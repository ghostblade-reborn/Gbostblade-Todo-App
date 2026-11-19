package com.ghostblade.task

import android.content.Context

/** Bridges tasks to the native alarm + location systems. */
object Reminders {

    fun trigger(t: TaskItem): Long? {
        val off = t.reminderOffsetMin ?: return null
        val date = t.dueDate ?: return null
        val parts = (t.dueTime ?: "09:00").split(":")
        val c = Dates.parse(date)
        c.set(java.util.Calendar.HOUR_OF_DAY, parts.getOrNull(0)?.toIntOrNull() ?: 9)
        c.set(java.util.Calendar.MINUTE, parts.getOrNull(1)?.toIntOrNull() ?: 0)
        c.set(java.util.Calendar.SECOND, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis - off * 60000L
    }

    fun sync(ctx: Context, t: TaskItem) {
        val at = trigger(t)
        if (at != null && at > System.currentTimeMillis()) {
            AlarmHelper.schedule(ctx, "rem_" + t.id, t.title, at)
        } else {
            AlarmHelper.cancel(ctx, "rem_" + t.id)
        }
    }

    fun drop(ctx: Context, t: TaskItem) = AlarmHelper.cancel(ctx, "rem_" + t.id)

    fun syncGeo(ctx: Context, t: TaskItem) {
        val lat = t.locLat
        val lng = t.locLng
        if (lat != null && lng != null) LocationReceiver.arm(ctx, t.id, t.title, lat, lng)
    }

    fun clearGeo(ctx: Context, t: TaskItem) {
        if (t.locLat != null) LocationReceiver.disarm(ctx, t.id)
    }

    fun syncAll(ctx: Context) {
        Store.state.tasks.forEach { sync(ctx, it); syncGeo(ctx, it) }
    }
}
