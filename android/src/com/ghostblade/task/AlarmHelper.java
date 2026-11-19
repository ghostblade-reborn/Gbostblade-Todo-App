package com.ghostblade.task;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.Map;

/** Schedules and cancels reminder alarms, and persists them so they can be
 *  restored after reboot (see BootReceiver). Storage format per reminder id:
 *  title + "\n" + triggerAtMillis. */
public class AlarmHelper {

    public static final String PREFS = "gbt_reminders";
    private static final String SEP = "\n";

    public static void schedule(Context ctx, String id, String title, long triggerAtMillis) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString(id, title + SEP + triggerAtMillis).apply();
        arm(ctx, id, title, triggerAtMillis);
    }

    public static void cancel(Context ctx, String id) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().remove(id).apply();
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pendingIntent(ctx, id, ""));
    }

    /** Re-arm every stored reminder that is still in the future; drop past ones. */
    public static void rescheduleAll(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        SharedPreferences.Editor ed = prefs.edit();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            Object v = e.getValue();
            if (!(v instanceof String)) continue;
            String s = (String) v;
            int idx = s.lastIndexOf(SEP);
            if (idx < 0) continue;
            String title = s.substring(0, idx);
            long at;
            try { at = Long.parseLong(s.substring(idx + 1)); } catch (Exception ex) { continue; }
            if (at > now) arm(ctx, e.getKey(), title, at);
            else ed.remove(e.getKey());
        }
        ed.apply();
    }

    private static void arm(Context ctx, String id, String title, long triggerAtMillis) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = pendingIntent(ctx, id, title);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
            }
        } catch (SecurityException se) {
            try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi); }
            catch (Exception ignored) { /* reminder stays stored; fires on next boot reschedule */ }
        }
    }

    private static PendingIntent pendingIntent(Context ctx, String id, String title) {
        Intent i = new Intent(ctx, ReminderReceiver.class);
        i.putExtra("id", id);
        i.putExtra("title", title);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, id.hashCode(), i, flags);
    }
}
