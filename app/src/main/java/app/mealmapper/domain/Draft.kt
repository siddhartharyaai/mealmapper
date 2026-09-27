package app.mealmapper.domain

import java.time.LocalDate
import kotlin.math.roundToInt
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Where an item's values came from, strongest first. Shown on every row. */
enum class SourceKind(val badge: String) {
    MEMORY("Your food"),
    LABEL("Label"),
    DATABANK("Databank"),
    WEB("Online"),
    AI("AI estimate"),
}

/**
 * What a still-loading item needs for its lookup, so the lookup can resume after Android stops the app.
 * Mirrors the lookup fields of the chat model's plan item.
 */
@Serializable
data class LookupSpec(
    val kind: String?,
    val searchName: String?,
    /** Web-search phrase for a named product. */
    val product: String?,
    val restaurant: String?,
    val note: String?,
)

/**
 * One food on a log card: a quantity in a natural unit ("2 scoop", "1 katori", "3 roti") and the grams each unit
 * weighs, so changing either keeps every nutrient consistent. Values are per 100 g (or ml).
 */
@Serializable
data class DraftItem(
    val name: String,
    val said: String?,
    val qty: Double,
    val unit: String,
    /** Grams per 1 unit, e.g. {"scoop": 33, "g": 1}. Always contains the base unit ("g" or "ml"). */
    val units: Map<String, Double>,
    val per100: Nutrients,
    val basis: Basis,
    val sourceKind: SourceKind,
    /** Where exactly, e.g. "INDB: Chapati/Roti" or "healthifyme.com, nutritionix.com". */
    val sourceDetail: String?,
    /** Set when this item came from, or will be saved to, the user's memory. */
    val memoryId: String?,
    val include: Boolean = true,
    /** True while the app is still finding this item's values; [per100] is the AI's estimate until then. */
    val loading: Boolean = false,
    val lookup: LookupSpec? = null,
) {
    val grams: Double get() = qty * (units[unit] ?: 1.0)
    val nutrients: Nutrients get() = per100.scaled(grams / 100.0)

    /** "2 scoop · 66 g", "150 g". */
    val amountText: String
        get() {
            val q = if (qty % 1.0 == 0.0) qty.toInt().toString() else "%.1f".format(qty)
            val base = basis.unit
            return if (unit == base) "$q $base" else "$q $unit · ${grams.roundToInt()} $base"
        }

    /** Next or previous step for the − and + buttons: grams move by 10, counts by ½ below 2 and by 1 above. */
    fun step(up: Boolean): Double {
        val step = when {
            unit == basis.unit -> 10.0
            qty < 2.0 || (!up && qty <= 2.0) -> 0.5
            else -> 1.0
        }
        return (if (up) qty + step else qty - step).coerceAtLeast(if (unit == basis.unit) 10.0 else 0.5)
    }

    /**
     * Applies looked-up values to a loading item. The user may have changed the amount while it loaded, so the
     * quantity, unit and tick stay as they are now; values, source and extra units come from [found].
     */
    fun fill(found: DraftItem): DraftItem = copy(
        per100 = found.per100,
        sourceKind = found.sourceKind,
        sourceDetail = found.sourceDetail,
        units = found.units + units.filterKeys { it == unit || it !in found.units },
        loading = false,
        lookup = null,
    )

    /** A lookup that could not finish: keep the AI's estimate and say so. */
    fun gaveUp(reason: String): DraftItem =
        if (!loading) this else copy(loading = false, lookup = null, sourceKind = SourceKind.AI, sourceDetail = reason)
}

object LocalDateSerializer : KSerializer<LocalDate> {
    override val descriptor = PrimitiveSerialDescriptor("LocalDate", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString())
}

/** A log card: items plus when they were eaten. Nothing reaches Health Connect until the user taps Log. */
@Serializable
data class Draft(
    val id: Long,
    val items: List<DraftItem>,
    @Serializable(with = LocalDateSerializer::class) val day: LocalDate,
    val slot: MealSlot,
    /** A question from the assistant, e.g. "Is this your usual breakfast?" */
    val question: String? = null,
    val state: State = State.PENDING,
    /** Health Connect client ids written for this card, for Undo. */
    val writtenIds: List<String> = emptyList(),
    /** "Log it" also saves these items as a meal with this name ("this is always my pre-breakfast, save it"). */
    val saveAs: String? = null,
    /** Set once logged with [saveAs]: the meal really was saved. */
    val savedAs: String? = null,
) {
    enum class State { PENDING, SAVING, LOGGED, DISCARDED }

    val total: Nutrients get() = items.filter { it.include }.map { it.nutrients }.fold(Nutrients(0.0, 0.0, 0.0, 0.0)) { a, b -> a + b }
    val loading: Boolean get() = items.any { it.include && it.loading }
    val canLog: Boolean get() = state == State.PENDING && items.any { it.include } && !loading

    /** Plain text for the clipboard: "Breakfast, Sat 27 Sep: 2 roti (80 g) 240 kcal …". */
    fun asText(): String = buildString {
        append("${slot.label}, ${day}\n")
        items.filter { it.include }.forEach { append("• ${it.name}: ${it.amountText}, ${it.nutrients.energyKcal.roundToInt()} kcal\n") }
        val t = total
        append("Total ${t.energyKcal.roundToInt()} kcal · P ${t.proteinG.roundToInt()} g · C ${t.carbsG.roundToInt()} g · F ${t.fatG.roundToInt()} g")
    }
}

/** The units offered for an item: its natural unit, Indian piece sizes, katori, serving, and grams. */
object Units {
    fun forItem(
        name: String,
        basis: Basis,
        unit: String,
        gramsPerUnit: Double,
        katoriMl: Int,
        servingGrams: Double? = null,
        remembered: Map<String, Double> = emptyMap(),
        dish: Boolean = false,
    ): Map<String, Double> {
        val out = linkedMapOf<String, Double>()
        out.putAll(remembered)
        if (unit != basis.unit && gramsPerUnit > 0) out[unit] = gramsPerUnit
        IndianPortions.modelFor(name)?.let { m ->
            if (m.noun !in out) out[m.noun] = m.sizes[m.defaultSize].grams
        }
        servingGrams?.let { out.putIfAbsent("serving", it) }
        if (dish) out.putIfAbsent("katori", katoriMl.toDouble())
        out[basis.unit] = 1.0
        return out
    }
}
