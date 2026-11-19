# Data Plan

## Context provenance
- `10 minutes Deep Breath 🫁 Meditation Must`, `10 minutes Workout Must`, `Writing 1 YouTube Script Must`, `Make at least One Ai Video` — all daily recurring, due Today (user's own tasks, from his Todoist screenshot in this conversation; explicitly handed off in the build brief as preload data)
- `Build it and make all code flexible to later changes. Cz we gonna do add more and more features day by day` (verbatim user request; drives modular architecture requirement)
- Todoist core scope — Inbox/Today/Upcoming/Browse, Quick Add NLP, projects/labels, priorities, recurrence, Productivity/Karma stats (assistant proposal from REA analysis of com.todoist v11474, explicitly accepted by the user with 'Build it')

## Pro features (added 2026-11-20, from user's Todoist Beginner-vs-Pro screenshot, all unlocked)
- 300 projects / 150 filter views: enforced caps in addProject/addFilter; filters table + lib/filters.ts query engine (today, overdue, p1, #Project, @label, "no date", & / AND); 4 starter filters seeded once.
- Calendar views: getCalendar action + CalendarView (month grid + week toggle, day tap → tasks, add-on-date).
- Custom reminders: reminders table (own records per task, offsetMinutes + absoluteAt, dismiss/snooze); getDueReminders computes fire time; ReminderBanner polls every 30s — UI states honestly that reminders fire while the app is open (no push/background delivery in a web artifact).
- Task durations: tasks.duration_minutes; editor presets + custom, Quick Add parsing ("for 30m", "30 min", "1h") in both NLP mirrors; Today header + stats show total planned time.
- Auto backups: backups table, daily rolling auto snapshot (maybeAutoBackup on add + triggerAutoBackup on open, keep last 10) + manual create, Export JSON download, Import/Restore (file upload or saved backup) in BackupsView.
- Full reporting history: activities table (created/completed, backfilled from completions/tasks on first History open) + getHistory (search, project, date range, 30-day chart, best day, all-time totals).
- Unlimited Ramble: RambleView with Web Speech API (SpeechRecognition) + typed fallback (one task per line/sentence), parsed via existing NLP, bulkAddTasks; no session limits.
- Task & Email Assist: lib/assist.ts rule-based extraction (assistExtract) + keyword-template breakdown (assistBreakdown: video/script/project/study/clean/buy/email/workout + generic); subtasks are real tasks via parent_id (listSubtasks/addSubtasks, counts on TaskRow, checkable in editor).
- Project insights: getProjectInsights (progress, 4-week trend, overdue, best weekday, open duration) + InsightsView.

## Tested sources
No external APIs or web sources are used. All data is user-owned, stored in the artifact's own database (ctx.db). No runtime fetch from external hosts. Ramble uses the browser's built-in Web Speech API only when available.

Imagery not needed: this is a task-management tool matching the user's dark Todoist screenshot; UI is typographic/iconographic (Lucide-style inline SVG via CSS/emoji-free icons), no photographic subjects.

## Long-term data behavior
- **Refresh policy**: No scheduled refresh; data changes only through user actions in the UI. No cron.
- **Growth**: Tasks, projects, labels, and completions accumulate in DB; lists are queried per view (inbox/today/upcoming/browse/search) with server-side filtering.
- **Ordering**: Tasks ordered by due date/time then priority then creation; completed history newest first.
- **Time semantics**: Due dates stored as local date string (YYYY-MM-DD, Asia/Dhaka user context rendered viewer-local) plus optional time string; recurrence stored as a rule string (e.g. daily, weekly:fri, every 2 days) so next-occurrence computation is deterministic. Seeded Must tasks use today's date at seed time (post-submit action computes today at runtime, never a hard-coded date).
- **Seeding**: The 4 Must tasks are seeded once via a post-submit `seedStarterTasks` action that is idempotent (skips if any tasks already exist), so it never overwrites user edits on later opens.
