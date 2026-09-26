package app.mealmapper.domain

import kotlin.math.roundToInt

/** Where an item's values came from, strongest first. Shown on every row. */
enum class SourceKind(val badge: String) {
    MEMORY("Your food"),
    LABEL("Label"),
    DATABANK("Databank"),
    WEB("Online"),
    AI("AI estimate"),
}

/**
 * One food on a log card: a quantity in a natural unit ("2 scoop", "1 katori", "3 roti") and the grams each unit
 * weighs, so changing either keeps every nutrient consistent. Values are per 100 g (or ml).
 */
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
}

/** A log card: items plus when they were eaten. Nothing reaches Health Connect until the user taps Log. */
data class Draft(
    val id: Long,
    val items: List<DraftItem>,
    val day: java.time.LocalDate,
    val slot: MealSlot,
    /** A question from the assistant, e.g. "Is this your usual breakfast?" */
    val question: String? = null,
    val state: State = State.PENDING,
    /** Health Connect client ids written for this card, for Undo. */
    val writtenIds: List<String> = emptyList(),
) {
    enum class State { PENDING, SAVING, LOGGED, DISCARDED }

    val total: Nutrients get() = items.filter { it.include }.map { it.nutrients }.fold(Nutrients(0.0, 0.0, 0.0, 0.0)) { a, b -> a + b }
    val canLog: Boolean get() = state == State.PENDING && items.any { it.include }
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
