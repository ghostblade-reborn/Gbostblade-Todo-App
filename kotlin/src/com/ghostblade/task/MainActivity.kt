package com.ghostblade.task

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Looper
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

/**
 * PHASE 1 checkpoint: the native shell in Kotlin. The WebView UI is replaced
 * by a fully native Kotlin UI in Phase 2 — after that, no HTML/JS remains.
 */
class MainActivity : Activity() {

    private lateinit var web: WebView

    /** Bridge exposed to the app UI as window.AndroidBridge. */
    inner class Bridge {
        @JavascriptInterface
        fun scheduleReminder(id: String, title: String, triggerAtMillis: Double) {
            AlarmHelper.schedule(this@MainActivity, id, title, triggerAtMillis.toLong())
        }

        @JavascriptInterface
        fun cancelReminder(id: String) {
            AlarmHelper.cancel(this@MainActivity, id)
        }

        @JavascriptInterface
        fun isNative(): Boolean = true

        /** Home-screen widget data mirror (JSON from the UI). */
        @JavascriptInterface
        fun setWidgetData(json: String) {
            getSharedPreferences(TaskWidget.PREFS, MODE_PRIVATE).edit().putString("data", json).apply()
            TaskWidget.updateAll(this@MainActivity)
        }

        /** Save a text file (sync export) into the public Downloads folder. */
        @JavascriptInterface
        fun writeDownload(name: String, text: String) {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    val cv = ContentValues()
                    cv.put(MediaStore.Downloads.DISPLAY_NAME, name)
                    cv.put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                        ?: throw Exception("insert failed")
                    val os = contentResolver.openOutputStream(uri) ?: throw Exception("open failed")
                    os.write(text.toByteArray(Charsets.UTF_8))
                    os.close()
                } else {
                    val f = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name
                    )
                    val os = FileOutputStream(f)
                    os.write(text.toByteArray(Charsets.UTF_8))
                    os.close()
                }
                toastUi("Saved to Downloads: $name")
            } catch (e: Exception) {
                toastUi("Could not save file")
            }
        }

        /** Capture the current location once and hand it back to the UI. */
        @JavascriptInterface
        fun captureLocation() {
            if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
            ) {
                runOnUiThread {
                    requestPermissions(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ), 77
                    )
                }
                toastUi("Allow location permission, then tap the button again")
                return
            }
            val lm = getSystemService(LOCATION_SERVICE) as? LocationManager
            if (lm == null) { jsLocFail(); return }
            try {
                var best: Location? = null
                for (p in lm.getProviders(true)) {
                    val l = lm.getLastKnownLocation(p)
                    if (l != null && (best == null || l.time > best.time)) best = l
                }
                val b = best
                if (b != null && System.currentTimeMillis() - b.time < 10 * 60 * 1000) {
                    jsLoc(b.latitude, b.longitude)
                    return
                }
                val listener = object : LocationListener {
                    override fun onLocationChanged(l: Location) = jsLoc(l.latitude, l.longitude)
                    override fun onStatusChanged(p: String?, s: Int, bundle: Bundle?) {}
                    override fun onProviderEnabled(p: String) {}
                    override fun onProviderDisabled(p: String) {}
                }
                var any = false
                try { lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, listener, Looper.getMainLooper()); any = true } catch (ignored: Exception) {}
                try { lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper()); any = true } catch (ignored: Exception) {}
                if (!any) jsLocFail()
            } catch (e: SecurityException) {
                jsLocFail()
            }
        }

        @JavascriptInterface
        fun setLocationReminder(id: String, title: String, lat: Double, lng: Double) {
            LocationReceiver.arm(this@MainActivity, id, title, lat, lng)
        }

        @JavascriptInterface
        fun clearLocationReminder(id: String) {
            LocationReceiver.disarm(this@MainActivity, id)
        }
    }

    private fun toastUi(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

    private fun jsLoc(lat: Double, lng: Double) {
        if (::web.isInitialized) web.post {
            web.evaluateJavascript("window.__gbtLocResult && window.__gbtLocResult($lat,$lng)", null)
        }
    }

    private fun jsLocFail() {
        if (::web.isInitialized) web.post {
            web.evaluateJavascript("window.__gbtLocFail && window.__gbtLocFail()", null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ReminderReceiver.ensureChannel(this)

        if (Build.VERSION.SDK_INT >= 33
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 42)
        }

        window.statusBarColor = 0xFF06060A.toInt()
        window.navigationBarColor = 0xFF06060A.toInt()

        web = WebView(this)
        web.setBackgroundColor(0xFF06060A.toInt())
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        web.webViewClient = WebViewClient()
        web.addJavascriptInterface(Bridge(), "AndroidBridge")
        web.loadUrl("file:///android_asset/index.html")
        setContentView(web)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("window.__gbtBack && window.__gbtBack()", null)
    }
}
