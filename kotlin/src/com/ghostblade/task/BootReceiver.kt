package com.ghostblade.task

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-arms stored reminders after reboot or clock/timezone changes,
 *  mirroring Todoist's ScheduleRemindersNotifyMissedReceiver design. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        AlarmHelper.rescheduleAll(ctx)
        LocationReceiver.rearmAll(ctx)
    }
}
