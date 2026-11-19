package com.ghostblade.task;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Re-arms stored reminders after reboot or clock/timezone changes,
 *  mirroring Todoist's ScheduleRemindersNotifyMissedReceiver design. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        AlarmHelper.rescheduleAll(ctx);
        LocationReceiver.rearmAll(ctx);
    }
}
