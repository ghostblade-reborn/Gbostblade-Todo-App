# 📱 GhostBlade Task — Android App

The installable Android edition of GhostBlade Task.

| | |
|---|---|
| **App name** | GhostBlade Task |
| **Package** | `com.ghostblade.task` |
| **Version** | 1.0 (versionCode 1) |
| **File** | `GhostBladeTask.apk` (34 KB) |
| **Requires** | Android 7.0 (API 24) or newer |
| **SHA-256** | `313e7b6c3aab89ce292c394d5e596fb56c365a8a693b76941a84501d3e592bf6` |

## Download

Get the APK from the [**Releases**](../../releases) section of this repo —
look for **GhostBlade Task v1.0**.

## Install

1. Download `GhostBladeTask.apk` to your phone.
2. Open the file and tap **Install**. If Android asks, allow
   "Install unknown apps" for your browser/file manager — this is normal
   for apps installed outside the Play Store.
3. Open **GhostBlade Task** from your app drawer.

## What you get

- All core + Pro features from the web version (filters, calendar,
  durations, subtasks, Assist, Ramble, project insights, history, backups)
- Data stored **on your device** — works fully offline
- **Real background reminders**: notifications fire at the set time even
  when the app is closed, and are re-armed automatically after a reboot
  (AlarmManager + boot receiver — the same pattern Todoist uses)
- Your 4 daily Must tasks are preloaded on first launch

## Source

The Android source lives in [`android/`](../android) — a native shell
(WebView + reminder bridge) hosting the app from `android/assets/index.html`.
Build it with `android/build.sh` (manual aapt2 → javac → d8 pipeline,
no Gradle required).
