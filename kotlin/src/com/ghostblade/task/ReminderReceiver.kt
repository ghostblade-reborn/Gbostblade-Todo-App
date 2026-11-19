package com.ghostblade.task

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra("id")
        val title = intent.getStringExtra("title") ?: "Task reminder"

        /* Snooze button on a fired reminder: re-arm for +1 hour, dismiss. */
        if (ACTION_SNOOZE == intent.action) {
            if (id != null) {
                AlarmHelper.schedule(ctx, id, title, System.currentTimeMillis() + 3600000L)
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.cancel(id.hashCode())
            }
            return
        }
        ensureChannel(ctx)

        val open = Intent(ctx, MainActivity::class.java)
        open.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        val tap = PendingIntent.getActivity(ctx, id?.hashCode() ?: 0, open, flags)

        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(ctx, CHANNEL_ID) else Notification.Builder(ctx)
        b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("GhostBlade Task")
            .setContentText(title)
            .setStyle(Notification.BigTextStyle().bigText(title))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .setVibrate(longArrayOf(0, 220, 120, 220))
            .setDefaults(Notification.DEFAULT_LIGHTS)
            .setPriority(Notification.PRIORITY_HIGH)

        /* Snooze 1h action */
        if (id != null) {
            val sz = Intent(ctx, ReminderReceiver::class.java)
            sz.action = ACTION_SNOOZE
            sz.putExtra("id", id)
            sz.putExtra("title", title)
            var sflags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) sflags = sflags or PendingIntent.FLAG_IMMUTABLE
            val szPi = PendingIntent.getBroadcast(ctx, id.hashCode() + 7919, sz, sflags)
            b.addAction(android.R.drawable.ic_menu_recent_history, "Snooze 1h", szPi)
        }

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(id?.hashCode() ?: System.currentTimeMillis().toInt(), b.build())

        // One-shot: remove from the store so BootReceiver does not re-arm it.
        if (id != null) {
            ctx.getSharedPreferences(AlarmHelper.PREFS, Context.MODE_PRIVATE).edit().remove(id).apply()
        }
    }

    companion object {
        const val CHANNEL_ID = "gbt_reminders_v2"
        const val ACTION_SNOOZE = "com.ghostblade.task.SNOOZE"

        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                    val ch = NotificationChannel(
                        CHANNEL_ID, "Task reminders", NotificationManager.IMPORTANCE_HIGH
                    )
                    ch.description = "GhostBlade Task reminders"
                    val snd = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    val aa = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    ch.setSound(snd, aa)
                    ch.enableVibration(true)
                    ch.vibrationPattern = longArrayOf(0, 220, 120, 220)
                    nm.createNotificationChannel(ch)
                }
            }
        }
    }
}
