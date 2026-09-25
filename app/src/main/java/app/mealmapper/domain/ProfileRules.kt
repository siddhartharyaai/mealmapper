package app.mealmapper.domain

/** Input limits for Settings. Wide enough for real people, narrow enough to catch typos (e.g. 19000 kcal). */
object ProfileRules {
    val AGE = 10..100
    val WEIGHT_KG = 30.0..250.0
    val CAP_KCAL = 800..5000

    fun ageError(v: Int?): String? = if (v != null && v !in AGE) "Age must be ${AGE.first}-${AGE.last}." else null
    fun weightError(v: Double?): String? =
        if (v != null && v !in WEIGHT_KG) "Weight must be ${WEIGHT_KG.start.toInt()}-${WEIGHT_KG.endInclusive.toInt()} kg." else null
    fun capError(v: Int?): String? =
        if (v != null && v !in CAP_KCAL) "Cap must be ${CAP_KCAL.first}-${CAP_KCAL.last} kcal." else null
}

/** Today's totals across every app that writes to Health Connect. */
data class DayTotals(
    val energyKcal: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val fiberG: Double = 0.0,
    val sugarG: Double = 0.0,
)
