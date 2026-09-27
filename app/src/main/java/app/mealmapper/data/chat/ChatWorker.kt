package app.mealmapper.data.chat

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.mealmapper.MealMapperApp
import java.util.concurrent.TimeUnit

/**
 * Processes one chat message in the background. WorkManager keeps it alive when the user switches apps, waits for
 * a network if there is none, re-runs it if Android stops it, and tries a network failure again (3 tries in all).
 */
class ChatWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY, -1)
        val engine = (applicationContext as MealMapperApp).container.chatEngine
        return if (engine.run(id, lastAttempt = runAttemptCount >= MAX_RETRIES)) Result.success() else Result.retry()
    }

    /** Only used on Android 11 and lower, where expedited work runs as a foreground service. */
    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(Notifier.WORK_ID, Notifier.working(applicationContext))

    companion object {
        private const val KEY = "message"
        private const val MAX_RETRIES = 2

        /** Starts (or, for a message already queued, keeps) the job for message [id]. */
        fun enqueue(context: Context, id: Long, replace: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<ChatWorker>()
                .setInputData(workDataOf(KEY to id))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "chat-$id",
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
