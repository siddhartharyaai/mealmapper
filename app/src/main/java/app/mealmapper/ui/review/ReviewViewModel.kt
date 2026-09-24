package app.mealmapper.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.mealmapper.data.health.HealthConnectGateway
import app.mealmapper.data.off.LookupResult
import app.mealmapper.data.off.OpenFoodFactsClient
import app.mealmapper.data.off.ParseResult
import app.mealmapper.domain.Basis
import app.mealmapper.domain.FoodProduct
import app.mealmapper.domain.MealSlot
import app.mealmapper.domain.NutritionEntry
import app.mealmapper.domain.Nutrients
import app.mealmapper.domain.defaultPortion
import app.mealmapper.domain.mealSlotFor
import app.mealmapper.ui.common.fmt
import java.time.Instant
import java.time.LocalTime
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    val name: String,
    val amountText: String,
    val slot: MealSlot,
    val note: String = "",
    /** Label values per 100 g/ml as text, so the user can correct them field by field. */
    val per100Text: Map<NutrientField, String>,
    val editingLabel: Boolean = false,
    /** True when the values were typed from the label, not taken from Open Food Facts. */
    val fromLabel: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
) {
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
    data object Loading : ReviewState
    data class NotFound(val barcode: String, val productName: String?) : ReviewState
    data class Failed(val message: String) : ReviewState
    data class Ready(val form: ReviewForm) : ReviewState
    data class Saved(val message: String) : ReviewState
}

class ReviewViewModel(
    private val barcode: String,
    private val openFoodFacts: OpenFoodFactsClient,
    private val healthConnect: HealthConnectGateway,
) : ViewModel() {

    private val _state = MutableStateFlow<ReviewState>(ReviewState.Loading)
    val state: StateFlow<ReviewState> = _state.asStateFlow()

    init {
        lookup()
    }

    fun lookup() {
        _state.value = ReviewState.Loading
        viewModelScope.launch {
            _state.value = when (val result = openFoodFacts.lookup(barcode)) {
                is LookupResult.Failed -> ReviewState.Failed(result.reason)
                is LookupResult.Found -> when (val parsed = result.result) {
                    ParseResult.NotFound -> ReviewState.NotFound(barcode, null)
                    is ParseResult.NoNutrition -> ReviewState.NotFound(barcode, parsed.name)
                    is ParseResult.Product -> ReviewState.Ready(formFor(parsed.product))
                }
            }
        }
    }

    /** Not in Open Food Facts: the user types the per-100 g values from the printed label. */
    fun enterManually(productName: String?) {
        val empty = Nutrients(0.0, 0.0, 0.0, 0.0)
        val product = FoodProduct(barcode, productName ?: "", null, empty, Basis.GRAMS, null, null)
        _state.value = ReviewState.Ready(
            formFor(product).copy(
                amountText = "",
                per100Text = NutrientField.entries.associateWith { "" },
                editingLabel = true,
                fromLabel = true,
            ),
        )
    }

    private fun formFor(product: FoodProduct) = ReviewForm(
        product = product,
        name = listOfNotNull(product.brand?.takeUnless { product.name.contains(it, ignoreCase = true) }, product.name)
            .joinToString(" "),
        amountText = defaultPortion(product).fmt(),
        slot = mealSlotFor(LocalTime.now()),
        per100Text = with(product.per100) {
            mapOf(
                NutrientField.ENERGY to energyKcal,
                NutrientField.PROTEIN to proteinG,
                NutrientField.CARBS to carbsG,
                NutrientField.SUGAR to sugarG,
                NutrientField.FAT to fatG,
                NutrientField.SATURATED_FAT to saturatedFatG,
                NutrientField.FIBER to fiberG,
                NutrientField.SODIUM to sodiumMg,
            ).mapValues { (_, v) -> v?.fmt() ?: "" }
        },
    )

    private fun edit(block: (ReviewForm) -> ReviewForm) = _state.update {
        if (it is ReviewState.Ready && !it.form.saving) ReviewState.Ready(block(it.form).copy(error = null)) else it
    }

    fun setName(v: String) = edit { it.copy(name = v) }
    fun setAmount(v: String) = edit { it.copy(amountText = v.filter { c -> c.isDigit() || c == '.' }.take(6)) }
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
            runCatching { healthConnect.write(entry) }
                .onSuccess {
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
        fun factory(barcode: String, openFoodFacts: OpenFoodFactsClient, healthConnect: HealthConnectGateway) =
            viewModelFactory { initializer { ReviewViewModel(barcode, openFoodFacts, healthConnect) } }
    }
}
