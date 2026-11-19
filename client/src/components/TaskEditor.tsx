import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, type ApiResponse } from "../api";
import { actionFields } from "@hatch/space-sdk/fields" with { type: "macro" };
import type { Actions } from "../../../server/src/actions";
import type { Task } from "./TaskRow";
import { PRIORITY_COLORS, priorityName } from "./TaskRow";
import { formatDuration, recurrenceLabel } from "../lib/nlp";
import { todayStr } from "../lib/dates";

const DURATION_OPTIONS: { value: string; label: string }[] = [
  { value: "", label: "No duration" },
  { value: "15", label: "15 min" }, { value: "30", label: "30 min" },
  { value: "45", label: "45 min" }, { value: "60", label: "1 hour" },
  { value: "90", label: "1.5 hours" }, { value: "120", label: "2 hours" },
  { value: "180", label: "3 hours" }, { value: "240", label: "4 hours" },
];
const REMINDER_PRESETS: { offset: number; label: string }[] = [
  { offset: 0, label: "At time" }, { offset: 10, label: "10 min before" },
  { offset: 30, label: "30 min before" }, { offset: 60, label: "1 hour before" },
  { offset: 1440, label: "1 day before" },
];

const FIELDS = actionFields<typeof Actions>();

type Project = ApiResponse<typeof api, "getBootstrap">["projects"][number];
type Label = ApiResponse<typeof api, "getBootstrap">["labels"][number];

const RECURRENCE_OPTIONS: { value: string; label: string }[] = [
  { value: "", label: "Does not repeat" },
  { value: "daily", label: "Every day" },
  { value: "weekdays", label: "Every weekday (Mon–Fri)" },
  { value: "weekly", label: "Every week" },
  { value: "weekly:1", label: "Every Monday" },
  { value: "weekly:2", label: "Every Tuesday" },
  { value: "weekly:3", label: "Every Wednesday" },
  { value: "weekly:4", label: "Every Thursday" },
  { value: "weekly:5", label: "Every Friday" },
  { value: "weekly:6", label: "Every Saturday" },
  { value: "weekly:0", label: "Every Sunday" },
  { value: "interval:2", label: "Every 2 days" },
  { value: "interval:3", label: "Every 3 days" },
  { value: "monthly", label: "Every month" },
];

interface Props {
  task: Task;
  projects: Project[];
  labels: Label[];
  onClose: () => void;
}

export function TaskEditor({ task, projects, labels, onClose }: Props) {
  const queryClient = useQueryClient();
  const [title, setTitle] = useState(task.title);
  const [notes, setNotes] = useState(task.notes ?? "");
  const [projectId, setProjectId] = useState<string>(
    task.projectId != null ? String(task.projectId) : "",
  );
  const [priority, setPriority] = useState(task.priority);
  const [dueDate, setDueDate] = useState(task.dueDate ?? "");
  const [dueTime, setDueTime] = useState(task.dueTime ?? "");
  const [recurrence, setRecurrence] = useState(task.recurrenceRule ?? "");
  const [labelIds, setLabelIds] = useState<number[]>(
    task.labels.map((l) => l.id),
  );
  const [titleError, setTitleError] = useState<string | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [duration, setDuration] = useState(task.durationMinutes != null ? String(task.durationMinutes) : "");
  const [customDuration, setCustomDuration] = useState("");
  const [reminderPreset, setReminderPreset] = useState("10");
  const [customReminder, setCustomReminder] = useState("");
  const [newSubtask, setNewSubtask] = useState("");
  const [suggested, setSuggested] = useState<string[]>([]);
  const [suggestedChecked, setSuggestedChecked] = useState<Set<number>>(new Set());
  const today = todayStr();
  const isSubtask = task.parentId != null;

  const invalidate = () => void queryClient.invalidateQueries();

  const subtasks = useQuery({
    queryKey: ["subtasks", task.id],
    queryFn: () => api.listSubtasks({ parentId: task.id }),
    enabled: !isSubtask,
  });
  const addReminder = useMutation({
    mutationFn: async (args: { offset: number; label: string }) => await api.addReminder({ taskId: task.id, offsetMinutes: args.offset, label: args.label }),
    onSuccess: invalidate,
  });
  const deleteReminder = useMutation({
    mutationFn: async (id: number) => await api.deleteReminder({ id }),
    onSuccess: invalidate,
  });
  const addSub = useMutation({
    mutationFn: async (rawText: string) => await api.addTask({ rawText, today, parentId: task.id }),
    onSuccess: () => { setNewSubtask(""); invalidate(); },
  });
  const toggleSub = useMutation({
    mutationFn: async (sub: Task) => sub.status === "completed" ? await api.uncompleteTask({ id: sub.id }) : await api.completeTask({ id: sub.id, today }),
    onSuccess: invalidate,
  });
  const deleteSub = useMutation({
    mutationFn: async (id: number) => await api.deleteTask({ id }),
    onSuccess: invalidate,
  });
  const breakdown = useMutation({
    mutationFn: async () => await api.assistBreakdown({ title: title.trim() || task.title }),
    onSuccess: (res) => { setSuggested(res.steps); setSuggestedChecked(new Set(res.steps.map((_, i) => i))); },
  });
  const saveSuggested = useMutation({
    mutationFn: async (titles: string[]) => await api.addSubtasks({ parentId: task.id, titles, today }),
    onSuccess: () => { setSuggested([]); invalidate(); },
  });

  const save = useMutation({
    mutationFn: async () =>
      await api.updateTask({
        id: task.id,
        title: title.trim(),
        notes: notes.trim() ? notes.trim() : null,
        projectId: projectId ? Number(projectId) : null,
        priority,
        dueDate: dueDate ? dueDate : null,
        dueTime: dueTime ? dueTime : null,
        recurrenceRule: recurrence ? recurrence : null,
        durationMinutes: duration === "custom" ? (customDuration ? Number(customDuration) : null) : (duration ? Number(duration) : null),
        labelIds,
      }),
    onSuccess: () => {
      invalidate();
      onClose();
    },
  });

  const remove = useMutation({
    mutationFn: async () => await api.deleteTask({ id: task.id }),
    onSuccess: () => {
      invalidate();
      onClose();
    },
  });

  const inputCls =
    "w-full rounded-lg bg-[var(--surface-alt)] px-3 py-2.5 text-base text-[var(--text)] outline-none focus:ring-2 focus:ring-[var(--accent)]";
  const labelCls = "mt-4 block text-[13px] font-semibold text-[var(--dim)]";

  return (
    <div
      className="fixed inset-0 z-40 flex items-end justify-center bg-black/60"
      role="dialog"
      aria-label="Edit task"
      onClick={onClose}
    >
      <div
        className="max-h-[92vh] w-full max-w-xl overflow-y-auto rounded-t-2xl bg-[var(--surface)] p-4 pb-[max(1rem,env(safe-area-inset-bottom))]"
        onClick={(e) => e.stopPropagation()}
      >
        <label htmlFor="edit-title" className="text-sm font-semibold text-[var(--text)]">
          Task
        </label>
        <input
          id="edit-title"
          {...FIELDS.updateTask.title}
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          className={`${inputCls} mt-1`}
        />
        {titleError && <p className="mt-1 text-sm text-[#E44332]">{titleError}</p>}

        <label htmlFor="edit-notes" className={labelCls}>
          Notes
        </label>
        <textarea
          id="edit-notes"
          {...FIELDS.updateTask.notes}
          value={notes}
          onChange={(e) => setNotes(e.target.value)}
          rows={2}
          placeholder="Details, links, anything…"
          className={inputCls}
        />

        <div className="grid grid-cols-2 gap-3">
          <div>
            <label htmlFor="edit-date" className={labelCls}>
              Due date
            </label>
            <input
              id="edit-date"
              type="date"
              value={dueDate}
              onChange={(e) => setDueDate(e.target.value)}
              className={inputCls}
            />
          </div>
          <div>
            <label htmlFor="edit-time" className={labelCls}>
              Time
            </label>
            <input
              id="edit-time"
              type="time"
              value={dueTime}
              onChange={(e) => setDueTime(e.target.value)}
              className={inputCls}
            />
          </div>
        </div>

        <label htmlFor="edit-recurrence" className={labelCls}>
          Repeat
        </label>
        <select
          id="edit-recurrence"
          value={recurrence}
          onChange={(e) => setRecurrence(e.target.value)}
          className={inputCls}
        >
          {RECURRENCE_OPTIONS.map((o) => (
            <option key={o.value || "none"} value={o.value}>
              {o.label}
            </option>
          ))}
          {recurrence &&
            !RECURRENCE_OPTIONS.some((o) => o.value === recurrence) && (
              <option value={recurrence}>{recurrenceLabel(recurrence)}</option>
            )}
        </select>

        <label htmlFor="edit-project" className={labelCls}>
          Project
        </label>
        <select
          id="edit-project"
          value={projectId}
          onChange={(e) => setProjectId(e.target.value)}
          className={inputCls}
        >
          <option value="">Inbox</option>
          {projects.map((p) => (
            <option key={p.id} value={p.id}>
              {p.name}
            </option>
          ))}
        </select>

        <span className={labelCls}>Priority</span>
        <div className="mt-1 flex gap-2" role="group" aria-label="Priority">
          {[4, 3, 2, 1].map((p) => (
            <button
              key={p}
              type="button"
              aria-pressed={priority === p}
              onClick={() => setPriority(p)}
              className="flex-1 rounded-lg px-3 py-2.5 font-semibold"
              style={{
                backgroundColor:
                  priority === p ? PRIORITY_COLORS[p] : "var(--surface-alt)",
                color: priority === p ? "#fff" : PRIORITY_COLORS[p],
              }}
            >
              {priorityName(p)}
            </button>
          ))}
        </div>

        {labels.length > 0 && (
          <>
            <span className={labelCls}>Labels</span>
            <div className="mt-1 flex flex-wrap gap-2">
              {labels.map((l) => {
                const on = labelIds.includes(l.id);
                return (
                  <button
                    key={l.id}
                    type="button"
                    aria-pressed={on}
                    onClick={() =>
                      setLabelIds((prev) =>
                        on ? prev.filter((x) => x !== l.id) : [...prev, l.id],
                      )
                    }
                    className="rounded-full px-3 py-1.5 text-sm font-medium"
                    style={{
                      backgroundColor: on ? l.color : "var(--surface-alt)",
                      color: on ? "#fff" : l.color,
                    }}
                  >
                    @{l.name}
                  </button>
                );
              })}
            </div>
          </>
        )}

        {/* Pro: Duration */}
        <label htmlFor="edit-duration" className={labelCls}>Duration ⏱</label>
        <select id="edit-duration" value={duration} onChange={(e) => setDuration(e.target.value)} className={inputCls}>
          {DURATION_OPTIONS.map((o) => <option key={o.value || "none"} value={o.value}>{o.label}</option>)}
          <option value="custom">Custom minutes…</option>
          {duration && !DURATION_OPTIONS.some((o) => o.value === duration) && duration !== "custom" && <option value={duration}>{formatDuration(Number(duration))}</option>}
        </select>
        {duration === "custom" && (
          <input type="number" aria-label="Custom duration in minutes" {...FIELDS.updateTask.durationMinutes} value={customDuration} onChange={(e) => setCustomDuration(e.target.value)} placeholder="e.g. 25" className={`${inputCls} mt-2`} />
        )}

        {/* Pro: Reminders */}
        {!isSubtask && (
          <>
            <span className={labelCls}>Reminders 🔔 <span className="font-normal normal-case">(fire while the app is open)</span></span>
            {(task.reminders ?? []).length === 0 && <p className="mt-1 text-sm text-[var(--dim)]">No reminders yet. Tasks with a date use 9:00 AM if no time is set.</p>}
            <ul className="mt-1 space-y-1.5">
              {(task.reminders ?? []).map((r) => (
                <li key={r.id} className="flex items-center justify-between rounded-lg bg-[var(--surface-alt)] px-3 py-2 text-sm text-[var(--text)]">
                  <span>🔔 {r.label}</span>
                  <button type="button" aria-label={`Delete reminder ${r.label}`} onClick={() => deleteReminder.mutate(r.id)} className="px-2 text-base text-[var(--dim)]">×</button>
                </li>
              ))}
            </ul>
            <div className="mt-2 flex gap-2">
              <select aria-label="Reminder preset" value={reminderPreset} onChange={(e) => setReminderPreset(e.target.value)} className="min-w-0 flex-1 rounded-lg bg-[var(--surface-alt)] px-3 py-2.5 text-base text-[var(--text)] outline-none">
                {REMINDER_PRESETS.map((p) => <option key={p.offset} value={String(p.offset)}>{p.label}</option>)}
                <option value="custom">Custom…</option>
              </select>
              <button type="button" disabled={addReminder.isPending} onClick={() => {
                if (reminderPreset === "custom") {
                  const n = Number(customReminder);
                  if (!n || n <= 0) return;
                  addReminder.mutate({ offset: n, label: `Custom: ${n} min before` });
                } else {
                  const preset = REMINDER_PRESETS.find((p) => String(p.offset) === reminderPreset) ?? REMINDER_PRESETS[1]!;
                  addReminder.mutate({ offset: preset.offset, label: preset.label });
                }
              }} className="rounded-lg bg-[var(--surface-alt)] px-4 py-2.5 font-semibold text-[var(--text)] disabled:opacity-50">Add</button>
            </div>
            {reminderPreset === "custom" && <input type="number" aria-label="Custom reminder minutes before" {...FIELDS.addReminder.offsetMinutes} value={customReminder} onChange={(e) => setCustomReminder(e.target.value)} placeholder="Minutes before, e.g. 45" className={`${inputCls} mt-2`} />}
          </>
        )}

        {/* Pro: Subtasks + Break it down */}
        {!isSubtask && (
          <>
            <span className={labelCls}>Subtasks {task.subtaskTotal > 0 ? `— ${task.subtaskDone}/${task.subtaskTotal} done` : ""}</span>
            <ul className="mt-1 space-y-1.5">
              {(subtasks.data?.tasks ?? []).map((sub) => (
                <li key={sub.id} className="flex items-center gap-2 rounded-lg bg-[var(--surface-alt)] px-3 py-2">
                  <input type="checkbox" aria-label={`Complete subtask "${sub.title}"`} checked={sub.status === "completed"} onChange={() => toggleSub.mutate(sub)} className="h-5 w-5 shrink-0 accent-[#E44332]" />
                  <span className={`min-w-0 flex-1 text-sm ${sub.status === "completed" ? "text-[var(--dim)] line-through" : "text-[var(--text)]"}`}>{sub.title}</span>
                  <button type="button" aria-label={`Delete subtask "${sub.title}"`} onClick={() => deleteSub.mutate(sub.id)} className="px-1 text-base text-[var(--dim)]">×</button>
                </li>
              ))}
            </ul>
            <div className="mt-2 flex gap-2">
              <input aria-label="New subtask" {...FIELDS.addTask.rawText} value={newSubtask} onChange={(e) => setNewSubtask(e.target.value)} onKeyDown={(e) => { if (e.key === "Enter" && newSubtask.trim()) addSub.mutate(newSubtask.trim()); }} placeholder="Add a subtask…" className="min-w-0 flex-1 rounded-lg bg-[var(--surface-alt)] px-3 py-2.5 text-base text-[var(--text)] outline-none placeholder:text-[var(--dim)]" />
              <button type="button" disabled={!newSubtask.trim() || addSub.isPending} onClick={() => addSub.mutate(newSubtask.trim())} className="rounded-lg bg-[var(--surface-alt)] px-4 py-2.5 font-semibold text-[var(--text)] disabled:opacity-50">Add</button>
            </div>
            <button type="button" disabled={breakdown.isPending} onClick={() => breakdown.mutate()} className="mt-2 w-full rounded-lg border border-[#FFA726]/50 bg-[#2b2118] px-4 py-2.5 font-semibold text-[#FFA726] disabled:opacity-50">{breakdown.isPending ? "Thinking…" : "✨ Break it down — suggest subtasks"}</button>
            {suggested.length > 0 && (
              <div className="mt-2 rounded-lg bg-[var(--surface-alt)] p-3">
                <p className="text-sm font-semibold text-[var(--text)]">Suggested — uncheck any you don't want:</p>
                <ul className="mt-2 space-y-1.5">
                  {suggested.map((s, i) => (
                    <li key={`${s}-${i}`} className="flex items-center gap-2 text-sm text-[var(--text)]">
                      <input type="checkbox" aria-label={`Include step "${s}"`} checked={suggestedChecked.has(i)} onChange={(e) => setSuggestedChecked((prev) => { const n = new Set(prev); if (e.target.checked) n.add(i); else n.delete(i); return n; })} className="h-5 w-5 accent-[#E44332]" />
                      {s}
                    </li>
                  ))}
                </ul>
                <button type="button" disabled={suggestedChecked.size === 0 || saveSuggested.isPending} onClick={() => saveSuggested.mutate(suggested.filter((_, i) => suggestedChecked.has(i)))} className="mt-2 w-full rounded-lg bg-[var(--accent)] px-4 py-2.5 font-semibold text-white disabled:opacity-50">Add {suggestedChecked.size} as subtasks</button>
              </div>
            )}
          </>
        )}

        <div className="mt-6 flex gap-2">
          <button
            type="button"
            onClick={onClose}
            className="flex-1 rounded-lg bg-[var(--surface-alt)] px-4 py-3 font-medium text-[var(--text)]"
          >
            Cancel
          </button>
          <button
            type="button"
            disabled={save.isPending}
            onClick={() => {
              if (!title.trim()) {
                setTitleError("The task needs a title.");
                return;
              }
              setTitleError(null);
              save.mutate();
            }}
            className="flex-1 rounded-lg bg-[var(--accent)] px-4 py-3 font-semibold text-white disabled:opacity-50"
          >
            {save.isPending ? "Saving…" : "Save"}
          </button>
        </div>

        {confirmDelete ? (
          <div className="mt-3 flex gap-2">
            <button
              type="button"
              onClick={() => setConfirmDelete(false)}
              className="flex-1 rounded-lg bg-[var(--surface-alt)] px-4 py-3 font-medium text-[var(--text)]"
            >
              Keep task
            </button>
            <button
              type="button"
              disabled={remove.isPending}
              onClick={() => remove.mutate()}
              className="flex-1 rounded-lg bg-[#7a1f16] px-4 py-3 font-semibold text-white disabled:opacity-50"
            >
              {remove.isPending ? "Deleting…" : "Yes, delete it"}
            </button>
          </div>
        ) : (
          <button
            type="button"
            onClick={() => setConfirmDelete(true)}
            className="mt-3 w-full rounded-lg px-4 py-3 font-medium text-[#E44332]"
          >
            Delete task
          </button>
        )}
      </div>
    </div>
  );
}
