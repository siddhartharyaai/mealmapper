package app.mealmapper.domain

import kotlinx.serialization.Serializable

/**
 * A food the user has logged before. Repeats ("my whey", even misspelt) are recognised from here and logged with
 * the same values, with no new search. Learnt automatically when a card is logged.
 */
@Serializable
data class MemoryFood(
    val id: String,
    val name: String,
    /** Other ways the user said it: misspellings, Hinglish, short forms. Normalised. */
    val aliases: List<String> = emptyList(),
    val per100: Nutrients,
    val basis: String = "g",
    /** Grams per unit, e.g. {"scoop": 33.0, "g": 1.0}. */
    val units: Map<String, Double> = mapOf("g" to 1.0),
    val usualQty: Double = 1.0,
    val usualUnit: String = "g",
    /** e.g. "Online: truebasics.com" or "Databank: INDB". */
    val source: String = "",
    val uses: Int = 0,
    val lastUsed: Long = 0,
    /** How often it was logged at each meal, by MealSlot name. */
    val slotUses: Map<String, Int> = emptyMap(),
)

/** A named combination, e.g. "My usual breakfast" = omelette 1, toast 2 slices, papaya 1 bowl. */
@Serializable
data class SavedMeal(val id: String, val name: String, val slot: String, val parts: List<MealPart>)

@Serializable
data class MealPart(val foodId: String, val qty: Double, val unit: String)

/** One-tap suggestion above the chat box. */
data class Suggestion(val label: String, val mealId: String? = null, val foodId: String? = null)

object Memory {
    fun normalize(s: String): String = FoodSearch.normalize(s)

    /** Adds or updates a food from a logged item. Returns the new list and the food's id. */
    fun learn(foods: List<MemoryFood>, item: DraftItem, slot: MealSlot, now: Long): Pair<List<MemoryFood>, String> {
        val key = normalize(item.name)
        val existing = foods.firstOrNull { it.id == item.memoryId } ?: foods.firstOrNull { normalize(it.name) == key }
        val said = item.said?.let(::normalize)?.takeIf { it.isNotEmpty() && it != key }
        val source = when (item.sourceKind) {
            SourceKind.MEMORY -> existing?.source.orEmpty()
            else -> item.sourceKind.badge + (item.sourceDetail?.let { ": $it" } ?: "")
        }
        val updated = if (existing == null) {
            MemoryFood(
                id = "m" + (foods.size + 1) + "_" + (now % 100000),
                name = item.name,
                aliases = listOfNotNull(said),
                per100 = item.per100,
                basis = item.basis.unit,
                units = item.units,
                usualQty = item.qty,
                usualUnit = item.unit,
                source = source,
                uses = 1,
                lastUsed = now,
                slotUses = mapOf(slot.name to 1),
            )
        } else {
            existing.copy(
                aliases = (existing.aliases + listOfNotNull(said)).distinct().takeLast(12),
                per100 = item.per100,
                units = existing.units + item.units,
                usualQty = item.qty,
                usualUnit = item.unit,
                source = source.ifEmpty { existing.source },
                uses = existing.uses + 1,
                lastUsed = now,
                slotUses = existing.slotUses + (slot.name to (existing.slotUses[slot.name] ?: 0) + 1),
            )
        }
        val list = if (existing == null) foods + updated else foods.map { if (it.id == existing.id) updated else it }
        return list to updated.id
    }

    /** Saved meals for this meal first, then foods the user often has at this meal (at least twice). */
    fun suggestions(foods: List<MemoryFood>, meals: List<SavedMeal>, slot: MealSlot, limit: Int = 5): List<Suggestion> {
        val fromMeals = meals.filter { it.slot == slot.name }.map { Suggestion(it.name, mealId = it.id) }
        val fromFoods = foods.filter { (it.slotUses[slot.name] ?: 0) >= 2 }
            .sortedByDescending { it.slotUses[slot.name] ?: 0 }
            .map { f ->
                val q = if (f.usualQty % 1.0 == 0.0) f.usualQty.toInt().toString() else f.usualQty.toString()
                Suggestion("${f.name} · $q ${f.usualUnit}", foodId = f.id)
            }
        return (fromMeals + fromFoods).take(limit)
    }

    /** A remembered food as a card item, with its usual amount unless told otherwise. */
    fun toDraftItem(f: MemoryFood, qty: Double? = null, unit: String? = null, said: String? = null): DraftItem {
        val u = unit?.takeIf { it in f.units } ?: f.usualUnit.takeIf { it in f.units } ?: f.basis
        return DraftItem(
            name = f.name,
            said = said,
            qty = qty ?: if (u == f.usualUnit) f.usualQty else 1.0,
            unit = u,
            units = f.units + (f.basis to 1.0),
            per100 = f.per100,
            basis = if (f.basis == "ml") Basis.MILLILITRES else Basis.GRAMS,
            sourceKind = SourceKind.MEMORY,
            sourceDetail = f.source.ifEmpty { null },
            memoryId = f.id,
        )
    }

    /** The memory as short lines for the chat model: the most used and most recent first. */
    fun promptLines(foods: List<MemoryFood>, meals: List<SavedMeal>, limit: Int = 120): String {
        val f = foods.sortedWith(compareByDescending<MemoryFood> { it.uses }.thenByDescending { it.lastUsed }).take(limit)
        val foodLines = f.joinToString("\n") { m ->
            val units = m.units.filterKeys { it != m.basis }.entries.joinToString { "${it.key}=${it.value.toInt()}${m.basis}" }
            val aka = if (m.aliases.isNotEmpty()) " | also said: ${m.aliases.joinToString()}" else ""
            "${m.id} | ${m.name}$aka | units: ${units.ifEmpty { "-" }} | usually ${m.usualQty} ${m.usualUnit} | ${m.per100.energyKcal.toInt()} kcal/100${m.basis}"
        }
        val mealLines = meals.joinToString("\n") { meal ->
            val parts = meal.parts.joinToString { p -> "${foods.firstOrNull { it.id == p.foodId }?.name ?: p.foodId} ${p.qty} ${p.unit}" }
            "${meal.id} | ${meal.name} | ${meal.slot.lowercase()} | $parts"
        }
        return "FOODS:\n" + foodLines.ifEmpty { "(none yet)" } + "\nSAVED MEALS:\n" + mealLines.ifEmpty { "(none yet)" }
    }
}
