ALTER TABLE tasks ADD COLUMN duration_minutes INTEGER;
--> statement-breakpoint
CREATE TABLE filters (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL,
  query TEXT NOT NULL,
  color TEXT NOT NULL DEFAULT '#E44332',
  is_favorite INTEGER NOT NULL DEFAULT 0,
  sort_order INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL
);
--> statement-breakpoint
CREATE TABLE reminders (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  task_id INTEGER NOT NULL REFERENCES tasks(id),
  offset_minutes INTEGER NOT NULL DEFAULT 0,
  label TEXT NOT NULL DEFAULT 'At time',
  absolute_at TEXT,
  dismissed_at INTEGER,
  snoozed_until INTEGER,
  created_at INTEGER NOT NULL
);
--> statement-breakpoint
CREATE TABLE backups (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  kind TEXT NOT NULL DEFAULT 'auto',
  data TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
--> statement-breakpoint
CREATE TABLE activities (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  type TEXT NOT NULL,
  task_id INTEGER,
  task_title TEXT NOT NULL,
  project_id INTEGER,
  project_name TEXT,
  event_date TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
