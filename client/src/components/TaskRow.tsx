import type { ApiResponse } from "../api";
import type { api } from "../api";
import { dueLabel, timeLabel } from "../lib/dates";
import { formatDuration } from "../lib/nlp";

export type Task = ApiResponse<typeof api, "listTasks">["tasks"][number];

export const PRIORITY_COLORS: Record<number, string> = {
  4: "#E44332",
  3: "#FFA726",
  2: "#42A5F5",
  1: "#8A8A8A",
};

export function priorityName(priority: number): string {
  return priority >= 4 ? "P1" : priority === 3 ? "P2" : priority === 2 ? "P3" : "P4";
}

/** Compact recurrence text for the meta line: "Daily", "Fridays", … */
function shortRecurrence(rule: string | null, label: string | null): string | null {
  if (!rule) return null;
  if (rule === "daily") return "Daily";
  if (rule === "weekdays") return "Weekdays";
  if (rule === "weekly") return "Weekly";
  if (rule === "monthly") return "Monthly";
  if (rule.startsWith("weekly:")) {
    const name = label?.replace(/^Every /, "") ?? "";
    return name ? `${name}s` : "Weekly";
  }
  return label;
}

interface Props {
  task: Task;
  today: string;
  completing: boolean;
  onCheck: (task: Task) => void;
  onOpen: (task: Task) => void;
}

export function TaskRow({ task, today, completing, onCheck, onOpen }: Props) {
  const color = PRIORITY_COLORS[task.priority] ?? "#8A8A8A";
  const overdue = task.dueDate != null && task.dueDate < today;
  const isToday = task.dueDate === today;
  const done = completing || task.status === "completed";

  return (
    <div
      className={`flex items-start gap-3 rounded-xl px-3 py-3 transition-colors ${
        completing ? "bg-[#22301f]" : "bg-[var(--surface)]"
      }`}
    >
      <button
        type="button"
        aria-label={
          task.status === "completed"
            ? `Mark "${task.title}" not done`
            : `Complete "${task.title}"`
        }
        onClick={() => onCheck(task)}
        className="mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-full transition-all"
        style={{
          border: `2px solid ${color}`,
          backgroundColor: done ? color : "transparent",
        }}
      >
        {done && (
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" aria-hidden>
            <path
              d="M4 12.5l5 5L20 6.5"
              stroke="#fff"
              strokeWidth="3.2"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
        )}
      </button>

      <button
        type="button"
        aria-label={`Edit "${task.title}"`}
        onClick={() => onOpen(task)}
        className="min-w-0 flex-1 text-left"
      >
        <span
          className={`block text-base leading-snug ${
            done ? "text-[var(--dim)] line-through" : "text-[var(--text)]"
          }`}
        >
          {task.title}
        </span>

        {(task.dueDate || task.recurrenceLabel || task.projectName ||
          task.labels.length > 0 || task.priority > 1 || task.notes || task.durationMinutes || task.subtaskTotal > 0 || (task.reminders?.length ?? 0) > 0) && (
          <span className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-[13px]">
            {task.dueDate && (
              <span
                className="inline-flex items-center gap-1"
                style={{
                  color: overdue ? "#E44332" : isToday ? "#4CAF7D" : "var(--dim)",
                }}
              >
                <svg width="13" height="13" viewBox="0 0 24 24" fill="none" aria-hidden>
                  <rect x="3" y="5" width="18" height="16" rx="2" stroke="currentColor" strokeWidth="2" />
                  <path d="M3 10h18M8 3v4M16 3v4" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
                </svg>
                {dueLabel(task.dueDate, today)}
                {task.dueTime ? ` ${timeLabel(task.dueTime)}` : ""}
                {task.recurrenceLabel && (
                  <span className="inline-flex items-center gap-0.5">
                    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" aria-hidden>
                      <path d="M20 12a8 8 0 1 1-2.34-5.66M20 3v4h-4" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" />
                    </svg>
                    {shortRecurrence(task.recurrenceRule, task.recurrenceLabel)}
                  </span>
                )}
              </span>
            )}
            {!task.dueDate && task.recurrenceLabel && (
              <span className="text-[var(--dim)]">{task.recurrenceLabel}</span>
            )}
            {task.priority > 1 && (
              <span style={{ color }} className="font-semibold">
                {priorityName(task.priority)}
              </span>
            )}
            {task.projectName && (
              <span className="text-[var(--dim)]">#{task.projectName}</span>
            )}
            {task.labels.map((l) => (
              <span key={l.id} style={{ color: l.color }}>
                @{l.name}
              </span>
            ))}
            {task.durationMinutes ? (
              <span className="inline-flex items-center gap-1 text-[var(--dim)]">⏱ {formatDuration(task.durationMinutes)}</span>
            ) : null}
            {task.subtaskTotal > 0 ? (
              <span className="text-[var(--dim)]">☑ {task.subtaskDone}/{task.subtaskTotal}</span>
            ) : null}
            {(task.reminders?.length ?? 0) > 0 ? (
              <span className="text-[#FFA726]">🔔 {task.reminders.length}</span>
            ) : null}
          </span>
        )}
        {task.notes && (
          <span className="mt-0.5 block truncate text-[13px] text-[var(--dim)]">
            {task.notes}
          </span>
        )}
      </button>
    </div>
  );
}
