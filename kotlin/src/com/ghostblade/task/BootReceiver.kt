package com.ghostblade.task

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-arms stored reminders after reboot or clock/timezone changes,
 *  mirroring Todoist's ScheduleRemindersNotifyMissedReceiver design. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                // A clock or timezone change moves "9:00 local" — re-arming
                // the stored absolute trigger would fire it at the old
                // instant. Recompute from the task data instead, so the
                // reminder stays at the right local time until the app is
                // next opened. Falls back to a blind re-arm if state can't
                // be loaded (e.g. storage trouble in the receiver).
                try {
                    Store.init(ctx)
                    Reminders.syncAll(ctx)
                } catch (e: Exception) {
                    AlarmHelper.rescheduleAll(ctx)
                }
                LocationReceiver.rearmAll(ctx)
            }
            else -> {
                AlarmHelper.rescheduleAll(ctx)
                LocationReceiver.rearmAll(ctx)
            }
        }
    }
}
