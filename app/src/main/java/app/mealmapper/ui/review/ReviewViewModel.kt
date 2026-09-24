package app.mealmapper.ui.review

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.mealmapper.AppContainer
import app.mealmapper.data.ai.AiException
import app.mealmapper.data.ai.AiParsing
import app.mealmapper.data.ai.Kitchen
import app.mealmapper.domain.DbFood
import app.mealmapper.domain.FoodSearch
import app.mealmapper.domain.IndianPortions
import app.mealmapper.domain.PieceModel
import app.mealmapper.data.ai.LookupOutcome
import app.mealmapper.data.log.LoggedItem
import app.mealmapper.data.off.LookupResult
import app.mealmapper.data.off.ParseResult
import app.mealmapper.domain.Basis
import app.mealmapper.domain.FoodProduct
import app.mealmapper.domain.MealSlot
import app.mealmapper.domain.NutritionEntry
import app.mealmapper.domain.Nutrients
import app.mealmapper.domain.ProductSource
import app.mealmapper.domain.mealSlotFor
import app.mealmapper.domain.mealSlotIn
import app.mealmapper.domain.eatenAtFor
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import app.mealmapper.domain.parsePortion
import app.mealmapper.ui.common.fmt
import app.mealmapper.ui.photo.ImageTools
import java.time.Instant
import java.time.LocalTime
import java.util.UUID
import kotlin.math.roundToInt
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
    /** A plate of food: photo and/or the user's words. [photo] is null for "type what you ate". */
    /** A meal: one or more photos (one per dish is fine), and/or the user's words. */
    data class Meal(val photos: List<Uri>, override val note: String, val restaurant: Boolean, val restaurantName: String?) : ReviewRequest
    /** A food picked from the databank search. */
    data class Food(val id: String, override val note: String) : ReviewRequest
    /** Menu photos (one per page): top picks for the calories left today. */
    data class Menu(val photos: List<Uri>, override val note: String, val restaurantName: String?) : ReviewRequest
}

/** One food on a plate. Values are AI estimates; the user can change the grams or leave the item out. */
data class MealRow(
    val name: String,
    val gramsText: String,
    /** The AI's own estimate per 100 g. */
    val aiPer100: Nutrients,
    val estimatedGrams: Double,
    val lowKcal: Double?,
    val highKcal: Double?,
    val assumption: String?,
    val include: Boolean = true,
    /** Restaurant's published values (found by search), not an estimate. */
    val published: Boolean = false,
    /** Databank row the AI matched to this dish (home food), used instead of the AI's own values when [useDb]. */
    val db: DbFood? = null,
    val useDb: Boolean = db != null,
    /** Counted foods: "3 rotis, 18 cm" sets the grams. */
    val pieces: PieceModel? = IndianPortions.modelFor(name),
    val pieceCount: Double = pieces?.let { p -> Math.round(estimatedGrams / p.sizes[p.defaultSize].grams * 2) / 2.0 }?.coerceAtLeast(0.5) ?: 0.0,
    val pieceSize: Int = pieces?.defaultSize ?: 0,
) {
    val per100: Nutrients get() = if (useDb && db != null) db.per100 else aiPer100

    val grams: Double? get() = gramsText.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it <= ReviewForm.MAX_AMOUNT }
    val nutrients: Nutrients? get() = grams?.let { per100.scaled(it / 100.0) }

    /** The AI's kcal range, scaled to the grams now entered. */
    val range: Pair<Double, Double>?
        get() {
            val g = grams ?: return null
            val f = g / estimatedGrams
            return if (lowKcal != null && highKcal != null && lowKcal <= highKcal) lowKcal * f to highKcal * f else null
        }
}

data class MealForm(
    val rows: List<MealRow>,
    val slot: MealSlot,
    val day: LocalDate = LocalDate.now(),
    val note: String,
    val restaurant: Boolean,
    val saving: Boolean = false,
    val error: String? = null,
) {
    private val included get() = rows.filter { it.include }
    val total: Nutrients? get() = included.mapNotNull { it.nutrients }.reduceOrNull(Nutrients::plus)
    val range: Pair<Double, Double>?
        get() {
            val all = included.map { it.range ?: it.nutrients?.let { n -> n.energyKcal to n.energyKcal } ?: return null }
            return if (all.isEmpty()) null else all.sumOf { it.first } to all.sumOf { it.second }
        }
    val canSave: Boolean get() = !saving && included.isNotEmpty() && included.all { it.grams != null }
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
    /** The day the food was eaten (today, or an earlier day the user forgot to log). */
    val day: LocalDate = LocalDate.now(),
    val note: String = "",
    /** Label values per 100 g/ml as text, so the user can correct them field by field. */
    val per100Text: Map<NutrientField, String>,
    /** Problems the app's checks found in AI-read values. Shown in amber; the values stay editable. */
    val problems: List<String> = emptyList(),
    val editingLabel: Boolean = false,
    /** Set when the amount came from the user's note, e.g. "125 g (half pack)". Cleared on manual change. */
    val amountFromNote: String? = null,
    /** Counted foods (roti, bread, idli): count x size fills the amount. */
    val pieces: PieceModel? = null,
    val pieceCount: Double = 0.0,
    val pieceSize: Int = pieces?.defaultSize ?: 0,
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
    data class Meal(val form: MealForm) : ReviewState
    data class Menu(val picks: List<AiParsing.MealItem>, val kcalLeft: Int?, val restaurantName: String?) : ReviewState
    data class Saved(val message: String) : ReviewState
}

class ReviewViewModel(
    private val request: ReviewRequest,
    /** The meal chosen on the capture screen, or null to work it out here. */
    private val initialSlot: MealSlot?,
    /** The day chosen on the capture screen; null means today. */
    private val initialDay: LocalDate?,
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
        is ReviewRequest.Meal, is ReviewRequest.Menu, is ReviewRequest.Food -> null
    }

    init {
        start()
    }

    /** Capture screen's choice, else a meal named in the note, else the time of day. */
    private fun startSlot(): MealSlot = initialSlot ?: mealSlotIn(request.note) ?: mealSlotFor(LocalTime.now())

    /** Late logging: lunch saved at 9 pm is recorded at lunchtime; yesterday's dinner at 8:30 pm yesterday. */
    private fun eatenAt(slot: MealSlot, day: LocalDate): Instant =
        eatenAtFor(slot, day, LocalDateTime.now()).atZone(ZoneId.systemDefault()).toInstant()

    /** " · Mon 22 Sep" when the food was logged for another day, so the confirmation says where it went. */
    private fun dayNote(day: LocalDate): String =
        if (day == LocalDate.now()) "" else " · " + day.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM"))

    fun start() {
        viewModelScope.launch {
            when (request) {
                is ReviewRequest.Barcode -> barcodeFlow(request.code)
                is ReviewRequest.Label -> labelFlow(request.photo)
                is ReviewRequest.Web -> webFlow(request.photo)
                is ReviewRequest.Meal -> mealFlow(request)
                is ReviewRequest.Menu -> menuFlow(request)
                is ReviewRequest.Food -> foodFlow(request.id)
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

    private suspend fun mealFlow(meal: ReviewRequest.Meal) {
        _state.value = ReviewState.Loading(
            when (meal.photos.size) {
                0 -> "Working out your meal…"
                1 -> "Looking at your meal…"
                else -> "Looking at your ${meal.photos.size} photos…"
            },
        )
        val profile = c.profile.profile.value
        val kitchen = Kitchen(profile.katori, profile.oilGramsPerPersonDay)
        val restaurant = meal.restaurant || meal.restaurantName != null
        val items = runLookup {
            c.nutritionLookup.meal(jpegs(meal.photos), meal.note, meal.restaurant, meal.restaurantName, kitchen)
        } ?: return
        // Home food: swap the AI's own numbers for databank values where the AI confirms the same dish.
        var matched: Map<Int, DbFood> = emptyMap()
        if (!restaurant) {
            _state.value = ReviewState.Loading("Matching to the food databank…")
            matched = runCatching {
                val foods = c.foodDb.all()
                val candidates = items.map { FoodSearch.candidates(foods, it.name) }
                c.nutritionLookup.pickFromDb(items, candidates).mapNotNull { (i, id) -> foods.firstOrNull { it.id == id }?.let { i to it } }.toMap()
            }.getOrElse { if (it is CancellationException) throw it else emptyMap() }
        }
        showMeal(items, restaurant, matched)
    }

    private fun showMeal(items: List<AiParsing.MealItem>, restaurant: Boolean, matched: Map<Int, DbFood> = emptyMap()) {
        _state.value = ReviewState.Meal(
            MealForm(
                rows = items.mapIndexed { i, it ->
                    MealRow(
                        it.name, it.grams.roundToInt().toString(), it.per100, it.grams, it.lowKcal, it.highKcal, it.assumption,
                        published = it.published,
                        db = matched[i],
                    )
                },
                slot = startSlot(),
                day = initialDay ?: LocalDate.now(),
                note = request.note.take(ReviewForm.MAX_NOTE),
                restaurant = restaurant,
            ),
        )
    }

    private suspend fun menuFlow(menu: ReviewRequest.Menu) {
        _state.value = ReviewState.Loading("Reading the menu…")
        val left = runCatching {
            val cap = c.profile.profile.value.dailyCapKcal ?: return@runCatching null
            cap - c.healthConnect.todayTotals().energyKcal.roundToInt()
        }.getOrNull()
        val picks = runLookup { c.nutritionLookup.menu(jpegs(menu.photos), menu.note, left, menu.restaurantName) } ?: return
        _state.value = ReviewState.Menu(picks, left, menu.restaurantName)
    }

    /** The user ordered a pick: it becomes a one-item meal to check and save. */
    fun choosePick(pick: AiParsing.MealItem) = showMeal(listOf(pick), restaurant = true)

    private fun editMeal(block: (MealForm) -> MealForm) = _state.update {
        if (it is ReviewState.Meal && !it.form.saving) ReviewState.Meal(block(it.form).copy(error = null)) else it
    }

    private fun editRow(i: Int, block: (MealRow) -> MealRow) =
        editMeal { f -> f.copy(rows = f.rows.mapIndexed { j, r -> if (j == i) block(r) else r }) }

    fun setRowGrams(i: Int, v: String) = editRow(i) { it.copy(gramsText = v.filter { c -> c.isDigit() || c == '.' }.take(6)) }
    fun toggleRow(i: Int) = editRow(i) { it.copy(include = !it.include) }
    fun setMealSlot(v: MealSlot) = editMeal { it.copy(slot = v) }
    fun setMealDay(v: LocalDate) = editMeal { it.copy(day = v) }
    fun toggleRowDb(i: Int) = editRow(i) { it.copy(useDb = !it.useDb) }
    fun setRowPieces(i: Int, count: Double, size: Int) = editRow(i) { row ->
        val p = row.pieces ?: return@editRow row
        val c = count.coerceIn(0.0, 50.0)
        row.copy(pieceCount = c, pieceSize = size, gramsText = p.grams(c, size).roundToInt().toString())
    }
    fun setPieces(count: Double, size: Int) = edit { f ->
        val p = f.pieces ?: return@edit f
        val c = count.coerceIn(0.0, 50.0)
        f.copy(pieceCount = c, pieceSize = size, amountText = if (c > 0) p.grams(c, size).fmt() else "", amountFromNote = null)
    }

    private suspend fun foodFlow(id: String) {
        _state.value = ReviewState.Loading("Opening the databank…")
        val food = c.foodDb.byId(id)
        if (food == null) {
            _state.value = ReviewState.Failed("This food is no longer in the databank. Search again.")
            return
        }
        show(
            food.toProduct(c.profile.profile.value.katori),
            ProductSource.Databank(food.sourceLabel, "fry-corrected" in food.flags),
            food.warnings,
        )
    }

    /** Each food is its own Health Connect entry, so Google Health lists dal, rice and phulka separately. */
    fun saveMeal() {
        val form = (_state.value as? ReviewState.Meal)?.form ?: return
        if (!form.canSave) return
        val now = eatenAt(form.slot, form.day)
        val entries = form.rows.filter { it.include }.mapIndexed { i, row ->
            val tag = when {
                row.published -> "published"
                row.useDb && row.db != null -> row.db.source
                form.restaurant -> "restaurant est."
                else -> "est."
            }
            NutritionEntry(
                clientId = UUID.randomUUID().toString(),
                name = "${row.name} · ${row.grams!!.roundToInt()} g · $tag",
                slot = form.slot,
                // Seconds apart so entries never share a start time.
                eatenAt = now.plusSeconds(i.toLong()),
                nutrients = row.nutrients!!,
            )
        }
        _state.value = ReviewState.Meal(form.copy(saving = true))
        viewModelScope.launch {
            var written = 0
            val rows = form.rows.filter { it.include }
            runCatching {
                entries.forEachIndexed { i, e ->
                    c.healthConnect.write(e)
                    c.log.add(LoggedItem.of(e, rows[i].grams!!, "g", rows[i].per100, estimate = !rows[i].published))
                    written++
                }
            }
                .onSuccess {
                    val kcal = entries.sumOf { it.nutrients.energyKcal }
                    _state.value = ReviewState.Saved("Saved meal: ${entries.size} items, ${Math.round(kcal)} kcal${dayNote(form.day)}")
                }
                .onFailure { e ->
                    // Keep only the items not yet written, so a retry does not log anything twice.
                    val left = form.rows.filter { it.include }.drop(written)
                    _state.value = ReviewState.Meal(
                        form.copy(
                            rows = left + form.rows.filter { !it.include },
                            saving = false,
                            error = "Saved $written of ${entries.size}. Health Connect said: ${e.message ?: e.javaClass.simpleName}",
                        ),
                    )
                }
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

    /** Several photos are sent smaller so a 6-photo meal stays a quick upload on mobile data. */
    private suspend fun jpegs(uris: List<Uri>): List<ByteArray> = withContext(Dispatchers.IO) {
        val side = if (uris.size > 2) 1280 else 1600
        uris.map { ImageTools.jpeg(app, it, side) }
    }

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
                // Blank until the user says how much they ate, unless their note already did.
                amountText = parsed?.amount?.fmt().orEmpty(),
                amountFromNote = parsed?.explanation,
                slot = startSlot(),
                day = initialDay ?: LocalDate.now(),
                note = request.note.take(ReviewForm.MAX_NOTE),
                per100Text = per100Text(product.per100),
                problems = problems,
                editingLabel = problems.isNotEmpty() && source !is ProductSource.Databank,
                pieces = IndianPortions.modelFor(product.name),
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
                slot = startSlot(),
                day = initialDay ?: LocalDate.now(),
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
    fun setDay(v: LocalDate) = edit { it.copy(day = v) }
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
            eatenAt = eatenAt(form.slot, form.day),
            nutrients = portion,
        )
        _state.value = ReviewState.Ready(form.copy(saving = true))
        viewModelScope.launch {
            runCatching { c.healthConnect.write(entry) }
                .onSuccess {
                    // Remember the confirmed values for this barcode: next scan is instant and identical.
                    c.productCache.put(form.product.copy(name = baseName, brand = null, per100 = form.per100))
                    c.log.add(LoggedItem.of(entry, form.amount!!, form.product.basis.unit, form.per100, estimate = false))
                    _state.value = ReviewState.Saved("Saved: $baseName, ${Math.round(portion.energyKcal)} kcal${dayNote(form.day)}")
                }
                .onFailure { e ->
                    _state.value = ReviewState.Ready(
                        form.copy(saving = false, error = "Not saved. Health Connect said: ${e.message ?: e.javaClass.simpleName}"),
                    )
                }
        }
    }

    companion object {
        fun factory(request: ReviewRequest, slot: MealSlot?, day: LocalDate?, app: Context, container: AppContainer) =
            viewModelFactory { initializer { ReviewViewModel(request, slot, day, app.applicationContext, container) } }
    }
}
