package app.mealmapper

import android.app.Application
import app.mealmapper.data.chat.ChatMessage
import app.mealmapper.data.chat.ChatWorker
import app.mealmapper.data.chat.DailySummary
import app.mealmapper.data.chat.Notifier

class MealMapperApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        container = AppContainer(this)
        Notifier.channels(this)
        // A message left waiting (phone restarted, app updated): make sure its job exists. No-op if it does.
        runCatching {
            container.chat.messages.value.filterIsInstance<ChatMessage.User>().filter { it.active }.forEach { ChatWorker.enqueue(this, it.id) }
            DailySummary.schedule(this, container.prefs.summaryOn.value, container.prefs.summaryMinute.value)
        }
    }
}
