package com.ghostblade.task

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import org.json.JSONObject

/** Home-screen widget: today's tasks, mirrored from the app into
 *  SharedPreferences ("gbt_widget" / "data" JSON). */
class TaskWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) updateOne(ctx, mgr, id)
    }

    companion object {
        const val PREFS = "gbt_widget"

        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, TaskWidget::class.java))
            for (id in ids) updateOne(ctx, mgr, id)
        }

        private fun updateOne(ctx: Context, mgr: AppWidgetManager, widgetId: Int) {
            // Follow the app's appearance: the Frost White theme gets the
            // light widget instead of the dark slab.
            val layout = if (Prefs.theme(ctx) == "white") R.layout.widget_light else R.layout.widget
            val rv = RemoteViews(ctx.packageName, layout)
            val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("data", "") ?: ""
            val lines = arrayOfNulls<String>(5)
            var open = 0
            var done = 0
            var stale = false
            try {
                val o = JSONObject(raw)
                open = o.optInt("open")
                done = o.optInt("done")
                // A snapshot computed for an earlier day is stale: the widget
                // refreshes on its own ~30 min schedule and nothing
                // recomputes it at midnight. Show a neutral line instead of
                // yesterday's tasks and counts. (Snapshots written before
                // this version carry no date and are treated as fresh.)
                val storedDate = o.optString("date", "")
                stale = storedDate.isNotEmpty() && storedDate != Dates.today()
                val a = o.optJSONArray("lines")
                if (a != null) for (i in 0 until minOf(5, a.length())) lines[i] = a.optString(i)
            } catch (ignored: Exception) { /* empty state below */ }

            if (stale) {
                rv.setTextViewText(R.id.wCount, "Open GhostBlade to refresh")
                val tv = intArrayOf(R.id.wL1, R.id.wL2, R.id.wL3, R.id.wL4, R.id.wL5)
                for (id in tv) rv.setViewVisibility(id, View.GONE)
                rv.setTextViewText(R.id.wEmpty, "Your tasks will refresh when you open the app.")
                rv.setViewVisibility(R.id.wEmpty, View.VISIBLE)
            } else {
                rv.setTextViewText(R.id.wCount, "$open to do · $done done today")
                val tv = intArrayOf(R.id.wL1, R.id.wL2, R.id.wL3, R.id.wL4, R.id.wL5)
                var any = false
                for (i in tv.indices) {
                    if (lines[i].isNullOrEmpty()) {
                        rv.setViewVisibility(tv[i], View.GONE)
                    } else {
                        any = true
                        rv.setViewVisibility(tv[i], View.VISIBLE)
                        rv.setTextViewText(tv[i], lines[i])
                    }
                }
                rv.setViewVisibility(R.id.wEmpty, if (any) View.GONE else View.VISIBLE)
            }

            val openApp = Intent(ctx, MainActivity::class.java)
            openApp.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
            rv.setOnClickPendingIntent(R.id.wRoot, PendingIntent.getActivity(ctx, 0, openApp, flags))
            mgr.updateAppWidget(widgetId, rv)
        }
    }
}
