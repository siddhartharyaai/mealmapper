package app.mealmapper.ui.review

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.mealmapper.AppContainer
import app.mealmapper.data.ai.AiException
import app.mealmapper.data.ai.LookupOutcome
import app.mealmapper.data.off.LookupResult
import app.mealmapper.data.off.ParseResult
import app.mealmapper.domain.Basis
import app.mealmapper.domain.FoodProduct
import app.mealmapper.domain.MealSlot
import app.mealmapper.domain.NutritionEntry
import app.mealmapper.domain.Nutrients
import app.mealmapper.domain.ProductSource
import app.mealmapper.domain.defaultPortion
import app.mealmapper.domain.mealSlotFor
import app.mealmapper.domain.parsePortion
import app.mealmapper.ui.common.fmt
import app.mealmapper.ui.photo.ImageTools
import java.time.Instant
import java.time.LocalTime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the Review screen was opened for. */
sealed interface ReviewRequest {
    val note: String

    data class Barcode(val code: String, override val note: String) : ReviewRequest
    data class Label(val photo: Uri, override val note: String, val barcode: String?, val name: String?) : ReviewRequest
    data class Web(val photo: Uri?, override val note: String, val barcode: String?, val name: String?) : ReviewRequest
}

enum class NutrientField(val label: String, val unit: String, val required: Boolean) {
    ENERGY("Energy", "kcal", true),
    PROTEIN("Protein", "g", true),
    CARBS("Carbohydrate", "g", true),
    SUGAR("of which sugar", "g", false),
    FAT("Fat", "g", true),
    SATURATED_FAT("of which saturated", "g", false),
    FIBER("Fibre", "g", false),
    SODIUM("Sodium", "mg", false),
}

data class ReviewForm(
    val product: FoodProduct,
    val source: ProductSource,
    val name: String,
    val amountText: String,
    val slot: MealSlot,
    val note: String = "",
    /** Label values per 100 g/ml as text, so the user can correct them field by field. */
    val per100Text: Map<NutrientField, String>,
    /** Problems the app's checks found in AI-read values. Shown in amber; the values stay editable. */
    val problems: List<String> = emptyList(),
    val editingLabel: Boolean = false,
    /** Set when the amount came from the user's note, e.g. "125 g (half pack)". Cleared on manual change. */
    val amountFromNote: String? = null,
    val saving: Boolean = false,
    val error: String? = null,
) {
    val isManual: Boolean get() = source == ProductSource.Manual

    val amount: Double? get() = amountText.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it <= MAX_AMOUNT }

    val per100: Nutrients
        get() {
            fun v(f: NutrientField) = per100Text[f]?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it >= 0 }
            return Nutrients(
                energyKcal = v(NutrientField.ENERGY) ?: 0.0,
                proteinG = v(NutrientField.PROTEIN) ?: 0.0,
                carbsG = v(NutrientField.CARBS) ?: 0.0,
                fatG = v(NutrientField.FAT) ?: 0.0,
                saturatedFatG = v(NutrientField.SATURATED_FAT),
                sugarG = v(NutrientField.SUGAR),
                fiberG = v(NutrientField.FIBER),
                sodiumMg = v(NutrientField.SODIUM),
            )
        }

    /** Nutrients for the chosen portion, or null while the amount is not valid. */
    val portion: Nutrients? get() = amount?.let { per100.scaled(it / 100.0) }

    companion object {
        const val MAX_AMOUNT = 5000.0
        const val MAX_NOTE = 60
    }
}

sealed interface ReviewState {
    data class Loading(val message: String) : ReviewState
    /** Nothing usable found. [canSearchWeb] is false when no Gemini key is set. */
    data class NotFound(
        val barcode: String?,
        val productName: String?,
        val reason: String,
        val canSearchWeb: Boolean,
        val webTried: Boolean,
    ) : ReviewState
    data class Failed(val message: String) : ReviewState
    data class Ready(val form: ReviewForm) : ReviewState
    data class Saved(val message: String) : ReviewState
}

class ReviewViewModel(
    private val request: ReviewRequest,
    private val app: Context,
    private val c: AppContainer,
) : ViewModel() {

    private val _state = MutableStateFlow<ReviewState>(ReviewState.Loading("Looking up the product…"))
    val state: StateFlow<ReviewState> = _state.asStateFlow()

    private var knownName: String? = (request as? ReviewRequest.Label)?.name ?: (request as? ReviewRequest.Web)?.name
    private val barcode: String? = when (request) {
        is ReviewRequest.Barcode -> request.code
        is ReviewRequest.Label -> request.barcode
        is ReviewRequest.Web -> request.barcode
    }

    init {
        start()
    }

    fun start() {
        viewModelScope.launch {
            when (request) {
                is ReviewRequest.Barcode -> barcodeFlow(request.code)
                is ReviewRequest.Label -> labelFlow(request.photo)
                is ReviewRequest.Web -> webFlow(request.photo)
            }
        }
    }

    /** Saved product -> Open Food Facts -> web (automatic when a Gemini key is set). */
    private suspend fun barcodeFlow(code: String) {
        c.productCache.get(code)?.let {
            show(it, ProductSource.Saved)
            return
        }
        _state.value = ReviewState.Loading("Looking up the product…")
        when (val result = c.openFoodFacts.lookup(code)) {
            is LookupResult.Failed -> _state.value = ReviewState.Failed(result.reason)
            is LookupResult.Found -> when (val parsed = result.result) {
                is ParseResult.Product -> show(parsed.product, ProductSource.OpenFoodFacts)
                is ParseResult.NoNutrition -> {
                    knownName = parsed.name
                    webOrNotFound("Open Food Facts has ${parsed.name ?: "this product"} but no nutrition values.")
                }
                ParseResult.NotFound -> webOrNotFound("Not in Open Food Facts.")
            }
        }
    }

    private suspend fun webOrNotFound(reason: String) {
        if (c.aiSettings.hasKey.value) webFlow(null) else notFound(reason, webTried = false)
    }

    /** User-triggered retry of the web search from the Not found screen. */
    fun searchWeb() {
        viewModelScope.launch { webFlow((request as? ReviewRequest.Web)?.photo) }
    }

    private suspend fun webFlow(photo: Uri?) {
        _state.value = ReviewState.Loading("Searching the web for ${knownName ?: "this product"}…")
        val outcome = runLookup {
            c.nutritionLookup.web(barcode, knownName, request.note, photo?.let { jpeg(it) })
        } ?: return
        when (outcome) {
            is LookupOutcome.Found -> show(outcome.product, outcome.source, outcome.problems)
            is LookupOutcome.NotFound -> notFound(outcome.reason, webTried = true)
        }
    }

    private suspend fun labelFlow(photo: Uri) {
        _state.value = ReviewState.Loading("Reading the label…")
        val outcome = runLookup { c.nutritionLookup.label(jpeg(photo), barcode, knownName, request.note) } ?: return
        when (outcome) {
            is LookupOutcome.Found -> show(outcome.product, outcome.source, outcome.problems)
            is LookupOutcome.NotFound -> _state.value = ReviewState.Failed(outcome.reason)
        }
    }

    private suspend fun <T> runLookup(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: AiException) {
        _state.value = ReviewState.Failed(e.message ?: "The AI lookup failed. Try again.")
        null
    } catch (e: Exception) {
        _state.value = ReviewState.Failed("Could not read the photo. Try another one.")
        null
    }

    private suspend fun jpeg(uri: Uri): ByteArray = withContext(Dispatchers.IO) { ImageTools.jpeg(app, uri) }

    private fun notFound(reason: String, webTried: Boolean) {
        _state.value = ReviewState.NotFound(barcode, knownName, reason, c.aiSettings.hasKey.value, webTried)
    }

    private fun show(product: FoodProduct, source: ProductSource, problems: List<String> = emptyList()) {
        val parsed = parsePortion(request.note, product)
        _state.value = ReviewState.Ready(
            ReviewForm(
                product = product,
                source = source,
                name = listOfNotNull(product.brand?.takeUnless { product.name.contains(it, ignoreCase = true) }, product.name)
                    .joinToString(" "),
                amountText = parsed?.amount?.fmt() ?: defaultPortion(product).fmt(),
                amountFromNote = parsed?.explanation,
                slot = mealSlotFor(LocalTime.now()),
                note = request.note.take(ReviewForm.MAX_NOTE),
                per100Text = per100Text(product.per100),
                problems = problems,
                editingLabel = problems.isNotEmpty(),
            ),
        )
    }

    /** Not found anywhere: the user types the per-100 g values from the printed label. */
    fun enterManually() {
        val product = FoodProduct(barcode.orEmpty(), knownName.orEmpty(), null, Nutrients(0.0, 0.0, 0.0, 0.0), Basis.GRAMS, null, null)
        _state.value = ReviewState.Ready(
            ReviewForm(
                product = product,
                source = ProductSource.Manual,
                name = knownName.orEmpty(),
                amountText = "",
                slot = mealSlotFor(LocalTime.now()),
                note = request.note.take(ReviewForm.MAX_NOTE),
                per100Text = NutrientField.entries.associateWith { "" },
                editingLabel = true,
            ),
        )
    }

    private fun per100Text(n: Nutrients) = mapOf(
        NutrientField.ENERGY to n.energyKcal,
        NutrientField.PROTEIN to n.proteinG,
        NutrientField.CARBS to n.carbsG,
        NutrientField.SUGAR to n.sugarG,
        NutrientField.FAT to n.fatG,
        NutrientField.SATURATED_FAT to n.saturatedFatG,
        NutrientField.FIBER to n.fiberG,
        NutrientField.SODIUM to n.sodiumMg,
    ).mapValues { (_, v) -> v?.fmt() ?: "" }

    private fun edit(block: (ReviewForm) -> ReviewForm) = _state.update {
        if (it is ReviewState.Ready && !it.form.saving) ReviewState.Ready(block(it.form).copy(error = null)) else it
    }

    fun setName(v: String) = edit { it.copy(name = v) }
    fun setAmount(v: String) =
        edit { it.copy(amountText = v.filter { c -> c.isDigit() || c == '.' }.take(6), amountFromNote = null) }
    fun setSlot(v: MealSlot) = edit { it.copy(slot = v) }
    fun setNote(v: String) = edit { it.copy(note = v.take(ReviewForm.MAX_NOTE)) }
    fun toggleEditLabel() = edit { it.copy(editingLabel = !it.editingLabel) }
    fun setPer100(field: NutrientField, v: String) =
        edit { it.copy(per100Text = it.per100Text + (field to v.filter { c -> c.isDigit() || c == '.' }.take(7))) }

    fun save() {
        val form = (_state.value as? ReviewState.Ready)?.form ?: return
        if (form.saving) return
        val portion = form.portion
        if (portion == null) {
            _state.value = ReviewState.Ready(form.copy(error = "Enter how much you had, in ${form.product.basis.unit}."))
            return
        }
        val missing = NutrientField.entries.filter { it.required && form.per100Text[it].isNullOrBlank() }
        if (missing.isNotEmpty()) {
            _state.value = ReviewState.Ready(
                form.copy(editingLabel = true, error = "Fill in ${missing.joinToString { it.label.lowercase() }} from the label."),
            )
            return
        }
        val baseName = form.name.trim().ifEmpty { form.product.name }.ifEmpty { "Packaged food" }
        val note = form.note.trim()
        val entry = NutritionEntry(
            clientId = UUID.randomUUID().toString(),
            name = if (note.isEmpty()) baseName else "$baseName · $note",
            slot = form.slot,
            eatenAt = Instant.now(),
            nutrients = portion,
        )
        _state.value = ReviewState.Ready(form.copy(saving = true))
        viewModelScope.launch {
            runCatching { c.healthConnect.write(entry) }
                .onSuccess {
                    // Remember the confirmed values for this barcode: next scan is instant and identical.
                    c.productCache.put(form.product.copy(name = baseName, brand = null, per100 = form.per100))
                    _state.value = ReviewState.Saved("Saved: $baseName, ${Math.round(portion.energyKcal)} kcal")
                }
                .onFailure { e ->
                    _state.value = ReviewState.Ready(
                        form.copy(saving = false, error = "Not saved. Health Connect said: ${e.message ?: e.javaClass.simpleName}"),
                    )
                }
        }
    }

    companion object {
        fun factory(request: ReviewRequest, app: Context, container: AppContainer) =
            viewModelFactory { initializer { ReviewViewModel(request, app.applicationContext, container) } }
    }
}
