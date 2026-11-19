package com.ghostblade.task;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Looper;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {

    private WebView web;

    /** Bridge exposed to the app UI as window.AndroidBridge. */
    public class Bridge {
        @JavascriptInterface
        public void scheduleReminder(String id, String title, double triggerAtMillis) {
            AlarmHelper.schedule(MainActivity.this, id, title, (long) triggerAtMillis);
        }

        @JavascriptInterface
        public void cancelReminder(String id) {
            AlarmHelper.cancel(MainActivity.this, id);
        }

        @JavascriptInterface
        public boolean isNative() { return true; }

        /** Home-screen widget data mirror (JSON from the UI). */
        @JavascriptInterface
        public void setWidgetData(String json) {
            getSharedPreferences(TaskWidget.PREFS, MODE_PRIVATE).edit().putString("data", json).apply();
            TaskWidget.updateAll(MainActivity.this);
        }

        /** Save a text file (sync export) into the public Downloads folder. */
        @JavascriptInterface
        public void writeDownload(String name, String text) {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    cv.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                    cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (uri == null) throw new Exception("insert failed");
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    os.write(text.getBytes("UTF-8"));
                    os.close();
                } else {
                    File f = new File(Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS), name);
                    FileOutputStream os = new FileOutputStream(f);
                    os.write(text.getBytes("UTF-8"));
                    os.close();
                }
                toastUi("Saved to Downloads: " + name);
            } catch (Exception e) {
                toastUi("Could not save file");
            }
        }

        /** Capture the current location once and hand it back to the UI. */
        @JavascriptInterface
        public void captureLocation() {
            if (Build.VERSION.SDK_INT >= 23
                    && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(() -> requestPermissions(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, 77));
                toastUi("Allow location permission, then tap the button again");
                return;
            }
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (lm == null) { jsLocFail(); return; }
            try {
                Location best = null;
                for (String p : lm.getProviders(true)) {
                    Location l = lm.getLastKnownLocation(p);
                    if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                }
                if (best != null && System.currentTimeMillis() - best.getTime() < 10 * 60 * 1000) {
                    jsLoc(best.getLatitude(), best.getLongitude());
                    return;
                }
                LocationListener listener = new LocationListener() {
                    @Override public void onLocationChanged(Location l) { jsLoc(l.getLatitude(), l.getLongitude()); }
                    @Override public void onStatusChanged(String p, int s, Bundle b) {}
                    @Override public void onProviderEnabled(String p) {}
                    @Override public void onProviderDisabled(String p) {}
                };
                boolean any = false;
                try { lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, listener, Looper.getMainLooper()); any = true; } catch (Exception ignored) {}
                try { lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper()); any = true; } catch (Exception ignored) {}
                if (!any) jsLocFail();
            } catch (SecurityException e) {
                jsLocFail();
            }
        }

        @JavascriptInterface
        public void setLocationReminder(String id, String title, double lat, double lng) {
            LocationReceiver.arm(MainActivity.this, id, title, lat, lng);
        }

        @JavascriptInterface
        public void clearLocationReminder(String id) {
            LocationReceiver.disarm(MainActivity.this, id);
        }
    }

    private void toastUi(final String msg) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
    }

    private void jsLoc(final double lat, final double lng) {
        if (web != null) web.post(() -> web.evaluateJavascript(
                "window.__gbtLocResult && window.__gbtLocResult(" + lat + "," + lng + ")", null));
    }

    private void jsLocFail() {
        if (web != null) web.post(() -> web.evaluateJavascript(
                "window.__gbtLocFail && window.__gbtLocFail()", null));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ReminderReceiver.ensureChannel(this);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 42);
        }

        getWindow().setStatusBarColor(0xFF06060A);
        getWindow().setNavigationBarColor(0xFF06060A);

        web = new WebView(this);
        web.setBackgroundColor(0xFF06060A);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.__gbtBack && window.__gbtBack()", null);
    }
}
