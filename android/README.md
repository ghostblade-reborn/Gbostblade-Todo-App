# GhostBlade Task — Android app

Native Android edition of GhostBlade Task (package `com.ghostblade.task`).
A thin native shell (WebView) hosts the full app UI from `assets/index.html`
(all core + Pro features, data stored on-device), while native code provides
what a web app cannot: **real background reminders**.

- `MainActivity` — WebView + `AndroidBridge` (schedule/cancel reminders)
- `AlarmHelper` — AlarmManager scheduling, persisted so reminders survive reboot
- `ReminderReceiver` — posts the notification when a reminder fires
- `BootReceiver` — re-arms reminders after reboot / time / timezone changes
  (same pattern REA found inside Todoist: BOOT_COMPLETED / TIME_SET / TIMEZONE_CHANGED)

## Build

No Gradle — `build.sh` runs the manual pipeline (aapt2 → javac → d8 →
zipalign → apksigner) against a local Android SDK (platform android-34,
build-tools 35) and a Temurin JDK 21:

```bash
bash build.sh   # produces GhostBladeTask.apk
```

Not in git: the APK itself, `build/` outputs, launcher PNGs (regenerable),
and `debug.keystore` (local signing key — never commit a keystore).
