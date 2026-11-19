package com.ghostblade.task;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;

public class ReminderReceiver extends BroadcastReceiver {

    public static final String CHANNEL_ID = "gbt_reminders_v2";

    public static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "Task reminders", NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("GhostBlade Task reminders");
                Uri snd = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                AudioAttributes aa = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build();
                ch.setSound(snd, aa);
                ch.enableVibration(true);
                ch.setVibrationPattern(new long[]{0, 220, 120, 220});
                nm.createNotificationChannel(ch);
            }
        }
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String id = intent.getStringExtra("id");
        String title = intent.getStringExtra("title");
        if (title == null) title = "Task reminder";
        ensureChannel(ctx);

        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent tap = PendingIntent.getActivity(ctx, id == null ? 0 : id.hashCode(), open, flags);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(ctx, CHANNEL_ID)
                : new Notification.Builder(ctx);
        b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
         .setContentTitle("GhostBlade Task")
         .setContentText(title)
         .setStyle(new Notification.BigTextStyle().bigText(title))
         .setContentIntent(tap)
         .setAutoCancel(true)
         .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
         .setVibrate(new long[]{0, 220, 120, 220})
         .setDefaults(Notification.DEFAULT_LIGHTS)
         .setPriority(Notification.PRIORITY_HIGH);

        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(id == null ? (int) System.currentTimeMillis() : id.hashCode(), b.build());

        // One-shot: remove from the store so BootReceiver does not re-arm it.
        if (id != null) {
            ctx.getSharedPreferences(AlarmHelper.PREFS, Context.MODE_PRIVATE).edit().remove(id).apply();
        }
    }
}
