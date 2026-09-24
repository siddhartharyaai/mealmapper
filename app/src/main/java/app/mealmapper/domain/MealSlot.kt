package app.mealmapper.domain

import java.time.LocalTime

enum class MealSlot { BREAKFAST, LUNCH, SNACK, DINNER }

private val BREAKFAST_START = LocalTime.of(5, 0)
private val LUNCH_START = LocalTime.of(11, 0)
private val SNACK_START = LocalTime.of(16, 0)
private val DINNER_START = LocalTime.of(20, 30)

/**
 * Default meal for a local time, tuned to Mumbai eating hours (late lunch, late dinner).
 * It is only a default: the user can change it on the Review screen.
 */
fun mealSlotFor(time: LocalTime): MealSlot = when {
    time < BREAKFAST_START -> MealSlot.DINNER
    time < LUNCH_START -> MealSlot.BREAKFAST
    time < SNACK_START -> MealSlot.LUNCH
    time < DINNER_START -> MealSlot.SNACK
    else -> MealSlot.DINNER
}
