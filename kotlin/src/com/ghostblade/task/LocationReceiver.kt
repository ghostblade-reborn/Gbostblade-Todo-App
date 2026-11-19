package com.ghostblade.task

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.media.RingtoneManager
import android.os.Build

/** Location reminders ("remind me at this place") using framework proximity
 *  alerts — no Play Services needed. Storage: "gbt_geo" prefs,
 *  id -> title + "\n" + lat + "\n" + lng. One-shot: fires once, then clears. */
class LocationReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val entering = intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false)
        val id = intent.getStringExtra("id") ?: return
        if (!entering) return
        val title = intent.getStringExtra("title") ?: "Task reminder"

        ReminderReceiver.ensureChannel(ctx)
        val open = Intent(ctx, MainActivity::class.java)
        open.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        val tap = PendingIntent.getActivity(ctx, ("geo_" + id).hashCode(), open, flags)

        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(ctx, ReminderReceiver.CHANNEL_ID) else Notification.Builder(ctx)
        b.setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("📍 You're here — GhostBlade Task")
            .setContentText(title)
            .setStyle(Notification.BigTextStyle().bigText(title))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .setVibrate(longArrayOf(0, 220, 120, 220))
            .setPriority(Notification.PRIORITY_HIGH)

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(("geo_" + id).hashCode(), b.build())

        disarm(ctx, id) // one-shot
    }

    companion object {
        const val PREFS = "gbt_geo"
        private const val RADIUS_M = 150f
        private const val SEP = "\n"

        fun arm(ctx: Context, id: String, title: String, lat: Double, lng: Double) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(id, title + SEP + lat + SEP + lng).apply()
            armNative(ctx, id, title, lat, lng)
        }

        fun disarm(ctx: Context, id: String) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(id).apply()
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (lm != null) {
                try { lm.removeProximityAlert(pendingIntent(ctx, id, "")) } catch (ignored: Exception) {}
            }
        }

        /** Re-arm every stored location reminder (boot / app start safety net). */
        fun rearmAll(ctx: Context) {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            for ((key, v) in prefs.all) {
                if (v !is String) continue
                val parts = v.split(SEP)
                if (parts.size != 3) continue
                try {
                    armNative(ctx, key, parts[0], parts[1].toDouble(), parts[2].toDouble())
                } catch (ignored: Exception) {}
            }
        }

        private fun armNative(ctx: Context, id: String, title: String, lat: Double, lng: Double) {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
            try {
                lm.addProximityAlert(lat, lng, RADIUS_M, -1, pendingIntent(ctx, id, title))
            } catch (ignored: SecurityException) { /* permission not granted yet */ }
        }

        private fun pendingIntent(ctx: Context, id: String, title: String): PendingIntent {
            val i = Intent(ctx, LocationReceiver::class.java)
            i.putExtra("id", id)
            i.putExtra("title", title)
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
            return PendingIntent.getBroadcast(ctx, ("geo_" + id).hashCode(), i, flags)
        }
    }
}
