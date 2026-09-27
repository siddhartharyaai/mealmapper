package app.mealmapper.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import app.mealmapper.MainActivity
import app.mealmapper.R

/** Home-screen widget: "🎙 Speak a meal" opens the chat already recording; "⌨ Type" opens it with the keyboard up. */
class QuickWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_quick).apply {
            setOnClickPendingIntent(R.id.widget_speak, open(context, MainActivity.ACTION_SPEAK, 1))
            setOnClickPendingIntent(R.id.widget_type, open(context, MainActivity.ACTION_TYPE, 2))
        }
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    private fun open(context: Context, action: String, code: Int): PendingIntent = PendingIntent.getActivity(
        context,
        code,
        Intent(context, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
