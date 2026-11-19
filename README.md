# GhostBlade Task

A Todoist-style task manager — dark theme, red accent — built with a modular architecture so new features can be added day by day without rewrites.

## 📱 Android app

GhostBlade Task is also an installable Android app (`GhostBladeTask.apk`, Android 7+) with real background reminders that fire even when the app is closed. Download it from the [**Releases**](../../releases) section — details, install steps and checksum are in [`app/`](app/). Android source: [`android/`](android/).

## Features

- Inbox / Today / Upcoming / Browse views
- Quick Add with natural-language parsing ("tomorrow at 4pm", "every friday", "#project", "@label", "p1", "for 30m")
- Projects, labels, priorities, recurring tasks that roll to their next date when completed
- Productivity stats: progress ring, day streak, weekly chart

### Pro features (all unlocked)

- 300 active projects, 150 saved filter views (query syntax: `today & p1`, `#Project`, `@label`, `no date`)
- Calendar view (month / week)
- Custom per-task reminders (in-app banner while the app is open)
- Task durations, with total planned time for Today
- Auto backups (rolling snapshots) plus JSON export / import
- Full reporting history with 30-day chart and all-time totals
- Ramble: voice capture that turns speech into parsed tasks (typed fallback included)
- Task & Email Assist: paste text/email to extract tasks, and "Break it down" subtask generation
- Project insights: progress, 4-week trend, overdue, most productive day

## Structure

- `client/src/` — UI: one component per view (`App.tsx`, `components/`), shared logic in `lib/` (`nlp.ts` natural-language parser, `dates.ts`)
- `server/src/` — one small action per capability (`actions.ts`), domain logic in `lib/` (`recurrence.ts`, `filters.ts`, `assist.ts`, `nlp.ts`, `dates.ts`), data model in `schema.ts`
- `drizzle/` — database migrations (`0002` core tables, `0004` Pro features)

The task record is deliberately extensible (duration, reminders, subtasks, metadata) so future features plug in as new modules and actions.
