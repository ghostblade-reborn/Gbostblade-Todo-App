package com.ghostblade.task

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/** Schedules and cancels reminder alarms, and persists them so they can be
 *  restored after reboot (see BootReceiver). Storage format per reminder id:
 *  title + "\n" + triggerAtMillis. */
object AlarmHelper {

    const val PREFS = "gbt_reminders"
    private const val SEP = "\n"

    fun schedule(ctx: Context, id: String, title: String, triggerAtMillis: Long) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(id, title + SEP + triggerAtMillis).apply()
        arm(ctx, id, title, triggerAtMillis)
    }

    fun cancel(ctx: Context, id: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(id).apply()
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        am?.cancel(pendingIntent(ctx, id, ""))
    }

    /** Re-arm every stored reminder that is still in the future; drop past ones. */
    fun rescheduleAll(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val ed = prefs.edit()
        for ((key, v) in prefs.all) {
            if (v !is String) continue
            val idx = v.lastIndexOf(SEP)
            if (idx < 0) continue
            val title = v.substring(0, idx)
            val at = v.substring(idx + 1).toLongOrNull() ?: continue
            if (at > now) arm(ctx, key, title, at) else ed.remove(key)
        }
        ed.apply()
    }

    private fun arm(ctx: Context, id: String, title: String, triggerAtMillis: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = pendingIntent(ctx, id, title)
        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am.canScheduleExactAlarms() ->
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
                else -> am.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            }
        } catch (se: SecurityException) {
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            } catch (ignored: Exception) {
                /* reminder stays stored; fires on next boot reschedule */
            }
        }
    }

    private fun pendingIntent(ctx: Context, id: String, title: String): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java)
        i.putExtra("id", id)
        i.putExtra("title", title)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(ctx, id.hashCode(), i, flags)
    }
}
