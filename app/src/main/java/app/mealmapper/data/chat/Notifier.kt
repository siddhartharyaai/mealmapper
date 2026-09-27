package app.mealmapper.data.chat

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.mealmapper.MainActivity
import app.mealmapper.R

/** The app's three notifications: a meal is ready (or failed), background work in progress, and the daily summary. */
object Notifier {
    private const val MEALS = "meals"
    private const val WORK = "work"
    private const val DAILY = "daily"
    const val WORK_ID = 1
    private const val DAILY_ID = 2

    /** The app is on screen: no need to notify, the chat shows it. Set by MainActivity. */
    @Volatile
    var appVisible = false

    fun channels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(MEALS, "Meals ready", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(WORK, "Working on a meal", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(DAILY, "Daily summary", NotificationManager.IMPORTANCE_DEFAULT))
    }

    /** "Pre-breakfast ready: 4 items, 92 kcal. Tap to check." Only when the app is not on screen. */
    fun meal(context: Context, messageId: Long, title: String, text: String) {
        if (appVisible) return
        post(context, (100 + messageId % 100_000).toInt(), base(context, MEALS, title, text).build())
    }

    fun daily(context: Context, title: String, text: String) = post(context, DAILY_ID, base(context, DAILY, title, text).build())

    /** Shown only on Android 11 and lower, where background work must run as a visible service. */
    fun working(context: Context): Notification =
        base(context, WORK, "Meal Mapper", "Working on your meal…").setOngoing(true).setSilent(true).build()

    private fun base(context: Context, channel: String, title: String, text: String): NotificationCompat.Builder {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
    }

    private fun post(context: Context, id: Int, n: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching { NotificationManagerCompat.from(context).notify(id, n) }
    }
}
