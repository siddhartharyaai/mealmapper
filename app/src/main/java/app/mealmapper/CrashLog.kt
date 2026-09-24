package app.mealmapper

import android.content.Context
import android.os.Build
import java.io.File
import java.time.LocalDateTime

/**
 * Saves the last crash to a file so the user can copy it from Home and send it.
 * No analytics service: the report stays on the phone until the user shares it.
 */
object CrashLog {
    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val file = File(context.filesDir, FILE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                file.writeText(
                    buildString {
                        appendLine("Meal Mapper ${BuildConfigInfo.version(context)} · ${LocalDateTime.now()}")
                        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
                        appendLine("Thread: ${thread.name}")
                        appendLine(error.stackTraceToString())
                    },
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(context: Context): String? = File(context.filesDir, FILE).takeIf { it.exists() }?.readText()

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
    }
}

private object BuildConfigInfo {
    fun version(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "?"
}
