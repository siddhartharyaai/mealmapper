package app.mealmapper.data.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The user's own numbers. All optional: the app works without them. */
data class Profile(
    val ageYears: Int? = null,
    val weightKg: Double? = null,
    /** Daily calorie cap the user chose. Meal Mapper does not calculate it. */
    val dailyCapKcal: Int? = null,
    /** Kitchen calibration. Katori size in ml (default 150). */
    val katoriMl: Int? = null,
    /** Cooking oil + ghee the household uses per month, in litres, and how many people eat at home. */
    val oilLitresPerMonth: Double? = null,
    val peopleAtHome: Int? = null,
) {
    val katori: Int get() = katoriMl ?: DEFAULT_KATORI_ML

    /** Oil and ghee per person per day in grams (oil is about 0.92 g/ml), or null if not calibrated. */
    val oilGramsPerPersonDay: Double?
        get() {
            val litres = oilLitresPerMonth ?: return null
            val people = peopleAtHome?.takeIf { it > 0 } ?: return null
            return litres * 920.0 / 30.0 / people
        }

    companion object {
        const val DEFAULT_KATORI_ML = 150
    }
}

/** Three values do not need a database. SharedPreferences, exposed as a StateFlow. */
class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("profile", Context.MODE_PRIVATE)
    private val _profile = MutableStateFlow(read())
    val profile: StateFlow<Profile> = _profile.asStateFlow()

    fun save(profile: Profile) {
        prefs.edit()
            .putOrRemove(AGE, profile.ageYears?.toFloat())
            .putOrRemove(WEIGHT, profile.weightKg?.toFloat())
            .putOrRemove(CAP, profile.dailyCapKcal?.toFloat())
            .putOrRemove(KATORI, profile.katoriMl?.toFloat())
            .putOrRemove(OIL, profile.oilLitresPerMonth?.toFloat())
            .putOrRemove(PEOPLE, profile.peopleAtHome?.toFloat())
            .apply()
        _profile.value = profile
    }

    private fun read() = Profile(
        ageYears = prefs.floatOrNull(AGE)?.toInt(),
        weightKg = prefs.floatOrNull(WEIGHT)?.toDouble(),
        dailyCapKcal = prefs.floatOrNull(CAP)?.toInt(),
        katoriMl = prefs.floatOrNull(KATORI)?.toInt(),
        oilLitresPerMonth = prefs.floatOrNull(OIL)?.toDouble(),
        peopleAtHome = prefs.floatOrNull(PEOPLE)?.toInt(),
    )

    private fun android.content.SharedPreferences.floatOrNull(key: String) =
        if (contains(key)) getFloat(key, 0f) else null

    private fun android.content.SharedPreferences.Editor.putOrRemove(key: String, value: Float?) =
        if (value == null) remove(key) else putFloat(key, value)

    private companion object {
        const val AGE = "age_years"
        const val WEIGHT = "weight_kg"
        const val CAP = "daily_cap_kcal"
        const val KATORI = "katori_ml"
        const val OIL = "oil_litres_month"
        const val PEOPLE = "people_at_home"
    }
}
