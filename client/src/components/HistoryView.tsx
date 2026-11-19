import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api, type ApiResponse } from "../api";
import { fullDateLabel, parseDateStr } from "../lib/dates";
import { actionFields } from "@hatch/space-sdk/fields" with { type: "macro" };
import type { Actions } from "../../../server/src/actions";

const FIELDS = actionFields<typeof Actions>();

type Bootstrap = ApiResponse<typeof api, "getBootstrap">;
const DAY_LETTERS = ["S","M","T","W","T","F","S"];

export function HistoryView({ today, bootstrap }: { today: string; bootstrap: Bootstrap | undefined }) {
  const [query, setQuery] = useState("");
  const [projectId, setProjectId] = useState<string>("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const hist = useQuery({
    queryKey: ["history", today, query, projectId, from, to],
    queryFn: () => api.getHistory({ today, query: query.trim() || undefined, projectId: projectId ? Number(projectId) : null, from: from || undefined, to: to || undefined, limit: 200 }),
  });
  const data = hist.data;
  const max30 = data ? Math.max(1, ...data.last30.map((d) => d.count)) : 1;
  const inputCls = "w-full rounded-lg bg-[var(--surface)] px-3 py-2.5 text-base text-[var(--text)] outline-none focus:ring-2 focus:ring-[var(--accent)]";
  return (
    <div className="space-y-5">
      <div className="grid grid-cols-3 gap-2">
        <div className="rounded-2xl bg-[var(--surface)] p-3 text-center"><p className="text-xl font-bold text-[var(--text)]">{data?.totalCompleted ?? "—"}</p><p className="text-[12px] text-[var(--dim)]">done, all time</p></div>
        <div className="rounded-2xl bg-[var(--surface)] p-3 text-center"><p className="text-xl font-bold text-[var(--text)]">{data?.totalCreated ?? "—"}</p><p className="text-[12px] text-[var(--dim)]">created, all time</p></div>
        <div className="rounded-2xl bg-[var(--surface)] p-3 text-center"><p className="text-xl font-bold text-[var(--text)]">{data?.bestDay?.count ?? 0}</p><p className="text-[12px] text-[var(--dim)]">best day{data?.bestDay ? ` · ${fullDateLabel(data.bestDay.date)}` : ""}</p></div>
      </div>

      <section aria-label="Last 30 days">
        <h2 className="mb-2 text-base font-semibold text-[var(--text)]">Last 30 days — completions</h2>
        <div className="flex items-end gap-[3px] rounded-2xl bg-[var(--surface)] px-3 pb-3 pt-4">
          {(data?.last30 ?? []).map((d) => (
            <div key={d.date} className="flex flex-1 flex-col items-center gap-1" title={`${d.date}: ${d.count}`}>
              <div className="w-full rounded-sm" style={{ height: `${Math.max(4, (d.count / max30) * 64)}px`, backgroundColor: d.date === today ? "#E44332" : "#4CAF7D", opacity: d.count === 0 ? 0.15 : 1 }} />
              <span className="text-[9px] text-[var(--dim)]">{DAY_LETTERS[parseDateStr(d.date).getDay()]}</span>
            </div>
          ))}
        </div>
      </section>

      <div className="space-y-2">
        <input type="search" aria-label="Search history" {...FIELDS.getHistory.query} value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Search history…" className={inputCls} />
        <div className="grid grid-cols-3 gap-2">
          <select aria-label="Filter by project" value={projectId} onChange={(e) => setProjectId(e.target.value)} className={inputCls}>
            <option value="">All projects</option>
            {(bootstrap?.projects ?? []).map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
          </select>
          <input type="date" aria-label="From date" value={from} onChange={(e) => setFrom(e.target.value)} className={inputCls} />
          <input type="date" aria-label="To date" value={to} onChange={(e) => setTo(e.target.value)} className={inputCls} />
        </div>
      </div>

      <section aria-label="Activity">
        <h2 className="mb-2 text-base font-semibold text-[var(--text)]">Full history</h2>
        {hist.isPending ? <p className="text-sm text-[var(--dim)]">Loading history…</p> : hist.isError ? <p className="text-sm text-[#E44332]">Could not load history.</p> : (data?.items.length ?? 0) === 0 ? (
          <p className="rounded-2xl bg-[var(--surface)] p-4 text-sm text-[var(--dim)]">No activity matches. Complete or create a task and it appears here — forever, not just 7 days.</p>
        ) : (
          <ul className="space-y-2">
            {data!.items.map((item) => (
              <li key={item.id} className="flex items-center justify-between gap-3 rounded-xl bg-[var(--surface)] px-3 py-2.5">
                <span className="min-w-0 truncate text-sm text-[var(--text)]">{item.type === "completed" ? "✓" : "＋"} {item.title}{item.projectName ? <span className="text-[var(--dim)]"> · #{item.projectName}</span> : null}</span>
                <span className="shrink-0 text-[13px]" style={{ color: item.type === "completed" ? "#4CAF7D" : "var(--dim)" }}>{fullDateLabel(item.date)}</span>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
