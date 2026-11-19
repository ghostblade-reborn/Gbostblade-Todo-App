package com.ghostblade.task;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.LocationManager;
import android.media.RingtoneManager;
import android.os.Build;

import java.util.Map;

/** Location reminders ("remind me at this place") using framework proximity
 *  alerts — no Play Services needed. Storage: "gbt_geo" prefs,
 *  id -> title + "\n" + lat + "\n" + lng. One-shot: fires once, then clears. */
public class LocationReceiver extends BroadcastReceiver {

    public static final String PREFS = "gbt_geo";
    private static final float RADIUS_M = 150f;
    private static final String SEP = "\n";

    public static void arm(Context ctx, String id, String title, double lat, double lng) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(id, title + SEP + lat + SEP + lng).apply();
        armNative(ctx, id, title, lat, lng);
    }

    public static void disarm(Context ctx, String id) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(id).apply();
        LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm != null) {
            try { lm.removeProximityAlert(pendingIntent(ctx, id, "")); } catch (Exception ignored) {}
        }
    }

    /** Re-arm every stored location reminder (boot / app start safety net). */
    public static void rearmAll(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            if (!(e.getValue() instanceof String)) continue;
            String[] parts = ((String) e.getValue()).split(SEP);
            if (parts.length != 3) continue;
            try {
                armNative(ctx, e.getKey(), parts[0],
                        Double.parseDouble(parts[1]), Double.parseDouble(parts[2]));
            } catch (Exception ignored) {}
        }
    }

    private static void armNative(Context ctx, String id, String title, double lat, double lng) {
        LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;
        try { lm.addProximityAlert(lat, lng, RADIUS_M, -1, pendingIntent(ctx, id, title)); }
        catch (SecurityException ignored) { /* permission not granted yet */ }
    }

    private static PendingIntent pendingIntent(Context ctx, String id, String title) {
        Intent i = new Intent(ctx, LocationReceiver.class);
        i.putExtra("id", id);
        i.putExtra("title", title);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, ("geo_" + id).hashCode(), i, flags);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        boolean entering = intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false);
        String id = intent.getStringExtra("id");
        String title = intent.getStringExtra("title");
        if (!entering || id == null) return;
        if (title == null) title = "Task reminder";

        ReminderReceiver.ensureChannel(ctx);
        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent tap = PendingIntent.getActivity(ctx, ("geo_" + id).hashCode(), open, flags);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(ctx, ReminderReceiver.CHANNEL_ID)
                : new Notification.Builder(ctx);
        b.setSmallIcon(android.R.drawable.ic_dialog_map)
         .setContentTitle("📍 You're here — GhostBlade Task")
         .setContentText(title)
         .setStyle(new Notification.BigTextStyle().bigText(title))
         .setContentIntent(tap)
         .setAutoCancel(true)
         .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
         .setVibrate(new long[]{0, 220, 120, 220})
         .setPriority(Notification.PRIORITY_HIGH);

        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(("geo_" + id).hashCode(), b.build());

        disarm(ctx, id); // one-shot
    }
}
