package app.mealmapper.ui.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/** Whether Android lets Meal Mapper work in the background, and the one system prompt that allows it. */
object Background {
    fun unrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: true

    /** The system's "Let app always run in background?" dialog; on Samsung this sets battery use to Unrestricted. */
    fun ask(context: Context) {
        val uri = Uri.parse("package:${context.packageName}")
        runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, uri)) }
            .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)) } }
    }
}
