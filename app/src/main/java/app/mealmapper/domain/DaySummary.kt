package app.mealmapper.domain

import kotlin.math.roundToInt

/** The evening summary: what was logged today, against the daily cap, and which main meal is missing. */
object DaySummary {
    /** Title and text for today's logged items (meal and nutrients of each). */
    fun text(items: List<Pair<MealSlot, Nutrients>>, capKcal: Int?): Pair<String, String> {
        if (items.isEmpty()) return "Nothing logged today" to "Tap to tell Meal Mapper what you ate."
        val t = items.map { it.second }.fold(Nutrients(0.0, 0.0, 0.0, 0.0)) { a, b -> a + b }
        val kcal = t.energyKcal.roundToInt()
        val title = when {
            capKcal == null -> "Today: $kcal kcal"
            kcal <= capKcal -> "Today: $kcal of $capKcal kcal (${capKcal - kcal} left)"
            else -> "Today: $kcal of $capKcal kcal (${kcal - capKcal} over)"
        }
        val macros = "P ${t.proteinG.roundToInt()} g · C ${t.carbsG.roundToInt()} g · F ${t.fatG.roundToInt()} g" +
            (t.fiberG?.let { " · Fibre ${it.roundToInt()} g" } ?: "") + (t.sugarG?.let { " · Sugar ${it.roundToInt()} g" } ?: "")
        val logged = items.map { it.first }.toSet()
        val missing = listOf(MealSlot.BREAKFAST, MealSlot.LUNCH, MealSlot.DINNER).filter { it !in logged }
        val gap = if (missing.isEmpty()) "" else "\nNot logged yet: " + missing.joinToString { it.label.lowercase() } + "."
        return title to macros + gap
    }
}
