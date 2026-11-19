// Actions for the Task Manager — Pro edition.
// Modular: core task actions + separate Pro modules (filters, reminders,
// calendar, backups, history, assist, insights, subtasks) in this file,
// shared logic in server/src/lib/.

import { defineAction, z, type ActionsModule, type Ctx } from "@hatch/space-sdk";
import { asc, desc, eq } from "drizzle-orm";
import * as schema from "./schema";
import { addDays, todayIn } from "./lib/dates";
import { nextOccurrence, recurrenceLabel } from "./lib/recurrence";
import { parseQuickAdd } from "./lib/nlp";
import { matchesFilter, STARTER_FILTERS } from "./lib/filters";
import { extractActionLines, breakdownTask } from "./lib/assist";

const USER_TZ = "Asia/Dhaka";

const labelOut = z.object({ id: z.number(), name: z.string(), color: z.string() });
const reminderOut = z.object({
  id: z.number(), taskId: z.number(), offsetMinutes: z.number(), label: z.string(),
  absoluteAt: z.string().nullable(), dismissedAt: z.string().nullable(), snoozedUntil: z.string().nullable(),
});
const taskOut = z.object({
  id: z.number(), title: z.string(), notes: z.string().nullable(),
  projectId: z.number().nullable(), projectName: z.string().nullable(),
  priority: z.number(), dueDate: z.string().nullable(), dueTime: z.string().nullable(),
  recurrenceRule: z.string().nullable(), recurrenceLabel: z.string().nullable(),
  reminder: z.string().nullable(), durationMinutes: z.number().nullable(),
  status: z.string(), completedAt: z.string().nullable(), createdAt: z.string(),
  parentId: z.number().nullable(), labels: z.array(labelOut),
  subtaskTotal: z.number(), subtaskDone: z.number(),
  reminders: z.array(reminderOut),
});
type TaskOut = z.infer<typeof taskOut>;
const projectOut = z.object({ id: z.number(), name: z.string(), color: z.string(), isFavorite: z.boolean(), activeCount: z.number() });
const filterOut = z.object({ id: z.number(), name: z.string(), query: z.string(), color: z.string(), isFavorite: z.boolean(), count: z.number() });
const okResponse = z.object({ ok: z.literal(true) });
type OkResponse = z.infer<typeof okResponse>;

async function loadTaskRows(ctx: Ctx) {
  const db = ctx.db<typeof schema>();
  const [taskRows, projectRows, labelRows, linkRows, reminderRows] = await Promise.all([
    db.select().from(schema.tasks).orderBy(asc(schema.tasks.id)),
    db.select().from(schema.projects),
    db.select().from(schema.labels),
    db.select().from(schema.taskLabels),
    db.select().from(schema.reminders).orderBy(asc(schema.reminders.id)),
  ]);
  const projectName = new Map(projectRows.map((p) => [p.id, p.name]));
  const labelById = new Map(labelRows.map((l) => [l.id, l]));
  const labelsByTask = new Map<number, z.infer<typeof labelOut>[]>();
  for (const link of linkRows) {
    const label = labelById.get(link.labelId);
    if (!label) continue;
    const list = labelsByTask.get(link.taskId) ?? [];
    list.push({ id: label.id, name: label.name, color: label.color });
    labelsByTask.set(link.taskId, list);
  }
  const remindersByTask = new Map<number, z.infer<typeof reminderOut>[]>();
  for (const r of reminderRows) {
    const list = remindersByTask.get(r.taskId) ?? [];
    list.push({ id: r.id, taskId: r.taskId, offsetMinutes: r.offsetMinutes, label: r.label, absoluteAt: r.absoluteAt, dismissedAt: r.dismissedAt ? r.dismissedAt.toISOString() : null, snoozedUntil: r.snoozedUntil ? r.snoozedUntil.toISOString() : null });
    remindersByTask.set(r.taskId, list);
  }
  // subtask counts
  const subCounts = new Map<number, { total: number; done: number }>();
  for (const t of taskRows) {
    if (t.parentId != null) {
      const c = subCounts.get(t.parentId) ?? { total: 0, done: 0 };
      c.total++;
      if (t.status === "completed") c.done++;
      subCounts.set(t.parentId, c);
    }
  }
  const tasks: TaskOut[] = taskRows.map((t) => ({
    id: t.id, title: t.title, notes: t.notes,
    projectId: t.projectId, projectName: t.projectId != null ? (projectName.get(t.projectId) ?? null) : null,
    priority: t.priority, dueDate: t.dueDate, dueTime: t.dueTime,
    recurrenceRule: t.recurrenceRule, recurrenceLabel: t.recurrenceRule ? recurrenceLabel(t.recurrenceRule) : null,
    reminder: t.reminder, durationMinutes: t.durationMinutes ?? null,
    status: t.status, completedAt: t.completedAt ? t.completedAt.toISOString() : null,
    createdAt: t.createdAt.toISOString(), parentId: t.parentId,
    labels: labelsByTask.get(t.id) ?? [],
    subtaskTotal: subCounts.get(t.id)?.total ?? 0,
    subtaskDone: subCounts.get(t.id)?.done ?? 0,
    reminders: remindersByTask.get(t.id) ?? [],
  }));
  return { tasks, projectRows, labelRows, reminderRows };
}

async function findOrCreateProject(ctx: Ctx, name: string) {
  const db = ctx.db<typeof schema>();
  const existing = await db.select().from(schema.projects).orderBy(asc(schema.projects.id));
  const found = existing.find((p) => p.name.toLowerCase() === name.toLowerCase());
  if (found) return found.id;
  const [row] = await db.insert(schema.projects).values({ name }).returning({ id: schema.projects.id });
  return row?.id ?? null;
}
async function findOrCreateLabel(ctx: Ctx, name: string) {
  const db = ctx.db<typeof schema>();
  const existing = await db.select().from(schema.labels).orderBy(asc(schema.labels.id));
  const found = existing.find((l) => l.name.toLowerCase() === name.toLowerCase());
  if (found) return found.id;
  const [row] = await db.insert(schema.labels).values({ name }).returning({ id: schema.labels.id });
  return row?.id ?? null;
}
async function setTaskLabels(ctx: Ctx, taskId: number, labelIds: number[]) {
  const db = ctx.db<typeof schema>();
  await db.delete(schema.taskLabels).where(eq(schema.taskLabels.taskId, taskId));
  for (const labelId of labelIds) await db.insert(schema.taskLabels).values({ taskId, labelId });
}
async function logActivity(ctx: Ctx, type: "created" | "completed", task: { id: number; title: string; projectId: number | null }, eventDate: string) {
  const db = ctx.db<typeof schema>();
  let projectName: string | null = null;
  if (task.projectId != null) {
    const [p] = await db.select().from(schema.projects).where(eq(schema.projects.id, task.projectId));
    projectName = p?.name ?? null;
  }
  await db.insert(schema.activities).values({ type, taskId: task.id, taskTitle: task.title, projectId: task.projectId, projectName, eventDate });
}
async function maybeAutoBackup(ctx: Ctx) {
  // Rolling auto-backup: at most one auto snapshot per calendar day (Dhaka).
  try {
    const db = ctx.db<typeof schema>();
    const today = todayIn(USER_TZ);
    const existing = await db.select().from(schema.backups).orderBy(desc(schema.backups.id));
    const lastAuto = existing.find((b) => b.kind === "auto");
    if (lastAuto) {
      const lastDate = new Intl.DateTimeFormat("en-CA", { timeZone: USER_TZ, year: "numeric", month: "2-digit", day: "2-digit" }).format(lastAuto.createdAt);
      if (lastDate === today) return;
    }
    const data = await buildSnapshot(ctx);
    await db.insert(schema.backups).values({ kind: "auto", data: JSON.stringify(data) });
    // keep last 10 backups
    const all = await db.select().from(schema.backups).orderBy(desc(schema.backups.id));
    for (const old of all.slice(10)) await db.delete(schema.backups).where(eq(schema.backups.id, old.id));
  } catch { /* backup must never break the main action */ }
}
async function buildSnapshot(ctx: Ctx) {
  const db = ctx.db<typeof schema>();
  const [tasks, projects, labels, filters, completions, taskLabels, reminders, activities] = await Promise.all([
    db.select().from(schema.tasks), db.select().from(schema.projects), db.select().from(schema.labels),
    db.select().from(schema.filters), db.select().from(schema.completions), db.select().from(schema.taskLabels),
    db.select().from(schema.reminders), db.select().from(schema.activities),
  ]);
  const ser = (rows: Record<string, unknown>[]) => rows.map((r) => {
    const o: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(r)) o[k] = v instanceof Date ? v.toISOString() : v;
    return o;
  });
  return { version: 1, exportedAt: new Date().toISOString(), tasks: ser(tasks as never), projects: ser(projects as never), labels: ser(labels as never), filters: ser(filters as never), completions: ser(completions as never), taskLabels: ser(taskLabels as never), reminders: ser(reminders as never), activities: ser(activities as never) };
}

export const Actions = {
  getBootstrap: defineAction({
    description: "Returns projects, labels and filters with active task counts, for Browse and pickers. Supports up to 300 projects and 150 filters (Pro).",
    request: z.object({ today: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional() }),
    response: z.object({
      projects: z.array(projectOut),
      labels: z.array(labelOut.extend({ activeCount: z.number() })),
      filters: z.array(filterOut),
      projectLimit: z.number(), filterLimit: z.number(),
    }),
    async handler(ctx, args) {
      const db = ctx.db<typeof schema>();
      const { tasks, projectRows, labelRows } = await loadTaskRows(ctx);
      const today = args.today ?? todayIn(USER_TZ);
      const topLevel = tasks.filter((t) => t.parentId == null);
      const active = topLevel.filter((t) => t.status === "active");
      // seed starter filters once
      let filterRows = await db.select().from(schema.filters).orderBy(asc(schema.filters.id));
      if (filterRows.length === 0) {
        const [flag] = await db.select().from(schema.settings).where(eq(schema.settings.key, "filters_seeded"));
        if (!flag) {
          for (const f of STARTER_FILTERS) await db.insert(schema.filters).values({ name: f.name, query: f.query, color: f.color });
          await db.insert(schema.settings).values({ key: "filters_seeded", value: "1" });
          filterRows = await db.select().from(schema.filters).orderBy(asc(schema.filters.id));
        }
      }
      return {
        projects: projectRows.map((p) => ({ id: p.id, name: p.name, color: p.color, isFavorite: p.isFavorite, activeCount: active.filter((t) => t.projectId === p.id).length })),
        labels: labelRows.map((l) => ({ id: l.id, name: l.name, color: l.color, activeCount: active.filter((t) => t.labels.some((x) => x.id === l.id)).length })),
        filters: filterRows.map((f) => ({ id: f.id, name: f.name, query: f.query, color: f.color, isFavorite: f.isFavorite, count: active.filter((t) => matchesFilter(t, f.query, today)).length })),
        projectLimit: 300, filterLimit: 150,
      };
    },
  }),

  listTasks: defineAction({
    description: "Lists tasks for a view (inbox, today, upcoming, project, label, completed, all, filter, calendar-day), filtered against the caller's local today. Subtasks are excluded from top-level views.",
    request: z.object({
      view: z.enum(["inbox", "today", "upcoming", "project", "label", "completed", "all", "filter", "day"]),
      projectId: z.number().int().positive().optional(),
      labelId: z.number().int().positive().optional(),
      filterId: z.number().int().positive().optional(),
      filterQuery: z.string().max(300).optional(),
      date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional(),
      query: z.string().max(200).optional(),
      today: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
    }),
    response: z.object({ tasks: z.array(taskOut) }),
    async handler(ctx, args) {
      const { tasks } = await loadTaskRows(ctx);
      const top = tasks.filter((t) => t.parentId == null);
      const q = args.query?.trim().toLowerCase();
      let out: TaskOut[];
      switch (args.view) {
        case "inbox": out = top.filter((t) => t.status === "active" && t.projectId == null && !t.dueDate); break;
        case "today": out = top.filter((t) => t.status === "active" && t.dueDate != null && t.dueDate <= args.today); break;
        case "upcoming": out = top.filter((t) => t.status === "active" && t.dueDate != null); break;
        case "project": out = top.filter((t) => t.status === "active" && t.projectId === args.projectId); break;
        case "label": out = top.filter((t) => t.status === "active" && t.labels.some((l) => l.id === args.labelId)); break;
        case "filter": {
          let queryStr = args.filterQuery ?? "";
          if (args.filterId != null) {
            const db = ctx.db<typeof schema>();
            const [f] = await db.select().from(schema.filters).where(eq(schema.filters.id, args.filterId));
            queryStr = f?.query ?? "";
          }
          out = top.filter((t) => t.status === "active" && matchesFilter(t, queryStr, args.today));
          break;
        }
        case "day": out = top.filter((t) => t.status === "active" && t.dueDate === args.date); break;
        case "completed": out = top.filter((t) => t.status === "completed").sort((a, b) => (b.completedAt ?? "").localeCompare(a.completedAt ?? "")); break;
        default: out = top.filter((t) => t.status === "active");
      }
      if (q) out = out.filter((t) => t.title.toLowerCase().includes(q));
      if (args.view !== "completed") {
        out = [...out].sort((a, b) => {
          const da = a.dueDate ?? "9999-12-31"; const db2 = b.dueDate ?? "9999-12-31";
          if (da !== db2) return da.localeCompare(db2);
          const ta = a.dueTime ?? "99:99"; const tb = b.dueTime ?? "99:99";
          if (ta !== tb) return ta.localeCompare(tb);
          if (a.priority !== b.priority) return b.priority - a.priority;
          return a.id - b.id;
        });
      }
      return { tasks: out };
    },
  }),

  listSubtasks: defineAction({
    description: "Lists subtasks of a parent task, in order.",
    request: z.object({ parentId: z.number().int().positive() }),
    response: z.object({ tasks: z.array(taskOut) }),
    async handler(ctx, args) {
      const { tasks } = await loadTaskRows(ctx);
      return { tasks: tasks.filter((t) => t.parentId === args.parentId) };
    },
  }),

  addTask: defineAction({
    description: "Adds a task (or subtask with parentId). Parses natural language (dates, times, recurrence, duration like 'for 30m', #project, @label, p1-p4) unless explicit fields are given.",
    request: z.object({
      rawText: z.string().trim().min(1).max(500),
      today: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
      projectId: z.number().int().positive().nullable().optional(),
      parentId: z.number().int().positive().nullable().optional(),
      priority: z.number().int().min(1).max(4).optional(),
      dueDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).nullable().optional(),
      dueTime: z.string().regex(/^\d{2}:\d{2}$/).nullable().optional(),
      recurrenceRule: z.string().max(40).nullable().optional(),
      durationMinutes: z.number().int().min(1).max(1440).nullable().optional(),
      notes: z.string().max(2000).optional(),
    }),
    response: z.object({ id: z.number() }),
    async handler(ctx, args) {
      const db = ctx.db<typeof schema>();
      const parsed = parseQuickAdd(args.rawText, args.today);
      let projectId: number | null = args.projectId ?? null;
      if (projectId == null && parsed.projectName && args.parentId == null) projectId = await findOrCreateProject(ctx, parsed.projectName);
      const labelIds: number[] = [];
      for (const name of parsed.labelNames) { const id = await findOrCreateLabel(ctx, name); if (id != null) labelIds.push(id); }
      const [row] = await db.insert(schema.tasks).values({
        title: parsed.title, notes: args.notes ?? null, projectId,
        parentId: args.parentId ?? null,
        priority: args.priority ?? parsed.priority,
        dueDate: args.dueDate !== undefined ? args.dueDate : (args.parentId != null ? null : parsed.dueDate),
        dueTime: args.dueTime !== undefined ? args.dueTime : parsed.dueTime,
        recurrenceRule: args.recurrenceRule !== undefined ? args.recurrenceRule : parsed.recurrenceRule,
        durationMinutes: args.durationMinutes !== undefined ? args.durationMinutes : parsed.durationMinutes,
      }).returning({ id: schema.tasks.id });
      if (!row) throw new Error("addTask: insert returned no rows");
      if (labelIds.length) await setTaskLabels(ctx, row.id, labelIds);
      if (args.parentId == null) {
        const [created] = await db.select().from(schema.tasks).where(eq(schema.tasks.id, row.id));
        if (created) await logActivity(ctx, "created", created, args.today);
      }
      await maybeAutoBackup(ctx);
      ctx.invalidateQueries();
      return { id: row.id };
    },
  }),

  bulkAddTasks: defineAction({
    description: "Adds many tasks at once (Ramble + Assist). Each line is parsed with the natural-language parser.",
    request: z.object({
      lines: z.array(z.string().trim().min(1).max(500)).min(1).max(50),
      today: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
      projectId: z.number().int().positive().nullable().optional(),
    }),
    response: z.object({ added: z.number(), ids: z.array(z.number()) }),
    async handler(ctx, args) {
      const ids: number[] = [];
      for (const line of args.lines) {
        const db = ctx.db<typeof schema>();
        const parsed = parseQuickAdd(line, args.today);
        let projectId: number | null = args.projectId ?? null;
        if (projectId == null && parsed.projectName) projectId = await findOrCreateProject(ctx, parsed.projectName);
        const labelIds: number[] = [];
        for (const name of parsed.labelNames) { const id = await findOrCreateLabel(ctx, name); if (id != null) labelIds.push(id); }
        const [row] = await db.insert(schema.tasks).values({
          title: parsed.title, projectId, priority: parsed.priority,
          dueDate: parsed.dueDate, dueTime: parsed.dueTime, recurrenceRule: parsed.recurrenceRule,
          durationMinutes: parsed.durationMinutes,
        }).returning({ id: schema.tasks.id });
        if (row) { ids.push(row.id); if (labelIds.length) await setTaskLabels(ctx, row.id, labelIds); const [c] = await db.select().from(schema.tasks).where(eq(schema.tasks.id, row.id)); if (c) await logActivity(ctx, "created", c, args.today); }
      }
      await maybeAutoBackup(ctx);
      ctx.invalidateQueries();
      return { added: ids.length, ids };
    },
  }),

  updateTask: defineAction({
    description: "Updates a task's title, notes, project, priority, due date/time, recurrence, duration, or labels.",
    request: z.object({
      id: z.number().int().positive(),
      title: z.string().trim().min(1).max(500).optional(),
      notes: z.string().max(2000).nullable().optional(),
      projectId: z.number().int().positive().nullable().optional(),
      priority: z.number().int().min(1).max(4).optional(),
      dueDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).nullable().optional(),
      dueTime: z.string().regex(/^\d{2}:\d{2}$/).nullable().optional(),
      recurrenceRule: z.string().max(40).nullable().optional(),
      durationMinutes: z.number().int().min(1).max(1440).nullable().optional(),
      labelIds: z.array(z.number().int().positive()).max(20).optional(),
    }),
    response: okResponse,
    async handler(ctx, args): Promise<OkResponse> {
      const db = ctx.db<typeof schema>();
      const patch: Partial<typeof schema.tasks.$inferInsert> = { updatedAt: new Date() };
      if (args.title !== undefined) patch.title = args.title;
      if (args.notes !== undefined) patch.notes = args.notes;
      if (args.projectId !== undefined) patch.projectId = args.projectId;
      if (args.priority !== undefined) patch.priority = args.priority;
      if (args.dueDate !== undefined) patch.dueDate = args.dueDate;
      if (args.dueTime !== undefined) patch.dueTime = args.dueTime;
      if (args.recurrenceRule !== undefined) patch.recurrenceRule = args.recurrenceRule;
      if (args.durationMinutes !== undefined) patch.durationMinutes = args.durationMinutes;
      await db.update(schema.tasks).set(patch).where(eq(schema.tasks.id, args.id));
      if (args.labelIds !== undefined) await setTaskLabels(ctx, args.id, args.labelIds);
      ctx.invalidateQueries();
      return { ok: true as const };
    },
  }),

  completeTask: defineAction({
    description: "Completes a task and logs the completion. A recurring task rolls to its next due date; subtasks complete independently.",
    request: z.object({ id: z.number().int().positive(), today: z.string().regex(/^\d{4}-\d{2}-\d{2}$/) }),
    response: z.object({ recurring: z.boolean(), nextDueDate: z.string().nullable() }),
    async handler(ctx, args) {
      const db = ctx.db<typeof schema>();
      const [task] = await db.select().from(schema.tasks).where(eq(schema.tasks.id, args.id));
      if (!task) throw new Error("Task not found");
      await db.insert(schema.completions).values({ taskId: task.id, taskTitle: task.title, completedDate: args.today });
      await logActivity(ctx, "completed", task, args.today);
      if (task.recurrenceRule && task.parentId == null) {
        const base = task.dueDate ?? args.today;
        let next = nextOccurrence(task.recurrenceRule, base);
        while (next < args.today) next = nextOccurrence(task.recurrenceRule, next);
        if (next === base) next = nextOccurrence(task.recurrenceRule, next);
        await db.update(schema.tasks).set({ dueDate: next, updatedAt: new Date() }).where(eq(schema.tasks.id, task.id));
        ctx.invalidateQueries();
        return { recurring: true, nextDueDate: next };
      }
      await db.update(schema.tasks).set({ status: "completed", completedAt: new Date(), updatedAt: new Date() }).where(eq(schema.tasks.id, task.id));
      ctx.invalidateQueries();
      return { recurring: false, nextDueDate: null };
    },
  }),

  uncompleteTask: defineAction({
    description: "Moves a completed task back to active.",
    request: z.object({ id: z.number().int().positive() }),
    response: okResponse,
    async handler(ctx, args): Promise<OkResponse> {
      const db = ctx.db<typeof schema>();
      await db.update(schema.tasks).set({ status: "active", completedAt: null, updatedAt: new Date() }).where(eq(schema.tasks.id, args.id));
      ctx.invalidateQueries();
      return { ok: true as const };
    },
  }),

  deleteTask: defineAction({
    description: "Permanently deletes a task, its subtasks, label links and reminders.",
    request: z.object({ id: z.number().int().positive() }),
    response: okResponse,
    async handler(ctx, args): Promise<OkResponse> {
      const db = ctx.db<typeof schema>();
      const subs = await db.select().from(schema.tasks).where(eq(schema.tasks.parentId, args.id));
      for (const s of subs) {
        await db.delete(schema.taskLabels).where(eq(schema.taskLabels.taskId, s.id));
        await db.delete(schema.reminders).where(eq(schema.reminders.taskId, s.id));
        await db.delete(schema.tasks).where(eq(schema.tasks.id, s.id));
      }
      await db.delete(schema.taskLabels).where(eq(schema.taskLabels.taskId, args.id));
      await db.delete(schema.reminders).where(eq(schema.reminders.taskId, args.id));
      await db.delete(schema.tasks).where(eq(schema.tasks.id, args.id));
      ctx.invalidateQueries();
      return { ok: true as const };
    },
  }),

  addProject: defineAction({
    description: "Creates a project (Pro: up to 300 active projects).",
    request: z.object({ name: z.string().trim().min(1).max(60), color: z.string().max(20).default("#E44332") }),
    response: z.object({ id: z.number() }),
    async handler(ctx, args) {
      const db = ctx.db<typeof schema>();
      const count = (await db.select().from(schema.projects)).length;
      if (count >= 300) throw new Error("Project limit reached (300). Delete a project first.");
      const [row] = await db.insert(schema.projects).values({ name: args.name, color: args.color }).returning({ id: schema.projects.id });
      if (!row) throw new Error("addProject: insert returned no rows");
      ctx.invalidateQueries();
      return { id: row.id };
    },
  }),
  deleteProject: defineAction({
    description: "Deletes a project. Its tasks are kept and moved back to the Inbox.",
    request: z.object({ id: z.number().int().positive() }),
    response: okResponse,
    async handler(ctx, args): Promise<OkResponse> {
      const db = ctx.db<typeof schema>();
      await db.update(schema.tasks).set({ projectId: null, updatedAt: new Date() }).where(eq(schema.tasks.projectId, args.id));
      await db.delete(schema.projects).where(eq(schema.projects.id, args.id));
      ctx.invalidateQueries();
      return { ok: true as const };
    },
  }),
  addLabel: defineAction({
    description: "Creates a label with a name and accent color.",
    request: z.object({ name: z.string().trim().min(1).max(40), color: z.string().max(20).default("#42A5F5") }),
    response: z.object({ id: z.number() }),
    async handler(ctx, args) {
      const db = ctx.db<typeof schema>();
      const [row] = await db.insert(schema.labels).values({ name: args.name, color: args.color }).returning({ id: schema.labels.id });
      if (!row) throw new Error("addLabel: insert returned no rows");
      ctx.invalidateQueries();
      return { id: row.id };
    },
  }),
  deleteLabel: defineAction({
    description: "Deletes a label and removes it from all tasks.",
    request: z.object({ id: z.number().int().positive() }),
    response: okResponse,
    async handler(ctx, args): Promise<OkResponse> {
      const db = ctx.db<typeof schema>();
      await db.delete(schema.taskLabels).where(eq(schema.taskLabels.labelId, args.id));
      await db.delete(schema.labels).where(eq(schema.labels.id, args.id));
      ctx.invalidateQueries();
      return { ok: true as const };
    },
  }),

  // ---- Filters (Pro: up to 150) ----
  addFilter: defineAction({
    description: "Creates a saved filter view (Pro, up to 150). Query syntax: today, overdue, p1, #Project, @label, 'no date', combined with & / AND.",
    request: z.object({ name: z.string().trim().min(1).max(60), query: z.string().trim().min(1).max(300), color: z.string().max(20).default("#E44332") }),
    response: z.object({ id: z.number() }),
    async handler(ctx, args) {
      const db = ctx.db<typeof schema>();
      const count = (await db.select().from(schema.filters)).length;
      if (count >= 150) throw new Error("Filter limit reached (150).");
      const [row] = await db.insert(schema.filters).values({ name: args.name, query: args.query, color: args.color }).returning({ id: schema.filters.id });
      if (!row) throw new Error("addFilter failed");
      ctx.invalidateQueries();
      return { id: row.id };
    },
  }),
  updateFilter: defineAction({
    description: "Updates a saved filter's name, query or color.",
    request: z.object({ id: z.number().int().positive(), name: z.string().trim().min(1).max(60).optional(), query: z.string().trim().min(1).max(300).optional(), color: z.string().max(20).optional(), isFavorite: z.boolean().optional() }),
    response: okResponse,
    async handler(ctx, args): Promise<OkResponse> {
      const db = ctx.db<typeof schema>();
      const patch: Partial<typeof schema.filters.$inferInsert> = {};
      if (args.name !== undefined) patch.name = args.name;
      if (args.query !== undefined) patch.query = args.query;
      if (args.color !== undefined) patch.color = args.color;
      if (args.isFavorite !== undefined) patch.isFavorite = args.isFavorite;
      await db.update(schema.filters).set(patch).where(eq(schema.filters.id, args.id));
      ctx.invalidateQueries();
      return { ok: true as const };
    },
  }),
  deleteFilter: defineAction({
    description: "Deletes a saved filter view.",
    request: z.object({ id: z.number().int().positive() }),
    response: okResponse,
    async handler(ctx, args): Promise<OkResponse> {
      const db = ctx.db<typeof schema>();
      await db.delete(schema.filters).where(eq(schema.filters.id, args.id));
      ctx.invalidateQueries();
      return { ok: true as const };
    },
  }),

  // ---- Reminders (Pro) ----
  addReminder: defineAction({
    description: "Adds a custom reminder to a task: offsetMinutes before due time (0 = at time, 10, 30, 60, 1440, or custom), or an absolute datetime.",
    request: z.object({ taskId: z.number().int().positive(), offsetMinutes: z.number().int().min(0).max(10080), label: z.string().max(60), absoluteAt: z.string().max(40).nullable().optional() }),
    response: z.object({ id: z.number() }),
    async handler(ctx, args) {
      const db = ctx.db<typeof schema>();
      const [row] = await db.insert(schema.reminders).values({ taskId: args.taskId, offsetMinutes: args.offsetMinutes, label: args.label, absoluteAt: args.absoluteAt ?? null }).returning({ id: schema.reminders.id });
      if (!row) throw new Error("addReminder failed");
      ctx.invalidateQueries();
      return { id: row.id };
    },
  }),
  deleteReminder: defineAction({
    description: "Deletes a reminder.",
    request: z.object({ id: z.number().int().positive() }),
    response: okResponse,
    async handler(ctx, args): Promise<OkResponse> {
      const db = ctx.db<typeof schema>();
      await db.delete(schema.reminders).where(eq(schema.reminders.id, args.id));
      ctx.invalidateQueries();
      return { ok: true as const };
    },
  }),
  dismissReminder: defineAction({
    description: "Dismisses a due reminder so it stops showing in the banner.",
    request: z.object({ id: z.number().int().positive() }),
    response: okResponse,
    async handler(ctx, args): Promise<OkResponse> {
      const db = ctx.db<typeof schema>();
      await db.update(schema.reminders).set({ dismissedAt: new Date() }).where(eq(schema.reminders.id, args.id));
      ctx.invalidateQueries();
      return { ok: true as const };
    },
  }),
  snoozeReminder: defineAction({
    description: "Snoozes a reminder by N minutes.",
    request: z.object({ id: z.number().int().positive(), minutes: z.number().int().min(1).max(1440).default(10) }),
    response: okResponse,
    async handler(ctx, args): Promise<OkResponse> {
      const db = ctx.db<typeof schema>();
      await db.update(schema.reminders).set({ snoozedUntil: new Date(Date.now() + args.minutes * 60000), dismissedAt: null }).where(eq(schema.reminders.id, args.id));
      ctx.invalidateQueries();
      return { ok: true as const };
    },
  }),
  getDueReminders: defineAction({
    description: "Returns reminders that are due right now (for the in-app banner). Computes fire time from task due date/time minus offset; honest scope: only while the app is open.",
    request: z.object({ nowIso: z.string().max(40), today: z.string().regex(/^\d{4}-\d{2}-\d{2}$/) }),
    response: z.object({ due: z.array(z.object({ reminderId: z.number(), taskId: z.number(), taskTitle: z.string(), label: z.string(), dueDate: z.string().nullable(), dueTime: z.string().nullable(), fireAt: z.string() })) }),
    async handler(ctx, args) {
      const { tasks } = await loadTaskRows(ctx);
      const now = new Date(args.nowIso).getTime();
      const due: { reminderId: number; taskId: number; taskTitle: string; label: string; dueDate: string | null; dueTime: string | null; fireAt: string }[] = [];
      const taskById = new Map(tasks.map((t) => [t.id, t]));
      const db = ctx.db<typeof schema>();
      const rows = await db.select().from(schema.reminders);
      for (const r of rows) {
        if (r.dismissedAt) continue;
        if (r.snoozedUntil && r.snoozedUntil.getTime() > now) continue;
        const task = taskById.get(r.taskId);
        if (!task || task.status !== "active") continue;
        let fireMs: number | null = null;
        if (r.absoluteAt) fireMs = new Date(r.absoluteAt).getTime();
        else if (task.dueDate) {
          const [y = 1970, mo = 1, d = 1] = task.dueDate.split("-").map(Number);
          const [hh = 9, mm = 0] = (task.dueTime ?? "09:00").split(":").map(Number);
          fireMs = new Date(y, mo - 1, d, hh, mm).getTime() - r.offsetMinutes * 60000;
        }
        if (fireMs != null && now >= fireMs && now <= fireMs + 24 * 3600 * 1000) {
          due.push({ reminderId: r.id, taskId: task.id, taskTitle: task.title, label: r.label, dueDate: task.dueDate, dueTime: task.dueTime, fireAt: new Date(fireMs).toISOString() });
        }
      }
      return { due };
    },
  }),

  // ---- Calendar (Pro) ----
  getCalendar: defineAction({
    description: "Returns task counts and tasks per day for a month (YYYY-MM), for the calendar view.",
    request: z.object({ month: z.string().regex(/^\d{4}-\d{2}$/), today: z.string().regex(/^\d{4}-\d{2}-\d{2}$/) }),
    response: z.object({ days: z.array(z.object({ date: z.string(), count: z.number(), tasks: z.array(z.object({ id: z.number(), title: z.string(), priority: z.number(), dueTime: z.string().nullable() })) })) }),
    async handler(ctx, args) {
      const { tasks } = await loadTaskRows(ctx);
      const active = tasks.filter((t) => t.parentId == null && t.status === "active" && t.dueDate?.startsWith(args.month));
      const map = new Map<string, typeof active>();
      for (const t of active) { const l = map.get(t.dueDate!) ?? []; l.push(t); map.set(t.dueDate!, l); }
      return { days: [...map.entries()].map(([date, ts]) => ({ date, count: ts.length, tasks: ts.map((t) => ({ id: t.id, title: t.title, priority: t.priority, dueTime: t.dueTime })) })) };
    },
  }),

  // ---- Stats + History (Pro: full reporting) ----
  getStats: defineAction({
    description: "Productivity stats: completed today, total, streak, last-7-days, today's planned duration total, and progress.",
    request: z.object({ today: z.string().regex(/^\d{4}-\d{2}-\d{2}$/) }),
    response: z.object({
      completedToday: z.number(), totalCompleted: z.number(), streak: z.number(),
      weekly: z.array(z.object({ date: z.string(), count: z.number() })),
      dueTodayTotal: z.number(), overdueCount: z.number(), activeTotal: z.number(),
      todayDurationMinutes: z.number(),
      recentCompletions: z.array(z.object({ title: z.string(), date: z.string() })),
    }),
    async handler(ctx, args) {
      const db = ctx.db<typeof schema>();
      const rows = await db.select().from(schema.completions).orderBy(desc(schema.completions.id));
      const { tasks } = await loadTaskRows(ctx);
      const top = tasks.filter((t) => t.parentId == null);
      const active = top.filter((t) => t.status === "active");
      const byDate = new Map<string, number>();
      for (const r of rows) byDate.set(r.completedDate, (byDate.get(r.completedDate) ?? 0) + 1);
      let streak = 0; let cursor = byDate.has(args.today) ? args.today : addDays(args.today, -1);
      while (byDate.has(cursor)) { streak++; cursor = addDays(cursor, -1); }
      const weekly = Array.from({ length: 7 }, (_, i) => { const date = addDays(args.today, i - 6); return { date, count: byDate.get(date) ?? 0 }; });
      const todayTasks = active.filter((t) => t.dueDate === args.today);
      return {
        completedToday: byDate.get(args.today) ?? 0, totalCompleted: rows.length, streak, weekly,
        dueTodayTotal: todayTasks.length,
        overdueCount: active.filter((t) => t.dueDate != null && t.dueDate < args.today).length,
        activeTotal: active.length,
        todayDurationMinutes: todayTasks.reduce((s, t) => s + (t.durationMinutes ?? 0), 0),
        recentCompletions: rows.slice(0, 8).map((r) => ({ title: r.taskTitle, date: r.completedDate })),
      };
    },
  }),

  getHistory: defineAction({
    description: "Full reporting history (Pro): all completion + creation activity, filterable by search text, project and date range, plus 30-day chart data, best day and totals.",
    request: z.object({
      today: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
      query: z.string().max(200).optional(),
      projectId: z.number().int().positive().nullable().optional(),
      from: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional(),
      to: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional(),
      limit: z.number().int().min(1).max(500).default(200),
    }),
    response: z.object({
      items: z.array(z.object({ id: z.number(), type: z.string(), title: z.string(), projectName: z.string().nullable(), date: z.string() })),
      last30: z.array(z.object({ date: z.string(), count: z.number() })),
      bestDay: z.object({ date: z.string(), count: z.number() }).nullable(),
      totalCompleted: z.number(), totalCreated: z.number(),
    }),
    async handler(ctx, args) {
      const db = ctx.db<typeof schema>();
      // Backfill any pre-Pro history missing from the log (dedupe by taskId
      // for creations, by title+date counts for completions).
      let acts = await db.select().from(schema.activities).orderBy(desc(schema.activities.id));
      {
        const comps = await db.select().from(schema.completions);
        const tks = await db.select().from(schema.tasks);
        const projRows = await db.select().from(schema.projects);
        const pname = new Map(projRows.map((p) => [p.id, p.name]));
        const createdIds = new Set(acts.filter((a) => a.type === "created").map((a) => a.taskId));
        let dirty = false;
        for (const t of tks) {
          if (createdIds.has(t.id)) continue;
          const d = new Intl.DateTimeFormat("en-CA", { timeZone: USER_TZ, year: "numeric", month: "2-digit", day: "2-digit" }).format(t.createdAt);
          await db.insert(schema.activities).values({ type: "created", taskId: t.id, taskTitle: t.title, projectId: t.projectId, projectName: t.projectId != null ? (pname.get(t.projectId) ?? null) : null, eventDate: d });
          dirty = true;
        }
        const compKey = (title: string, date: string) => `${title}|${date}`;
        const have = new Map<string, number>();
        for (const a of acts) if (a.type === "completed") have.set(compKey(a.taskTitle, a.eventDate), (have.get(compKey(a.taskTitle, a.eventDate)) ?? 0) + 1);
        for (const c of comps) {
          const k = compKey(c.taskTitle, c.completedDate);
          const n = have.get(k) ?? 0;
          if (n > 0) { have.set(k, n - 1); continue; }
          await db.insert(schema.activities).values({ type: "completed", taskId: c.taskId, taskTitle: c.taskTitle, projectId: null, projectName: null, eventDate: c.completedDate });
          dirty = true;
        }
        if (dirty) acts = await db.select().from(schema.activities).orderBy(desc(schema.activities.id));
      }
      const comps = await db.select().from(schema.completions);
      const byDate = new Map<string, number>();
      for (const r of comps) byDate.set(r.completedDate, (byDate.get(r.completedDate) ?? 0) + 1);
      const last30 = Array.from({ length: 30 }, (_, i) => { const date = addDays(args.today, i - 29); return { date, count: byDate.get(date) ?? 0 }; });
      let bestDay: { date: string; count: number } | null = null;
      for (const [date, count] of byDate) if (!bestDay || count > bestDay.count) bestDay = { date, count };
      let filtered = acts;
      const q = args.query?.trim().toLowerCase();
      if (q) filtered = filtered.filter((a) => a.taskTitle.toLowerCase().includes(q));
      if (args.projectId != null) filtered = filtered.filter((a) => a.projectId === args.projectId);
      if (args.from) filtered = filtered.filter((a) => a.eventDate >= args.from!);
      if (args.to) filtered = filtered.filter((a) => a.eventDate <= args.to!);
      return {
        items: filtered.slice(0, args.limit).map((a) => ({ id: a.id, type: a.type, title: a.taskTitle, projectName: a.projectName, date: a.eventDate })),
        last30, bestDay,
        totalCompleted: acts.filter((a) => a.type === "completed").length,
        totalCreated: acts.filter((a) => a.type === "created").length,
      };
    },
  }),

  // ---- Project insights (Pro) ----
  getProjectInsights: defineAction({
    description: "Per-project insights (Pro): progress done vs total, last-4-weeks completion trend, overdue count, most productive weekday, total open-task duration.",
    request: z.object({ today: z.string().regex(/^\d{4}-\d{2}-\d{2}$/) }),
    response: z.object({
      projects: z.array(z.object({
        id: z.number(), name: z.string(), color: z.string(),
        total: z.number(), done: z.number(), active: z.number(), overdue: z.number(),
        pct: z.number(), openDurationMinutes: z.number(),
        weeklyTrend: z.array(z.object({ weekStart: z.string(), count: z.number() })),
        bestWeekday: z.string().nullable(),
      })),
      inbox: z.object({ total: z.number(), done: z.number(), active: z.number(), overdue: z.number(), pct: z.number(), openDurationMinutes: z.number() }),
    }),
    async handler(ctx, args) {
      const db = ctx.db<typeof schema>();
      const { tasks, projectRows } = await loadTaskRows(ctx);
      const acts = await db.select().from(schema.activities);
      const top = tasks.filter((t) => t.parentId == null);
      const WEEKDAYS = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"];
      function insightsFor(projectId: number | null) {
        const mine = top.filter((t) => t.projectId === projectId);
        const doneViaStatus = mine.filter((t) => t.status === "completed").length;
        const completedEvents = acts.filter((a) => a.type === "completed" && a.projectId === projectId).length;
        // total ever = active + completed-status + recurring completion events not double counted simply:
        const active = mine.filter((t) => t.status === "active");
        const total = mine.length + Math.max(0, completedEvents - doneViaStatus);
        const done = total - active.length;
        const overdue = active.filter((t) => t.dueDate != null && t.dueDate < args.today).length;
        const openDurationMinutes = active.reduce((s, t) => s + (t.durationMinutes ?? 0), 0);
        const weeklyTrend = Array.from({ length: 4 }, (_, i) => {
          const weekStart = addDays(args.today, -(3 - i) * 7 - 6);
          const weekEnd = addDays(weekStart, 6);
          const count = acts.filter((a) => a.type === "completed" && a.projectId === projectId && a.eventDate >= weekStart && a.eventDate <= weekEnd).length;
          return { weekStart, count };
        });
        const dowCounts = new Array(7).fill(0) as number[];
        for (const a of acts) {
          if (a.type !== "completed" || a.projectId !== projectId) continue;
          const [y = 1970, m = 1, d = 1] = a.eventDate.split("-").map(Number);
          dowCounts[new Date(Date.UTC(y, m - 1, d)).getUTCDay()]!++;
        }
        const bestIdx = dowCounts.indexOf(Math.max(...dowCounts));
        const bestWeekday = Math.max(...dowCounts) > 0 ? (WEEKDAYS[bestIdx] ?? null) : null;
        return { total, done, active: active.length, overdue, pct: total > 0 ? Math.round((done / total) * 100) : 0, openDurationMinutes, weeklyTrend, bestWeekday };
      }
      return {
        projects: projectRows.map((p) => { const ins = insightsFor(p.id); return { id: p.id, name: p.name, color: p.color, ...ins }; }),
        inbox: (() => { const ins = insightsFor(null); return { total: ins.total, done: ins.done, active: ins.active, overdue: ins.overdue, pct: ins.pct, openDurationMinutes: ins.openDurationMinutes }; })(),
      };
    },
  }),

  // ---- Assist (Pro) ----
  assistExtract: defineAction({
    description: "Task & Email Assist: extracts actionable lines from pasted text/email, each pre-parsed with the NLP parser so the user can review before adding.",
    request: z.object({ text: z.string().min(1).max(10000), today: z.string().regex(/^\d{4}-\d{2}-\d{2}$/) }),
    response: z.object({ items: z.array(z.object({ raw: z.string(), title: z.string(), dueDate: z.string().nullable(), priority: z.number() })) }),
    async handler(_ctx, args) {
      const lines = extractActionLines(args.text);
      return { items: lines.map((raw) => { const p = parseQuickAdd(raw, args.today); return { raw, title: p.title, dueDate: p.dueDate, priority: p.priority }; }) };
    },
  }),
  assistBreakdown: defineAction({
    description: "Break-it-down helper: suggests subtasks for a task title using keyword templates (video/script/project/study/clean/buy…) plus generic steps.",
    request: z.object({ title: z.string().trim().min(1).max(500) }),
    response: z.object({ steps: z.array(z.string()) }),
    async handler(_ctx, args) { return { steps: breakdownTask(args.title) }; },
  }),
  addSubtasks: defineAction({
    description: "Saves suggested steps as real subtasks under a parent task.",
    request: z.object({ parentId: z.number().int().positive(), titles: z.array(z.string().trim().min(1).max(500)).min(1).max(20), today: z.string().regex(/^\d{4}-\d{2}-\d{2}$/) }),
    response: z.object({ added: z.number() }),
    async handler(ctx, args) {
      const db = ctx.db<typeof schema>();
      const [parent] = await db.select().from(schema.tasks).where(eq(schema.tasks.id, args.parentId));
      if (!parent) throw new Error("Parent task not found");
      let added = 0;
      for (const title of args.titles) {
        await db.insert(schema.tasks).values({ title, parentId: args.parentId, projectId: parent.projectId, priority: 1 });
        added++;
      }
      ctx.invalidateQueries();
      return { added };
    },
  }),

  // ---- Backups (Pro: auto backups) ----
  getBackups: defineAction({
    description: "Lists backups with last-backup time, for the Settings/Backups section.",
    request: z.object({}),
    response: z.object({ backups: z.array(z.object({ id: z.number(), kind: z.string(), createdAt: z.string(), sizeKb: z.number() })), lastBackupAt: z.string().nullable() }),
    async handler(ctx) {
      const db = ctx.db<typeof schema>();
      const rows = await db.select().from(schema.backups).orderBy(desc(schema.backups.id));
      return { backups: rows.map((b) => ({ id: b.id, kind: b.kind, createdAt: b.createdAt.toISOString(), sizeKb: Math.max(1, Math.round(b.data.length / 1024)) })), lastBackupAt: rows[0]?.createdAt.toISOString() ?? null };
    },
  }),
  createBackup: defineAction({
    description: "Creates a manual backup snapshot of all app data now.",
    request: z.object({}),
    response: z.object({ id: z.number(), createdAt: z.string() }),
    async handler(ctx) {
      const db = ctx.db<typeof schema>();
      const data = await buildSnapshot(ctx);
      const [row] = await db.insert(schema.backups).values({ kind: "manual", data: JSON.stringify(data) }).returning({ id: schema.backups.id, createdAt: schema.backups.createdAt });
      if (!row) throw new Error("createBackup failed");
      ctx.invalidateQueries();
      return { id: row.id, createdAt: row.createdAt.toISOString() };
    },
  }),
  exportBackup: defineAction({
    description: "Returns the full JSON export (latest backup or a fresh snapshot) for download.",
    request: z.object({ backupId: z.number().int().positive().optional() }),
    response: z.object({ json: z.string(), filename: z.string() }),
    async handler(ctx, args) {
      const db = ctx.db<typeof schema>();
      if (args.backupId != null) {
        const [b] = await db.select().from(schema.backups).where(eq(schema.backups.id, args.backupId));
        if (!b) throw new Error("Backup not found");
        return { json: b.data, filename: `task-manager-backup-${b.id}.json` };
      }
      const data = await buildSnapshot(ctx);
      return { json: JSON.stringify(data, null, 2), filename: "task-manager-export.json" };
    },
  }),
  restoreBackup: defineAction({
    description: "Restores app data from a backup snapshot (by backup id) or from pasted/uploaded export JSON. Replaces current tasks/projects/labels/filters; completions & history are restored too.",
    request: z.object({ backupId: z.number().int().positive().optional(), json: z.string().max(5000000).optional() }),
    response: z.object({ restored: z.object({ tasks: z.number(), projects: z.number(), labels: z.number(), filters: z.number() }) }),
    async handler(ctx, args) {
      const db = ctx.db<typeof schema>();
      let raw = args.json;
      if (args.backupId != null) {
        const [b] = await db.select().from(schema.backups).where(eq(schema.backups.id, args.backupId));
        if (!b) throw new Error("Backup not found");
        raw = b.data;
      }
      if (!raw) throw new Error("Nothing to restore");
      const data = JSON.parse(raw) as Record<string, Record<string, unknown>[]>;
      const revive = (rows: Record<string, unknown>[] | undefined) => (rows ?? []).map((r) => {
        const o: Record<string, unknown> = {};
        for (const [k, v] of Object.entries(r)) {
          if (typeof v === "string" && /^\d{4}-\d{2}-\d{2}T/.test(v) && (k.endsWith("At") || k === "createdAt" || k === "updatedAt" || k === "completedAt" || k === "dismissedAt" || k === "snoozedUntil")) o[k] = new Date(v);
          else o[k] = v;
        }
        return o;
      });
      // Wipe in FK-safe order, then re-insert preserving ids.
      await db.delete(schema.reminders); await db.delete(schema.taskLabels); await db.delete(schema.activities);
      await db.delete(schema.completions); await db.delete(schema.tasks); await db.delete(schema.filters);
      await db.delete(schema.labels); await db.delete(schema.projects);
      const projects = revive(data.projects); const labels = revive(data.labels); const tasks = revive(data.tasks);
      const filters = revive(data.filters); const completions = revive(data.completions); const taskLabels = revive(data.taskLabels);
      const reminders = revive(data.reminders); const activities = revive(data.activities);
      for (const r of projects) await db.insert(schema.projects).values(r as never);
      for (const r of labels) await db.insert(schema.labels).values(r as never);
      for (const r of tasks) await db.insert(schema.tasks).values(r as never);
      for (const r of filters) await db.insert(schema.filters).values(r as never);
      for (const r of completions) await db.insert(schema.completions).values(r as never);
      for (const r of taskLabels) await db.insert(schema.taskLabels).values(r as never);
      for (const r of reminders) await db.insert(schema.reminders).values(r as never);
      for (const r of activities) await db.insert(schema.activities).values(r as never);
      ctx.invalidateQueries();
      return { restored: { tasks: tasks.length, projects: projects.length, labels: labels.length, filters: filters.length } };
    },
  }),
  triggerAutoBackup: defineAction({
    description: "Ensures today's automatic backup exists (called on app open). Returns last backup time.",
    request: z.object({}),
    response: z.object({ lastBackupAt: z.string().nullable() }),
    async handler(ctx) {
      await maybeAutoBackup(ctx);
      const db = ctx.db<typeof schema>();
      const rows = await db.select().from(schema.backups).orderBy(desc(schema.backups.id));
      return { lastBackupAt: rows[0]?.createdAt.toISOString() ?? null };
    },
  }),

  seedStarterTasks: defineAction({
    description: "Seeds the user's four daily Must tasks exactly once, dated to the caller's local today; a permanent no-op after the first seed.",
    request: z.object({ today: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional() }),
    response: z.object({ seeded: z.boolean() }),
    async handler(ctx, args) {
      const db = ctx.db<typeof schema>();
      const [flag] = await db.select().from(schema.settings).where(eq(schema.settings.key, "starter_seeded"));
      if (flag) return { seeded: false };
      const existing = await db.select({ id: schema.tasks.id }).from(schema.tasks).limit(1);
      if (existing.length > 0) { await db.insert(schema.settings).values({ key: "starter_seeded", value: "1" }); return { seeded: false }; }
      const today = args.today ?? todayIn(USER_TZ);
      const mustTasks = ["10 minutes Deep Breath 🫁 Meditation Must", "10 minutes Workout Must", "Writing 1 YouTube Script Must", "Make at least One Ai Video"];
      for (const title of mustTasks) {
        const [row] = await db.insert(schema.tasks).values({ title, priority: 4, dueDate: today, recurrenceRule: "daily" }).returning({ id: schema.tasks.id });
        if (row) await logActivity(ctx, "created", { id: row.id, title, projectId: null }, today);
      }
      await db.insert(schema.settings).values({ key: "starter_seeded", value: "1" });
      ctx.invalidateQueries();
      return { seeded: true };
    },
  }),
} satisfies ActionsModule;
