# 📱 GhostBlade Task — Android App

The installable Android edition of GhostBlade Task — now **100% Kotlin**.

| | |
|---|---|
| **App name** | GhostBlade Task |
| **Package** | `com.ghostblade.task` |
| **Version** | 2.1-kotlin (versionCode 5) |
| **File** | `GhostBladeTask-Kotlin.apk` (726 KB) |
| **Requires** | Android 7.0 (API 24) or newer |
| **SHA-256** | `374beb1cddeae4157db2b7499daf239cf579d2b7febdd894e766efb55a325928` |

## Download

Get the APK from the [**Releases**](../../releases) section of this repo.

## Install

1. Download `GhostBladeTask-Kotlin.apk` to your phone.
2. Open the file and tap **Install**. If Android asks, allow
   "Install unknown apps" for your browser/file manager — this is normal
   for apps installed outside the Play Store.
3. Open **GhostBlade Task** from your app drawer.

**Coming from the older JS edition (v1.x)?** Your data moves in two taps:
in the old app, Browse → Backups & Sync → **Export sync file**; then in the
Kotlin app, Browse → Backups & Sync → **Import sync file**. Tasks, projects,
labels, filters, history and backups all carry over — the file format is
identical.

## What you get

- **100% Kotlin** — every line of code is native Kotlin: the UI is built
  entirely in code (no WebView, no HTML/JS), sounds are synthesized with
  AudioTrack, the focus ring is drawn on Canvas
- All core + Pro features (300 projects, filters, calendar view, durations,
  subtasks, Assist, Ramble, project insights, full history, auto backups)
- **iOS Liquid Glass look** — clear/tinted glass styles, 6 accent colors,
  changeable wallpapers (incl. your own photo), spring animations, and a
  distinct sound effect for every action
- Focus timer, board view, offline Smart Plan day planner, morning brief,
  search, labels, undo delete
- **Home-screen widget** with today's tasks
- Data stored **on your device** — works fully offline, **no internet
  permission at all**; sync between devices via a sync file you control
- **Real background reminders** with a Snooze 1h action: notifications
  fire at the set time even when the app is closed, and are re-armed
  automatically after a reboot (AlarmManager + boot receiver — the same
  pattern Todoist uses)
- **Location reminders**: "remind me at this place" (framework proximity
  alerts, no Play Services)
- Your 4 daily Must tasks are preloaded on first launch

## Source

The Kotlin source lives in [`kotlin/`](../kotlin) — 15 `.kt` files plus
`build.sh` (aapt2 → javac R → kotlinc → d8 pipeline, no Gradle required).
The previous JavaScript edition remains under [`android/`](../android)
for history.
