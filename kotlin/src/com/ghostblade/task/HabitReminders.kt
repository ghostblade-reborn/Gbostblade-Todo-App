package com.ghostblade.task

import android.content.Context
import java.util.Calendar

/** Habit reminder plumbing (SHADOW-THREE-DESIGN §1.7): extends the
 *  existing one-shot AlarmHelper, never rewrites it. Each habit
 *  reminder time gets ONE alarm armed for its next due occurrence;
 *  when it fires, ReminderReceiver calls [resyncAfterFire] to arm the
 *  following occurrence (this covers daily, weekday, period and
 *  monthday habits uniformly). Alarm ids are
 *  "habit:<habitId>:<HH:mm>" so a habit's alarms cancel by prefix.
 *  The stored alarm text doubles as the notification body, so it is
 *  "Name: phrase" when a custom reminder phrase is set, else the name. */
object HabitReminders {

    private const val PREFIX = "habit:"

    fun alarmId(habitId: String, time: String) = PREFIX + habitId + ":" + time

    /** Inverse of [alarmId]. Parses from the END — the time is always
     *  the trailing ":HH:mm" (six chars) — so a habit id containing
     *  ":" (only possible via a crafted import) round-trips intact
     *  instead of being truncated at its first colon. Anything that
     *  does not end in a well-formed time is not a habit alarm id. */
    private val ALARM_TIME_TAIL = Regex(":\\d{2}:\\d{2}$")

    fun habitIdOf(alarmId: String): String? {
        if (!alarmId.startsWith(PREFIX)) return null
        val rest = alarmId.removePrefix(PREFIX)
        val tail = ALARM_TIME_TAIL.find(rest) ?: return null
        val id = rest.substring(0, tail.range.first)
        return id.ifEmpty { null }
    }

    fun displayText(h: HabitItem): String =
        if (h.reminderPhrase.isNotBlank() && h.reminderPhrase != h.name)
            h.name + ": " + h.reminderPhrase
        else h.name

    /** Epoch millis of the next due occurrence of [time] strictly after
     *  now. Scans up to 400 days ahead so monthday habits (worst case:
     *  the 31st, two months out) always resolve. */
    fun nextTrigger(h: HabitItem, time: String, now: Long = System.currentTimeMillis()): Long? {
        // Strict "HH:mm" only: a malformed time ("8", "25:99") yields
        // NO alarm instead of being leniently reinterpreted — "25:99"
        // used to roll over to next-day 02:39, possibly on a day the
        // habit is not even due. (The loader filters these too; this
        // is the second line of defence.)
        if (!Dates.isValidTimeStr(time)) return null
        val parts = time.split(":")
        val hh = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val mm = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val today = Dates.today()
        for (i in 0..400) {
            val d = Dates.addDays(today, i)
            if (!HabitLogic.isDueOn(h, d)) continue
            val c = Dates.parse(d)
            c.set(Calendar.HOUR_OF_DAY, hh)
            c.set(Calendar.MINUTE, mm)
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            if (c.timeInMillis > now) return c.timeInMillis
        }
        return null
    }

    fun cancel(ctx: Context, habitId: String) {
        val prefs = ctx.getSharedPreferences(AlarmHelper.PREFS, Context.MODE_PRIVATE)
        // Match on the PARSED habit id, not a raw prefix: cancelling
        // habit "a" must not also cancel habit "a:b"'s alarms.
        prefs.all.keys.filter { habitIdOf(it) == habitId }
            .forEach { AlarmHelper.cancel(ctx, it) }
    }

    fun sync(ctx: Context, h: HabitItem) {
        cancel(ctx, h.id)
        if (h.archived) return
        for (t in h.reminderTimes) {
            nextTrigger(h, t)?.let { AlarmHelper.schedule(ctx, alarmId(h.id, t), displayText(h), it) }
        }
    }

    fun syncAll(ctx: Context) {
        Store.state.habits.forEach { sync(ctx, it) }
    }

    /** Called by ReminderReceiver after a habit alarm fires (its
     *  one-shot entry has already been removed): arm the next one. */
    fun resyncAfterFire(ctx: Context, habitId: String) {
        val h = Store.state.habits.find { it.id == habitId } ?: return
        if (!h.archived) sync(ctx, h)
    }
}
