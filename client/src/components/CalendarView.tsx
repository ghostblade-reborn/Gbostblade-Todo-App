import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "../api";
import { addDays, parseDateStr, todayStr } from "../lib/dates";
import type { Task } from "./TaskRow";
import { TaskRow } from "./TaskRow";

const MONTH_NAMES = ["January","February","March","April","May","June","July","August","September","October","November","December"];
const DOW = ["Su","Mo","Tu","We","Th","Fr","Sa"];

interface Props {
  today: string;
  onOpenTask: (t: Task) => void;
  onCheckTask: (t: Task) => void;
  completingIds: Set<number>;
  onAddOnDate: (date: string) => void;
}

export function CalendarView({ today, onOpenTask, onCheckTask, completingIds, onAddOnDate }: Props) {
  const now = new Date();
  const [cursor, setCursor] = useState({ y: now.getFullYear(), m: now.getMonth() });
  const [mode, setMode] = useState<"month" | "week">("month");
  const [selected, setSelected] = useState<string>(today);
  const monthStr = `${cursor.y}-${String(cursor.m + 1).padStart(2, "0")}`;

  const cal = useQuery({ queryKey: ["calendar", monthStr], queryFn: () => api.getCalendar({ month: monthStr, today }) });
  const dayTasks = useQuery({ queryKey: ["tasks", { view: "day", date: selected, today }], queryFn: () => api.listTasks({ view: "day", date: selected, today }) });

  const countByDate = useMemo(() => {
    const m = new Map<string, number>();
    for (const d of cal.data?.days ?? []) m.set(d.date, d.count);
    return m;
  }, [cal.data]);

  const cells = useMemo(() => {
    if (mode === "week") {
      // week containing selected date
      const sel = parseDateStr(selected);
      const start = addDays(selected, -sel.getDay());
      return Array.from({ length: 7 }, (_, i) => addDays(start, i));
    }
    const first = new Date(cursor.y, cursor.m, 1);
    const startDow = first.getDay();
    const daysInMonth = new Date(cursor.y, cursor.m + 1, 0).getDate();
    const out: (string | null)[] = [];
    for (let i = 0; i < startDow; i++) out.push(null);
    for (let d = 1; d <= daysInMonth; d++) out.push(`${cursor.y}-${String(cursor.m + 1).padStart(2, "0")}-${String(d).padStart(2, "0")}`);
    return out;
  }, [cursor, mode, selected]);

  const shift = (dir: number) => {
    if (mode === "week") { setSelected(addDays(selected, dir * 7)); const d = parseDateStr(addDays(selected, dir * 7)); setCursor({ y: d.getFullYear(), m: d.getMonth() }); return; }
    const nm = cursor.m + dir;
    setCursor({ y: cursor.y + Math.floor(nm / 12), m: ((nm % 12) + 12) % 12 });
  };

  return (
    <div>
      <div className="flex items-center justify-between">
        <button type="button" aria-label="Previous period" onClick={() => shift(-1)} className="px-3 py-2 text-xl text-[var(--text)]">‹</button>
        <h2 className="text-base font-bold text-[var(--text)]">{MONTH_NAMES[cursor.m]} {cursor.y}</h2>
        <button type="button" aria-label="Next period" onClick={() => shift(1)} className="px-3 py-2 text-xl text-[var(--text)]">›</button>
      </div>
      <div className="mt-2 flex gap-2" role="group" aria-label="Calendar mode">
        {(["month", "week"] as const).map((m) => (
          <button key={m} type="button" aria-pressed={mode === m} onClick={() => setMode(m)} className="flex-1 rounded-lg px-3 py-2 text-sm font-semibold capitalize" style={{ backgroundColor: mode === m ? "var(--accent)" : "var(--surface)", color: mode === m ? "#fff" : "var(--text)" }}>{m} view</button>
        ))}
      </div>

      <div className="mt-3 grid grid-cols-7 gap-1 text-center text-[11px] font-semibold text-[var(--dim)]">
        {DOW.map((d) => <span key={d}>{d}</span>)}
      </div>
      <div className="mt-1 grid grid-cols-7 gap-1">
        {cells.map((dateStr, i) => {
          if (!dateStr) return <span key={`e-${i}`} />;
          const count = countByDate.get(dateStr) ?? 0;
          const isToday = dateStr === today;
          const isSel = dateStr === selected;
          const dayNum = Number(dateStr.slice(8));
          return (
            <button key={dateStr} type="button" aria-label={`${dateStr}, ${count} tasks`} aria-pressed={isSel} onClick={() => setSelected(dateStr)}
              className="flex min-h-11 flex-col items-center justify-center rounded-lg py-1"
              style={{ backgroundColor: isSel ? "var(--accent)" : "var(--surface)", color: isSel ? "#fff" : "var(--text)", outline: isToday && !isSel ? "1.5px solid var(--accent)" : "none" }}>
              <span className="text-sm font-semibold leading-none">{dayNum}</span>
              {count > 0 && <span className="mt-1 flex gap-0.5">{Array.from({ length: Math.min(3, count) }).map((_, j) => <span key={j} className="h-1.5 w-1.5 rounded-full" style={{ backgroundColor: isSel ? "#fff" : "#E44332" }} />)}<span className="text-[10px] leading-none">{count > 3 ? `+${count - 3}` : ""}</span></span>}
            </button>
          );
        })}
      </div>
      <p className="mt-2 text-[12px] text-[var(--dim)]">Recurring tasks show on their current occurrence. Tap a day to see its tasks.</p>

      <div className="mt-4 flex items-center justify-between">
        <h3 className="text-sm font-semibold text-[var(--text)]">{selected === today ? "Today" : selected} — {dayTasks.data?.tasks.length ?? 0} tasks</h3>
        <button type="button" onClick={() => onAddOnDate(selected)} className="rounded-lg bg-[var(--accent)] px-3 py-2 text-sm font-semibold text-white">+ Add on this day</button>
      </div>
      <div className="mt-2 space-y-2">
        {(dayTasks.data?.tasks ?? []).map((t) => (
          <TaskRow key={t.id} task={t} today={today} completing={completingIds.has(t.id)} onCheck={onCheckTask} onOpen={onOpenTask} />
        ))}
        {dayTasks.data && dayTasks.data.tasks.length === 0 && <p className="rounded-xl bg-[var(--surface)] p-4 text-sm text-[var(--dim)]">No tasks this day. Add one above — it'll be due {selected}.</p>}
      </div>
      <span className="hidden">{todayStr()}</span>
    </div>
  );
}
