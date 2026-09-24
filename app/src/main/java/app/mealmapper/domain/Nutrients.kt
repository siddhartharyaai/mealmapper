package app.mealmapper.domain

import kotlin.math.abs
import kotlin.math.max

/**
 * Nutrient amounts for one quantity of food (per 100 g, or for a portion).
 * Optional nutrients stay null when the source does not give them. Null is not zero.
 */
data class Nutrients(
    val energyKcal: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val saturatedFatG: Double? = null,
    val sugarG: Double? = null,
    val fiberG: Double? = null,
    val sodiumMg: Double? = null,
) {
    fun scaled(factor: Double): Nutrients = Nutrients(
        energyKcal = energyKcal * factor,
        proteinG = proteinG * factor,
        carbsG = carbsG * factor,
        fatG = fatG * factor,
        saturatedFatG = saturatedFatG?.times(factor),
        sugarG = sugarG?.times(factor),
        fiberG = fiberG?.times(factor),
        sodiumMg = sodiumMg?.times(factor),
    )

    /**
     * True when the stated energy and the energy from macros (4/4/9 kcal per g) differ by more
     * than 15%. This catches wrong labels, wrong OCR and wrong AI output. Very small amounts are
     * skipped because rounding on labels makes them noisy.
     */
    fun energyLooksWrong(): Boolean {
        val fromMacros = 4 * proteinG + 4 * carbsG + 9 * fatG
        val larger = max(energyKcal, fromMacros)
        if (larger < MIN_KCAL_TO_CHECK) return false
        return abs(energyKcal - fromMacros) / larger > ENERGY_TOLERANCE
    }

    private companion object {
        const val ENERGY_TOLERANCE = 0.15
        const val MIN_KCAL_TO_CHECK = 20.0
    }
}
