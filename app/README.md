# 📱 GhostBlade Task — Android App

The installable Android edition of GhostBlade Task — now **100% Kotlin**.

| | |
|---|---|
| **App name** | GhostBlade Task |
| **Package** | `com.ghostblade.task` |
| **Version** | 3.1-kotlin (versionCode 15) |
| **File** | `GhostBlade Task v4.apk` (751 KB) |
| **Requires** | Android 7.0 (API 24) or newer |
| **SHA-256** | `0c7b0e6d610ffa825ca8aa1858a489b4ec8fb2534dbf48c989ffa6a4bd1659d3` |

## Download

Get the APK from the [**Releases**](../../releases) section of this repo.

## Install

1. Download `GhostBlade Task v4.apk` to your phone.
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
- **Todoist-style Quick Add** — natural-language parsing ("tomorrow at 7am
  every day #Health p2"), date/priority/reminder/project pickers, inline
  `#project` and `!!priority` suggestions
- **Performance database (SQLite)** — done vs missed history with 7-day,
  30-day, 2-month and 1-year views, completion rate, streak rate, current
  and best streaks
- **iOS Liquid Glass look** — real backdrop frost blur (double-pass +
  saturation), soft rounded shadows, clear/tinted glass styles, 6 accent
  colors, changeable wallpapers (incl. your own photo), spring animations,
  iOS-style minimal sound effects
- **Three themes** — Aurora (colour wallpapers), Midnight Black and Frost
  White (Apple Music-style), plus a Red text option for detail lines
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

The Kotlin source lives in [`kotlin/`](../kotlin) — 16 `.kt` files, the
`res/` XML resources, a runnable logic test suite (`kotlin/tests/`), plus
`build.sh` (aapt2 → javac R → kotlinc → d8 pipeline, no Gradle required).
The previous JavaScript edition remains under [`android/`](../android)
for history.
