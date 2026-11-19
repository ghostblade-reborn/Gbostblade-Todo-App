package com.ghostblade.task;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

/** Home-screen widget: today's tasks, mirrored from the app UI through the
 *  bridge into SharedPreferences ("gbt_widget" / "data" JSON). */
public class TaskWidget extends AppWidgetProvider {

    public static final String PREFS = "gbt_widget";

    public static void updateAll(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        if (mgr == null) return;
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, TaskWidget.class));
        for (int id : ids) updateOne(ctx, mgr, id);
    }

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) updateOne(ctx, mgr, id);
    }

    private static void updateOne(Context ctx, AppWidgetManager mgr, int widgetId) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget);
        String raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("data", "");
        String[] lines = new String[5];
        int open = 0, done = 0;
        try {
            JSONObject o = new JSONObject(raw);
            open = o.optInt("open");
            done = o.optInt("done");
            JSONArray a = o.optJSONArray("lines");
            if (a != null) for (int i = 0; i < Math.min(5, a.length()); i++) lines[i] = a.optString(i);
        } catch (Exception ignored) { /* empty state below */ }

        rv.setTextViewText(R.id.wCount, open + " to do · " + done + " done today");
        int[] tv = {R.id.wL1, R.id.wL2, R.id.wL3, R.id.wL4, R.id.wL5};
        boolean any = false;
        for (int i = 0; i < tv.length; i++) {
            if (lines[i] == null || lines[i].isEmpty()) {
                rv.setViewVisibility(tv[i], View.GONE);
            } else {
                any = true;
                rv.setViewVisibility(tv[i], View.VISIBLE);
                rv.setTextViewText(tv[i], lines[i]);
            }
        }
        rv.setViewVisibility(R.id.wEmpty, any ? View.GONE : View.VISIBLE);

        Intent openApp = new Intent(ctx, MainActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        rv.setOnClickPendingIntent(R.id.wRoot, PendingIntent.getActivity(ctx, 0, openApp, flags));
        mgr.updateAppWidget(widgetId, rv);
    }
}
