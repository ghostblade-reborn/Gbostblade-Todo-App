DROP TABLE entries;
--> statement-breakpoint
CREATE TABLE projects (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL,
  color TEXT NOT NULL DEFAULT '#808080',
  is_favorite INTEGER NOT NULL DEFAULT 0,
  sort_order INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  metadata TEXT
);
--> statement-breakpoint
CREATE TABLE labels (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL,
  color TEXT NOT NULL DEFAULT '#808080',
  created_at INTEGER NOT NULL,
  metadata TEXT
);
--> statement-breakpoint
CREATE TABLE tasks (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  title TEXT NOT NULL,
  notes TEXT,
  project_id INTEGER REFERENCES projects(id),
  priority INTEGER NOT NULL DEFAULT 1,
  due_date TEXT,
  due_time TEXT,
  recurrence_rule TEXT,
  reminder TEXT,
  status TEXT NOT NULL DEFAULT 'active',
  completed_at INTEGER,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  parent_id INTEGER,
  sort_order INTEGER NOT NULL DEFAULT 0,
  metadata TEXT
);
--> statement-breakpoint
CREATE TABLE task_labels (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  task_id INTEGER NOT NULL REFERENCES tasks(id),
  label_id INTEGER NOT NULL REFERENCES labels(id)
);
--> statement-breakpoint
CREATE TABLE completions (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  task_id INTEGER,
  task_title TEXT NOT NULL,
  completed_date TEXT NOT NULL,
  completed_at INTEGER NOT NULL
);
