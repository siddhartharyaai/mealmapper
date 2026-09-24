package app.mealmapper.domain

import java.time.Instant

/**
 * One confirmed food log, ready to write to Health Connect.
 * Optional nutrients stay null when the source does not give them. Null is not zero.
 */
data class NutritionEntry(
    val clientId: String,
    val name: String,
    val slot: MealSlot,
    val eatenAt: Instant,
    val energyKcal: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val saturatedFatG: Double? = null,
    val sugarG: Double? = null,
    val fiberG: Double? = null,
    val sodiumMg: Double? = null,
)
