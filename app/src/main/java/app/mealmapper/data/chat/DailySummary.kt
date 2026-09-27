package app.mealmapper.data.chat

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.mealmapper.MealMapperApp
import app.mealmapper.domain.DaySummary
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Schedules the evening summary; the text itself is [DaySummary.text]. */
object DailySummary {
    /** Schedules (or cancels) the daily notification at [minuteOfDay]. Safe to call on every app start. */
    fun schedule(context: Context, on: Boolean, minuteOfDay: Int) {
        val wm = WorkManager.getInstance(context)
        if (!on) {
            wm.cancelUniqueWork(NAME)
            return
        }
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(LocalTime.of(minuteOfDay / 60, minuteOfDay % 60))
        if (!next.isAfter(now)) next = next.plusDays(1)
        val request = PeriodicWorkRequestBuilder<DailySummaryWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(Duration.between(now, next).toMinutes(), TimeUnit.MINUTES)
            .addTag("$minuteOfDay")
            .build()
        wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private const val NAME = "daily-summary"
}

class DailySummaryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as MealMapperApp).container
        if (!c.prefs.summaryOn.value) return Result.success()
        val zone = ZoneId.systemDefault()
        val today = c.log.items.value.filter { it.time.atZone(zone).toLocalDate() == LocalDate.now() }
        val (title, text) = DaySummary.text(today.map { it.mealSlot to it.nutrients }, c.profile.profile.value.dailyCapKcal)
        Notifier.daily(applicationContext, title, text)
        return Result.success()
    }
}
