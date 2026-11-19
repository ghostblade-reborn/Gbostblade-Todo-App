import { useQuery } from "@tanstack/react-query";
import { api } from "../api";
import { formatDuration } from "../lib/nlp";

export function InsightsView({ today }: { today: string }) {
  const q = useQuery({ queryKey: ["insights", today], queryFn: () => api.getProjectInsights({ today }) });
  if (q.isPending) return <p className="text-sm text-[var(--dim)]">Loading insights…</p>;
  if (q.isError || !q.data) return <p className="text-sm text-[#E44332]">Could not load project insights.</p>;
  const all = [...q.data.projects];
  return (
    <div className="space-y-4">
      <p className="text-sm text-[var(--dim)]">Progress, 4-week trend, overdue and planned time per project.</p>
      {all.length === 0 && <p className="rounded-2xl bg-[var(--surface)] p-4 text-sm text-[var(--dim)]">No projects yet — create one in Browse and insights appear here.</p>}
      {all.map((p) => {
        const maxTrend = Math.max(1, ...p.weeklyTrend.map((w) => w.count));
        return (
          <section key={p.id} aria-label={`Insights for ${p.name}`} className="rounded-2xl bg-[var(--surface)] p-4">
            <div className="flex items-center gap-2">
              <span className="h-3 w-3 rounded-full" style={{ backgroundColor: p.color }} aria-hidden />
              <h2 className="flex-1 text-base font-bold text-[var(--text)]">{p.name}</h2>
              <span className="text-sm font-bold text-[var(--text)]">{p.pct}%</span>
            </div>
            <div className="mt-2 h-2 overflow-hidden rounded-full bg-[var(--surface-alt)]"><div className="h-full rounded-full bg-[#4CAF7D]" style={{ width: `${p.pct}%` }} /></div>
            <p className="mt-2 text-[13px] text-[var(--dim)]">{p.done} done · {p.active} open · {p.total} total{p.overdue > 0 ? ` · ${p.overdue} overdue` : ""}</p>
            <div className="mt-3 grid grid-cols-3 gap-2 text-center">
              <div className="rounded-lg bg-[var(--surface-alt)] p-2"><p className="text-sm font-bold text-[var(--text)]">{p.openDurationMinutes > 0 ? formatDuration(p.openDurationMinutes) : "—"}</p><p className="text-[11px] text-[var(--dim)]">planned (open)</p></div>
              <div className="rounded-lg bg-[var(--surface-alt)] p-2"><p className="text-sm font-bold text-[var(--text)]">{p.bestWeekday ?? "—"}</p><p className="text-[11px] text-[var(--dim)]">most productive</p></div>
              <div className="rounded-lg bg-[var(--surface-alt)] p-2"><p className="text-sm font-bold text-[var(--text)]">{p.weeklyTrend.reduce((s, w) => s + w.count, 0)}</p><p className="text-[11px] text-[var(--dim)]">done · 4 weeks</p></div>
            </div>
            <div className="mt-3 flex items-end gap-2" aria-label="Last 4 weeks trend">
              {p.weeklyTrend.map((w) => (
                <div key={w.weekStart} className="flex flex-1 flex-col items-center gap-1">
                  <span className="text-[11px] text-[var(--dim)]">{w.count || ""}</span>
                  <div className="w-full rounded-md bg-[#4CAF7D]" style={{ height: `${Math.max(5, (w.count / maxTrend) * 36)}px`, opacity: w.count === 0 ? 0.18 : 1 }} />
                  <span className="text-[10px] text-[var(--dim)]">{w.weekStart.slice(5)}</span>
                </div>
              ))}
            </div>
          </section>
        );
      })}
      <section aria-label="Inbox insights" className="rounded-2xl bg-[var(--surface)] p-4">
        <h2 className="text-base font-bold text-[var(--text)]">Inbox (no project)</h2>
        <p className="mt-1 text-[13px] text-[var(--dim)]">{q.data.inbox.done} done · {q.data.inbox.active} open · {q.data.inbox.pct}% complete{q.data.inbox.overdue > 0 ? ` · ${q.data.inbox.overdue} overdue` : ""}{q.data.inbox.openDurationMinutes > 0 ? ` · ${formatDuration(q.data.inbox.openDurationMinutes)} planned` : ""}</p>
      </section>
    </div>
  );
}
