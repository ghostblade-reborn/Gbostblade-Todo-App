// Drizzle schema for the Task Manager.
//
// Domain model (kept extensible on purpose — new features should arrive as
// new columns/tables/actions, not rewrites):
//   projects    — named buckets tasks belong to (Inbox = project_id NULL)
//   labels      — free-form tags, many-to-many with tasks via task_labels
//   tasks       — the core record. `parent_id` reserves subtasks,
//                 `reminder` reserves reminders, `metadata` is a JSON
//                 escape hatch for future fields (comments, collaborators…)
//   completions — one row per completion event; powers streaks and the
//                 weekly Productivity view without touching task rows.

import { integer, sqliteTable, text } from "drizzle-orm/sqlite-core";

const createdAt = () =>
  integer("created_at", { mode: "timestamp_ms" })
    .notNull()
    .$defaultFn(() => new Date());

export const projects = sqliteTable("projects", {
  id: integer("id").primaryKey({ autoIncrement: true }),
  name: text("name").notNull(),
  color: text("color").notNull().default("#808080"),
  isFavorite: integer("is_favorite", { mode: "boolean" })
    .notNull()
    .default(false),
  sortOrder: integer("sort_order").notNull().default(0),
  createdAt: createdAt(),
  metadata: text("metadata"),
});

export const labels = sqliteTable("labels", {
  id: integer("id").primaryKey({ autoIncrement: true }),
  name: text("name").notNull(),
  color: text("color").notNull().default("#808080"),
  createdAt: createdAt(),
  metadata: text("metadata"),
});

export const tasks = sqliteTable("tasks", {
  id: integer("id").primaryKey({ autoIncrement: true }),
  title: text("title").notNull(),
  notes: text("notes"),
  projectId: integer("project_id").references(() => projects.id),
  /** 1 = normal … 4 = highest (Todoist P1). */
  priority: integer("priority").notNull().default(1),
  /** Local calendar date, YYYY-MM-DD. Null = no due date (Inbox-style). */
  dueDate: text("due_date"),
  /** Local wall-clock time, HH:MM (24h). Null = date only. */
  dueTime: text("due_time"),
  /** Recurrence rule id: daily | weekdays | weekly | weekly:<0-6> | monthly | interval:<n>. */
  recurrenceRule: text("recurrence_rule"),
  /** Legacy free-form reminder spec — kept for compat; real reminders live in `reminders`. */
  reminder: text("reminder"),
  /** Pro: task duration in minutes (15, 30, 60…). Null = no estimate. */
  durationMinutes: integer("duration_minutes"),
  status: text("status", { enum: ["active", "completed"] })
    .notNull()
    .default("active"),
  completedAt: integer("completed_at", { mode: "timestamp_ms" }),
  createdAt: createdAt(),
  updatedAt: integer("updated_at", { mode: "timestamp_ms" })
    .notNull()
    .$defaultFn(() => new Date()),
  /** Subtasks: id of the parent task. Null = top-level task. */
  parentId: integer("parent_id"),
  sortOrder: integer("sort_order").notNull().default(0),
  /** JSON blob for future fields without a migration-blocking redesign. */
  metadata: text("metadata"),
});

/** Pro: saved filter views (up to 150). Query uses simple syntax: today, overdue, p1, #Project, @label, "no date", combined with & / AND. */
export const filters = sqliteTable("filters", {
  id: integer("id").primaryKey({ autoIncrement: true }),
  name: text("name").notNull(),
  query: text("query").notNull(),
  color: text("color").notNull().default("#E44332"),
  isFavorite: integer("is_favorite", { mode: "boolean" }).notNull().default(false),
  sortOrder: integer("sort_order").notNull().default(0),
  createdAt: createdAt(),
});

/** Pro: per-task custom reminders. One row per reminder; a task can have several. */
export const reminders = sqliteTable("reminders", {
  id: integer("id").primaryKey({ autoIncrement: true }),
  taskId: integer("task_id").notNull().references(() => tasks.id),
  /** Minutes before due time. 0 = at due time, -1 = absolute (see absoluteAt). */
  offsetMinutes: integer("offset_minutes").notNull().default(0),
  /** Human label e.g. "At time", "10 min before", "Custom: 45 min before". */
  label: text("label").notNull().default("At time"),
  /** For absolute reminders: ISO datetime string. Null for relative. */
  absoluteAt: text("absolute_at"),
  dismissedAt: integer("dismissed_at", { mode: "timestamp_ms" }),
  snoozedUntil: integer("snoozed_until", { mode: "timestamp_ms" }),
  createdAt: createdAt(),
});

/** Pro: auto backups — rolling snapshots of all app data as JSON. */
export const backups = sqliteTable("backups", {
  id: integer("id").primaryKey({ autoIncrement: true }),
  kind: text("kind", { enum: ["auto", "manual"] }).notNull().default("auto"),
  /** Full JSON snapshot: { tasks, projects, labels, filters, completions, taskLabels, reminders } */
  data: text("data").notNull(),
  createdAt: createdAt(),
});

/** Pro: activity log for full reporting history (creations + completions). */
export const activities = sqliteTable("activities", {
  id: integer("id").primaryKey({ autoIncrement: true }),
  type: text("type", { enum: ["created", "completed"] }).notNull(),
  taskId: integer("task_id"),
  taskTitle: text("task_title").notNull(),
  projectId: integer("project_id"),
  projectName: text("project_name"),
  /** Local date YYYY-MM-DD of the event. */
  eventDate: text("event_date").notNull(),
  createdAt: createdAt(),
});

export const taskLabels = sqliteTable("task_labels", {
  id: integer("id").primaryKey({ autoIncrement: true }),
  taskId: integer("task_id")
    .notNull()
    .references(() => tasks.id),
  labelId: integer("label_id")
    .notNull()
    .references(() => labels.id),
});

export const completions = sqliteTable("completions", {
  id: integer("id").primaryKey({ autoIncrement: true }),
  taskId: integer("task_id"),
  taskTitle: text("task_title").notNull(),
  /** Local calendar date the completion happened, YYYY-MM-DD. */
  completedDate: text("completed_date").notNull(),
  completedAt: integer("completed_at", { mode: "timestamp_ms" })
    .notNull()
    .$defaultFn(() => new Date()),
});

/** Key-value app settings (e.g. the starter-seed flag). */
export const settings = sqliteTable("settings", {
  key: text("key").primaryKey(),
  value: text("value").notNull(),
});
