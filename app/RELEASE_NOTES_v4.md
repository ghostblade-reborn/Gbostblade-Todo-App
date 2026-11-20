# GhostBlade Task v4 — release notes

- **Tag:** `v4` (target `main`)
- **Title:** `GhostBlade Task v4`
- **Asset file name:** `GhostBlade Task v4.apk`
- **Asset SHA-256:** `2caca877868d4cc48306c64608b23c27ad11f6d2dfc9494cbe3b829b83d5bdc1`
- The APK is the **v3.9-kotlin** build (versionCode 23) — the overnight-improved
  edition (batches v3.2–v3.9 on top of v3.1). It is NOT the same bytes as the
  `v3.1-kotlin` release asset. Source: commit `11c796c` on `main`.

## Release description (as published)

GhostBlade Task v4 — the overnight-improved edition, based on v3.9-kotlin (versionCode 23).

What's new since v3.1:
• Quick Add draft resume — a half-typed task survives closing the sheet
• Sheet back-stack — Back steps through Quick Add sub-sheets instead of closing everything, plus an editor discard-changes guard
• Liquid Glass polish — iOS-eased sheet animations, one type scale, unified corners and spacing, Frost White contrast fixes
• Exact-alarm hint — warns in the Reminders picker when reminders may arrive late, with a one-tap path to the system setting
• Reliability fixes — undo restores position, recurring tasks catch up, widget follows theme and never shows yesterday's tasks, reminders stay correct across timezone changes
• Themes: Aurora / Black / White + red text option; SQLite performance stats (7d/30d/2m/1y, streaks)

69 logic tests passing. No internet permission. Installs over previous versions (same signature); your data carries over.

SHA-256: 2caca877868d4cc48306c64608b23c27ad11f6d2dfc9494cbe3b829b83d5bdc1
