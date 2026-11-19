// Task Manager — App shell.
//
// Views are small and swappable: Inbox / Today / Upcoming / Browse are the
// bottom-nav tabs (mirroring the user's Todoist); Project, Label,
// Completed and Productivity are sub-views pushed from Browse. New
// features should arrive as new views + new components, not edits here
// beyond registering the view.

import { useEffect, useMemo, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { SafeAreaTopScrim } from "@hatch/space-sdk/client";
import { api } from "./api";
import { addDays, fullDateLabel, todayStr } from "./lib/dates";
import { TaskRow, type Task } from "./components/TaskRow";
import { QuickAdd } from "./components/QuickAdd";
import { TaskEditor } from "./components/TaskEditor";
import { BrowseView } from "./components/BrowseView";
import { StatsView } from "./components/StatsView";
import { CalendarView } from "./components/CalendarView";
import { RambleView } from "./components/RambleView";
import { AssistView } from "./components/AssistView";
import { HistoryView } from "./components/HistoryView";
import { InsightsView } from "./components/InsightsView";
import { BackupsView } from "./components/BackupsView";
import { ReminderBanner } from "./components/ReminderBanner";
import { formatDuration } from "./lib/nlp";

type View =
  | { kind: "inbox" }
  | { kind: "today" }
  | { kind: "upcoming" }
  | { kind: "browse" }
  | { kind: "project"; id: number; name: string }
  | { kind: "label"; id: number; name: string }
  | { kind: "filter"; id: number; name: string; query: string }
  | { kind: "completed" }
  | { kind: "stats" }
  | { kind: "calendar" }
  | { kind: "ramble" }
  | { kind: "assist" }
  | { kind: "history" }
  | { kind: "insights" }
  | { kind: "backups" };

const LIST_VIEWS = new Set(["inbox", "today", "upcoming", "project", "label", "completed", "filter"]);

function viewTitle(view: View, today: string): string {
  switch (view.kind) {
    case "inbox": return "Inbox";
    case "today": return "Today";
    case "upcoming": return "Upcoming";
    case "browse": return "Browse";
    case "project": return view.name;
    case "label": return `@${view.name}`;
    case "filter": return view.name;
    case "completed": return "Completed";
    case "stats": return "Productivity";
    case "calendar": return "Calendar";
    case "ramble": return "Ramble";
    case "assist": return "Assist";
    case "history": return "History";
    case "insights": return "Project insights";
    case "backups": return "Backups";
    default: return "";
  }
}

function emptyText(view: View): string {
  switch (view.kind) {
    case "inbox":
      return "Inbox is clear. Quick-add below — tasks with no date or project land here.";
    case "today":
      return "Nothing due today. Add one with the + button — try “Meditate every day”.";
    case "upcoming":
      return "No scheduled tasks yet. Give a task a date in Quick Add: “tomorrow”, “friday”, “dec 25”…";
    case "completed":
      return "No completed tasks yet. Your wins will collect here.";
    case "project":
      return "No tasks in this project yet. Add one with the + button.";
    case "label":
      return "No tasks with this label yet. Tag tasks with @label in Quick Add.";
    case "filter":
      return "No tasks match this filter yet. Try a broader query in Browse → Filter views.";
    default:
      return "Nothing here yet.";
  }
}

export function App() {
  const queryClient = useQueryClient();
  const today = todayStr();
  const [view, setView] = useState<View>({ kind: "today" });
  const [search, setSearch] = useState("");
  const [quickAddOpen, setQuickAddOpen] = useState(false);
  const [quickAddDate, setQuickAddDate] = useState<string | null>(null);
  const [editing, setEditing] = useState<Task | null>(null);
  const [completingIds, setCompletingIds] = useState<Set<number>>(new Set());
  const timers = useRef<Map<number, ReturnType<typeof setTimeout>>>(new Map());

  // First-run seed: the starter Must tasks are dated with THIS viewer's
  // local today, so they always land in the Today view on first open.
  // The action is idempotent and permanently no-ops after the first seed.
  const seedAttempted = useRef(false);
  useEffect(() => {
    if (seedAttempted.current) return;
    seedAttempted.current = true;
    void api
      .seedStarterTasks({ today })
      .then(() => api.triggerAutoBackup({}).catch(() => undefined))
      .then(() => void queryClient.invalidateQueries())
      .catch(() => undefined);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const isListView = LIST_VIEWS.has(view.kind);
  const isSubView = ["project", "label", "completed", "stats", "filter", "calendar", "ramble", "assist", "history", "insights", "backups"].includes(view.kind);

  const bootstrap = useQuery({
    queryKey: ["bootstrap", today],
    queryFn: () => api.getBootstrap({ today }),
  });

  const listArgs = useMemo(() => {
    const base = {
      today,
      query: search.trim() ? search.trim() : undefined,
    };
    switch (view.kind) {
      case "project":
        return { ...base, view: "project" as const, projectId: view.id };
      case "label":
        return { ...base, view: "label" as const, labelId: view.id };
      case "filter":
        return { ...base, view: "filter" as const, filterId: view.id, filterQuery: view.query };
      case "completed":
        return { ...base, view: "completed" as const };
      case "inbox":
      case "today":
      case "upcoming":
        return { ...base, view: view.kind };
      default:
        return { ...base, view: "all" as const };
    }
  }, [view, today, search]);

  const taskList = useQuery({
    queryKey: ["tasks", listArgs],
    queryFn: () => api.listTasks(listArgs),
    enabled: isListView,
  });

  const stats = useQuery({
    queryKey: ["stats", today],
    queryFn: () => api.getStats({ today }),
  });

  const inboxList = useQuery({
    queryKey: ["tasks", { view: "inbox", today }],
    queryFn: () => api.listTasks({ view: "inbox", today }),
  });

  const complete = useMutation({
    mutationFn: async (id: number) => await api.completeTask({ id, today }),
    onSettled: () => void queryClient.invalidateQueries(),
  });
  const uncomplete = useMutation({
    mutationFn: async (id: number) => await api.uncompleteTask({ id }),
    onSettled: () => void queryClient.invalidateQueries(),
  });

  const handleCheck = (task: Task) => {
    if (task.status === "completed") {
      uncomplete.mutate(task.id);
      return;
    }
    if (completingIds.has(task.id)) return;
    setCompletingIds((prev) => new Set(prev).add(task.id));
    const timer = setTimeout(() => {
      complete.mutate(task.id, {
        onSettled: () =>
          setCompletingIds((prev) => {
            const next = new Set(prev);
            next.delete(task.id);
            return next;
          }),
      });
      timers.current.delete(task.id);
    }, 450);
    timers.current.set(task.id, timer);
  };

  const tasks = taskList.data?.tasks ?? [];

  // Group dated views: Overdue first, then one group per date.
  const groups = useMemo(() => {
    if (view.kind !== "upcoming" && view.kind !== "today") return null;
    const map = new Map<string, Task[]>();
    for (const t of tasks) {
      const key =
        view.kind === "today"
          ? t.dueDate != null && t.dueDate < today
            ? "__overdue"
            : "__today"
          : (t.dueDate ?? "__none");
      const list = map.get(key) ?? [];
      list.push(t);
      map.set(key, list);
    }
    return [...map.entries()];
  }, [tasks, view.kind, today]);

  const navBtn = (
    kind: View["kind"],
    label: string,
    icon: React.ReactNode,
  ) => {
    const active =
      view.kind === kind ||
      (kind === "browse" && isSubView);
    return (
      <button
        key={kind}
        type="button"
        aria-label={label}
        aria-current={active ? "page" : undefined}
        onClick={() => {
          setView({ kind } as View);
          setSearch("");
        }}
        className="flex flex-1 flex-col items-center gap-0.5 py-2"
        style={{ color: active ? "#E44332" : "#9B9B9B" }}
      >
        {icon}
        <span className="text-[11px] font-medium">{label}</span>
      </button>
    );
  };

  const dayNum = String(new Date().getDate());

  return (
    <div className="min-h-screen bg-[var(--bg)] pb-28">
      <SafeAreaTopScrim backgroundColor="var(--bg)" />

      <header className="px-4 pt-safe">
        <div className="flex items-center gap-2 pt-3">
          {isSubView && (
            <button
              type="button"
              aria-label="Back to Browse"
              onClick={() => setView({ kind: "browse" })}
              className="-ml-1 px-2 py-1 text-2xl leading-none text-[var(--text)]"
            >
              ‹
            </button>
          )}
          <h1 className="text-[22px] font-bold text-[var(--text)]">
            {viewTitle(view, today)}
          </h1>
        </div>
        {view.kind === "today" && (
          <p className="mt-0.5 text-sm text-[var(--dim)]">
            {fullDateLabel(today)}
            {stats.data &&
              ` · ${stats.data.completedToday} done · ${tasks.length} to go`}
            {stats.data && stats.data.streak > 0 &&
              ` · 🔥 ${stats.data.streak} day streak`}
            {stats.data && stats.data.todayDurationMinutes > 0 &&
              ` · ⏱ ${formatDuration(stats.data.todayDurationMinutes)} planned`}
          </p>
        )}
        {view.kind === "upcoming" && (
          <p className="mt-0.5 text-sm text-[var(--dim)]">
            Everything with a date, in order
          </p>
        )}
        {view.kind === "filter" && (
          <p className="mt-0.5 text-sm text-[var(--dim)]">Filter: {view.query}</p>
        )}
        {view.kind === "calendar" && <p className="mt-0.5 text-sm text-[var(--dim)]">Your month at a glance — Pro calendar view</p>}
        {view.kind === "ramble" && <p className="mt-0.5 text-sm text-[var(--dim)]">Unlimited voice capture — Pro</p>}
        {view.kind === "history" && <p className="mt-0.5 text-sm text-[var(--dim)]">Full reporting history — Pro</p>}
        {isListView && (
          <input
            type="search"
            aria-label="Search tasks"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search tasks…"
            className="mt-3 w-full rounded-lg bg-[var(--surface)] px-3 py-2.5 text-base text-[var(--text)] outline-none placeholder:text-[var(--dim)] focus:ring-2 focus:ring-[var(--accent)]"
          />
        )}
      </header>

      <ReminderBanner />

      <main className="px-4 pt-4">
        {view.kind === "browse" &&
          (bootstrap.isPending ? (
            <p className="text-sm text-[var(--dim)]">Loading…</p>
          ) : bootstrap.error ? (
            <p className="text-sm text-[#E44332]">
              Could not load projects and labels. Pull to retry by reopening the app.
            </p>
          ) : bootstrap.data ? (
            <BrowseView
              bootstrap={bootstrap.data}
              inboxCount={inboxList.data?.tasks.length ?? 0}
              onOpenProject={(id, name) => setView({ kind: "project", id, name })}
              onOpenLabel={(id, name) => setView({ kind: "label", id, name })}
              onOpenCompleted={() => setView({ kind: "completed" })}
              onOpenStats={() => setView({ kind: "stats" })}
              onOpenFilter={(id, name, query) => setView({ kind: "filter", id, name, query })}
              onOpenCalendar={() => setView({ kind: "calendar" })}
              onOpenRamble={() => setView({ kind: "ramble" })}
              onOpenAssist={() => setView({ kind: "assist" })}
              onOpenHistory={() => setView({ kind: "history" })}
              onOpenInsights={() => setView({ kind: "insights" })}
              onOpenBackups={() => setView({ kind: "backups" })}
            />
          ) : null)}

        {view.kind === "calendar" && (
          <CalendarView today={today} onOpenTask={setEditing} onCheckTask={handleCheck} completingIds={completingIds} onAddOnDate={(date) => { setQuickAddDate(date); setQuickAddOpen(true); }} />
        )}
        {view.kind === "ramble" && <RambleView />}
        {view.kind === "assist" && <AssistView />}
        {view.kind === "history" && <HistoryView today={today} bootstrap={bootstrap.data} />}
        {view.kind === "insights" && <InsightsView today={today} />}
        {view.kind === "backups" && <BackupsView />}

        {view.kind === "stats" &&
          (stats.isPending ? (
            <p className="text-sm text-[var(--dim)]">Loading…</p>
          ) : stats.data ? (
            <StatsView stats={stats.data} today={today} />
          ) : (
            <p className="text-sm text-[#E44332]">Could not load your stats.</p>
          ))}

        {isListView &&
          (taskList.isPending ? (
            <p className="text-sm text-[var(--dim)]">Loading tasks…</p>
          ) : taskList.error ? (
            <p className="text-sm text-[#E44332]">
              Could not load tasks. Reopen the app to retry.
            </p>
          ) : tasks.length === 0 ? (
            <div className="rounded-2xl bg-[var(--surface)] p-5 text-center">
              <p className="text-base text-[var(--text)]">
                {search.trim() ? "No tasks match your search." : emptyText(view)}
              </p>
            </div>
          ) : groups ? (
            <div className="space-y-5">
              {groups.map(([key, groupTasks]) => (
                <section key={key} aria-label={key === "__overdue" ? "Overdue" : key}>
                  <h2
                    className="mb-2 text-sm font-semibold"
                    style={{
                      color: key === "__overdue" ? "#E44332" : "var(--dim)",
                    }}
                  >
                    {key === "__overdue"
                      ? "Overdue"
                      : key === "__today"
                        ? "Today"
                        : key === addDays(today, 1)
                          ? `Tomorrow · ${fullDateLabel(key)}`
                          : fullDateLabel(key)}
                  </h2>
                  <div className="space-y-2">
                    {groupTasks.map((t) => (
                      <TaskRow
                        key={t.id}
                        task={t}
                        today={today}
                        completing={completingIds.has(t.id)}
                        onCheck={handleCheck}
                        onOpen={setEditing}
                      />
                    ))}
                  </div>
                </section>
              ))}
            </div>
          ) : (
            <div className="space-y-2">
              {tasks.map((t) => (
                <TaskRow
                  key={t.id}
                  task={t}
                  today={today}
                  completing={completingIds.has(t.id)}
                  onCheck={handleCheck}
                  onOpen={setEditing}
                />
              ))}
            </div>
          ))}
      </main>

      {/* Quick-add FAB */}
      {view.kind !== "stats" && view.kind !== "backups" && (
        <button
          type="button"
          aria-label="Add task"
          onClick={() => { setQuickAddDate(null); setQuickAddOpen(true); }}
          className="fixed bottom-24 right-4 z-30 flex h-14 w-14 items-center justify-center rounded-2xl bg-[var(--accent)] text-3xl font-light text-white shadow-lg shadow-black/50"
        >
          +
        </button>
      )}

      {/* Bottom nav */}
      <nav
        aria-label="Main views"
        className="fixed inset-x-0 bottom-0 z-30 border-t border-[var(--border)] bg-[#1A1A1A] pb-[env(safe-area-inset-bottom)]"
      >
        <div className="mx-auto flex max-w-xl">
          {navBtn(
            "inbox",
            "Inbox",
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" aria-hidden>
              <path d="M3 13l3-8h12l3 8v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-6z" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" />
              <path d="M3 13h6l2 3h2l2-3h6" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" />
            </svg>,
          )}
          {navBtn(
            "today",
            "Today",
            <span className="relative inline-flex" aria-hidden>
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none">
                <rect x="3" y="5" width="18" height="16" rx="2" stroke="currentColor" strokeWidth="1.8" />
                <path d="M3 10h18M8 3v4M16 3v4" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
              </svg>
              <span className="absolute inset-x-0 top-[9px] text-center text-[10px] font-bold leading-none">
                {dayNum}
              </span>
            </span>,
          )}
          {navBtn(
            "upcoming",
            "Upcoming",
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" aria-hidden>
              <rect x="3" y="5" width="18" height="16" rx="2" stroke="currentColor" strokeWidth="1.8" />
              <path d="M3 10h18M8 3v4M16 3v4" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
              <path d="M7 14h4M7 17.5h7" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
            </svg>,
          )}
          {navBtn(
            "browse",
            "Browse",
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" aria-hidden>
              <path d="M4 7h16M4 12h16M4 17h16" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
            </svg>,
          )}
        </div>
      </nav>

      {quickAddOpen && (
        <QuickAdd
          defaultProjectId={view.kind === "project" ? view.id : null}
          defaultDate={quickAddDate}
          onClose={() => { setQuickAddOpen(false); setQuickAddDate(null); }}
        />
      )}
      {editing && (
        <TaskEditor
          task={editing}
          projects={bootstrap.data?.projects ?? []}
          labels={bootstrap.data?.labels ?? []}
          onClose={() => setEditing(null)}
        />
      )}
    </div>
  );
}
