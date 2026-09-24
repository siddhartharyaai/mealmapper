package app.mealmapper.domain

import java.time.Instant

/** One confirmed food log, ready to write to Health Connect. */
data class NutritionEntry(
    val clientId: String,
    val name: String,
    val slot: MealSlot,
    val eatenAt: Instant,
    val nutrients: Nutrients,
)
