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
)

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
            .apply()
        _profile.value = profile
    }

    private fun read() = Profile(
        ageYears = prefs.floatOrNull(AGE)?.toInt(),
        weightKg = prefs.floatOrNull(WEIGHT)?.toDouble(),
        dailyCapKcal = prefs.floatOrNull(CAP)?.toInt(),
    )

    private fun android.content.SharedPreferences.floatOrNull(key: String) =
        if (contains(key)) getFloat(key, 0f) else null

    private fun android.content.SharedPreferences.Editor.putOrRemove(key: String, value: Float?) =
        if (value == null) remove(key) else putFloat(key, value)

    private companion object {
        const val AGE = "age_years"
        const val WEIGHT = "weight_kg"
        const val CAP = "daily_cap_kcal"
    }
}
