# 📱 GhostBlade Task — Android App

The installable Android edition of GhostBlade Task.

| | |
|---|---|
| **App name** | GhostBlade Task |
| **Package** | `com.ghostblade.task` |
| **Version** | 1.2 (versionCode 3) |
| **File** | `GhostBladeTask.apk` (71 KB) |
| **Requires** | Android 7.0 (API 24) or newer |
| **SHA-256** | `87a8f83689bf464ae6f4dfe52a950c49c72dd21984fb0809d1674b23ef2a34cd` |

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
- **iOS Liquid Glass UI** — clear/tinted glass styles, 6 accent colors,
  changeable wallpapers (incl. your own photo), spring animations, and a
  distinct sound effect for every action
- Focus timer, board view, smart day planner, morning brief, search,
  labels, undo delete
- **Home-screen widget** with today's tasks
- Data stored **on your device** — works fully offline, no internet
  permission; sync between devices via a sync file you keep in any cloud
- **Real background reminders** with a Snooze 1h action: notifications
  fire at the set time even when the app is closed, and are re-armed
  automatically after a reboot (AlarmManager + boot receiver — the same
  pattern Todoist uses)
- **Location reminders**: "remind me at this place" (framework proximity
  alerts, no Play Services)
- Your 4 daily Must tasks are preloaded on first launch

## Source

The Android source lives in [`android/`](../android) — a native shell
(WebView + reminder bridge) hosting the app from `android/assets/index.html`.
Build it with `android/build.sh` (manual aapt2 → javac → d8 pipeline,
no Gradle required).
