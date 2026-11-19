import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../api";
import { todayStr } from "../lib/dates";

export function ReminderBanner() {
  const queryClient = useQueryClient();
  const [tick, setTick] = useState(0);
  useEffect(() => {
    const id = setInterval(() => setTick((t) => t + 1), 30000);
    return () => clearInterval(id);
  }, []);
  const due = useQuery({
    queryKey: ["dueReminders", tick],
    queryFn: () => api.getDueReminders({ nowIso: new Date().toISOString(), today: todayStr() }),
  });
  const dismiss = useMutation({ mutationFn: async (id: number) => await api.dismissReminder({ id }), onSuccess: () => void queryClient.invalidateQueries() });
  const snooze = useMutation({ mutationFn: async (id: number) => await api.snoozeReminder({ id, minutes: 10 }), onSuccess: () => void queryClient.invalidateQueries() });

  const items = due.data?.due ?? [];
  if (items.length === 0) return null;
  return (
    <div className="mx-4 mt-3 space-y-2" role="alert" aria-label="Due reminders">
      <p className="text-[12px] text-[var(--dim)]">🔔 Reminders fire while the app is open.</p>
      {items.slice(0, 3).map((r) => (
        <div key={r.reminderId} className="flex items-start gap-3 rounded-xl border border-[#FFA726]/40 bg-[#2b2118] p-3">
          <span aria-hidden className="text-lg">🔔</span>
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm font-semibold text-[var(--text)]">{r.taskTitle}</p>
            <p className="text-[13px] text-[var(--dim)]">{r.label}{r.dueTime ? ` · due ${r.dueTime}` : ""}</p>
            <div className="mt-2 flex gap-2">
              <button type="button" onClick={() => snooze.mutate(r.reminderId)} className="rounded-lg bg-[var(--surface-alt)] px-3 py-1.5 text-sm font-medium text-[var(--text)]">Snooze 10m</button>
              <button type="button" onClick={() => dismiss.mutate(r.reminderId)} className="rounded-lg bg-[var(--accent)] px-3 py-1.5 text-sm font-semibold text-white">Dismiss</button>
            </div>
          </div>
        </div>
      ))}
    </div>
  );
}
