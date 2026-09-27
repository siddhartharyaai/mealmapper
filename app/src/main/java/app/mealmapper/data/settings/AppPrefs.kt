package app.mealmapper.data.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Small switches: the daily summary, and the one-time prompts already shown. */
class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)

    private val _summaryOn = MutableStateFlow(prefs.getBoolean(SUMMARY_ON, true))
    val summaryOn: StateFlow<Boolean> = _summaryOn.asStateFlow()

    private val _summaryMinute = MutableStateFlow(prefs.getInt(SUMMARY_MINUTE, 21 * 60 + 30))
    /** Minute of the day for the daily summary; 21:30 by default. */
    val summaryMinute: StateFlow<Int> = _summaryMinute.asStateFlow()

    fun setSummary(on: Boolean, minuteOfDay: Int) {
        prefs.edit().putBoolean(SUMMARY_ON, on).putInt(SUMMARY_MINUTE, minuteOfDay).apply()
        _summaryOn.value = on
        _summaryMinute.value = minuteOfDay
    }

    var askedNotifications: Boolean
        get() = prefs.getBoolean(ASKED_NOTIFY, false)
        set(v) = prefs.edit().putBoolean(ASKED_NOTIFY, v).apply()

    var dismissedBattery: Boolean
        get() = prefs.getBoolean(DISMISSED_BATTERY, false)
        set(v) = prefs.edit().putBoolean(DISMISSED_BATTERY, v).apply()

    private companion object {
        const val SUMMARY_ON = "summary_on"
        const val SUMMARY_MINUTE = "summary_minute"
        const val ASKED_NOTIFY = "asked_notifications"
        const val DISMISSED_BATTERY = "dismissed_battery"
    }
}
