import { useMemo, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "../api";
import { actionFields } from "@hatch/space-sdk/fields" with { type: "macro" };
import type { Actions } from "../../../server/src/actions";
import { formatDuration, parseQuickAdd } from "../lib/nlp";
import { dueLabel, timeLabel, todayStr } from "../lib/dates";
import { priorityName } from "./TaskRow";

const FIELDS = actionFields<typeof Actions>();

interface Props {
  defaultProjectId: number | null;
  defaultDate?: string | null;
  onClose: () => void;
}

export function QuickAdd({ defaultProjectId, defaultDate, onClose }: Props) {
  const queryClient = useQueryClient();
  const today = todayStr();
  const [text, setText] = useState("");
  const [error, setError] = useState<string | null>(null);

  const parsed = useMemo(
    () => (text.trim() ? parseQuickAdd(text, today) : null),
    [text, today],
  );

  const add = useMutation({
    mutationFn: async (rawText: string) =>
      await api.addTask({
        rawText,
        today,
        projectId: defaultProjectId ?? undefined,
        dueDate: defaultDate ?? undefined,
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries();
      onClose();
    },
  });

  const submit = () => {
    if (!text.trim()) {
      setError("Type the task first — e.g. “Write script tomorrow at 4pm”.");
      return;
    }
    setError(null);
    add.mutate(text.trim());
  };

  return (
    <div
      className="fixed inset-0 z-40 flex items-end justify-center bg-black/60"
      role="dialog"
      aria-label="Quick add task"
      onClick={onClose}
    >
      <div
        className="w-full max-w-xl rounded-t-2xl bg-[var(--surface)] p-4 pb-[max(1rem,env(safe-area-inset-bottom))]"
        onClick={(e) => e.stopPropagation()}
      >
        <label htmlFor="quick-add-input" className="text-sm font-semibold text-[var(--text)]">
          Add task
        </label>
        <input
          id="quick-add-input"
          {...FIELDS.addTask.rawText}
          autoFocus
          value={text}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") submit();
            if (e.key === "Escape") onClose();
          }}
          placeholder="e.g. Meditate every day, or Call mum friday at 6pm #Family p1"
          className="mt-2 w-full rounded-lg bg-[var(--surface-alt)] px-3 py-3 text-base text-[var(--text)] outline-none placeholder:text-[var(--dim)] focus:ring-2 focus:ring-[var(--accent)]"
        />

        {parsed && (
          <div className="mt-3 flex flex-wrap gap-2 text-[13px]" aria-live="polite">
            {parsed.dueDate && (
              <span className="rounded-full bg-[#1d2b1d] px-2.5 py-1 font-medium text-[#4CAF7D]">
                {dueLabel(parsed.dueDate, today)}
                {parsed.dueTime ? ` · ${timeLabel(parsed.dueTime)}` : ""}
              </span>
            )}
            {parsed.recurrenceLabel && (
              <span className="rounded-full bg-[#1d2b1d] px-2.5 py-1 font-medium text-[#4CAF7D]">
                ↻ {parsed.recurrenceLabel}
              </span>
            )}
            {parsed.priority > 1 && (
              <span className="rounded-full bg-[#2b2118] px-2.5 py-1 font-medium text-[#FFA726]">
                {priorityName(parsed.priority)} priority
              </span>
            )}
            {parsed.projectName && (
              <span className="rounded-full bg-[#232323] px-2.5 py-1 text-[var(--text)]">
                #{parsed.projectName}
              </span>
            )}
            {parsed.labelNames.map((name) => (
              <span key={name} className="rounded-full bg-[#232323] px-2.5 py-1 text-[var(--text)]">
                @{name}
              </span>
            ))}
            {parsed.durationMinutes ? (
              <span className="rounded-full bg-[#232323] px-2.5 py-1 text-[var(--text)]">
                ⏱ {formatDuration(parsed.durationMinutes)}
              </span>
            ) : null}
            {!parsed.dueDate && !parsed.recurrenceLabel && (
              <span className="py-1 text-[var(--dim)]">Goes to Inbox — add “today”, “tomorrow” or “every day” to schedule it.</span>
            )}
          </div>
        )}

        {error && <p className="mt-2 text-sm text-[#E44332]">{error}</p>}

        <div className="mt-4 flex gap-2">
          <button
            type="button"
            onClick={onClose}
            className="flex-1 rounded-lg bg-[var(--surface-alt)] px-4 py-3 font-medium text-[var(--text)]"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={submit}
            disabled={add.isPending}
            className="flex-1 rounded-lg bg-[var(--accent)] px-4 py-3 font-semibold text-white disabled:opacity-50"
          >
            {add.isPending ? "Adding…" : "Add task"}
          </button>
        </div>
      </div>
    </div>
  );
}
