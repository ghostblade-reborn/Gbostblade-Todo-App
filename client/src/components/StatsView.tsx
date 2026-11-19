import type { ApiResponse } from "../api";
import type { api } from "../api";
import { addDays, fullDateLabel, parseDateStr } from "../lib/dates";

type Stats = ApiResponse<typeof api, "getStats">;

const DAY_LETTERS = ["S", "M", "T", "W", "T", "F", "S"];

export function StatsView({ stats, today }: { stats: Stats; today: string }) {
  const totalForRing = stats.completedToday + stats.dueTodayTotal;
  const pct =
    totalForRing > 0
      ? Math.round((stats.completedToday / totalForRing) * 100)
      : 0;
  const ringR = 34;
  const ringC = 2 * Math.PI * ringR;
  const maxWeekly = Math.max(1, ...stats.weekly.map((d) => d.count));

  return (
    <div className="space-y-6">
      {/* Today ring + headline numbers */}
      <div className="flex items-center gap-5 rounded-2xl bg-[var(--surface)] p-4">
        <div className="relative h-[88px] w-[88px] shrink-0" role="img" aria-label={`Today: ${stats.completedToday} of ${totalForRing} tasks done`}>
          <svg width="88" height="88" viewBox="0 0 88 88">
            <circle cx="44" cy="44" r={ringR} fill="none" stroke="#2c2c2c" strokeWidth="8" />
            <circle
              cx="44"
              cy="44"
              r={ringR}
              fill="none"
              stroke="#4CAF7D"
              strokeWidth="8"
              strokeLinecap="round"
              strokeDasharray={ringC}
              strokeDashoffset={ringC - (ringC * pct) / 100}
              transform="rotate(-90 44 44)"
            />
          </svg>
          <div className="absolute inset-0 flex flex-col items-center justify-center">
            <span className="text-xl font-bold text-[var(--text)]">
              {stats.completedToday}
            </span>
            <span className="text-[11px] text-[var(--dim)]">
              of {totalForRing} today
            </span>
          </div>
        </div>
        <div className="grid flex-1 grid-cols-2 gap-3">
          <div>
            <p className="text-2xl font-bold text-[var(--text)]">{stats.streak}</p>
            <p className="text-[13px] text-[var(--dim)]">
              day streak {stats.streak > 0 ? "🔥" : ""}
            </p>
          </div>
          <div>
            <p className="text-2xl font-bold text-[var(--text)]">
              {stats.totalCompleted}
            </p>
            <p className="text-[13px] text-[var(--dim)]">tasks done, all time</p>
          </div>
          <div>
            <p className="text-2xl font-bold text-[var(--text)]">
              {stats.activeTotal}
            </p>
            <p className="text-[13px] text-[var(--dim)]">open tasks</p>
          </div>
          <div>
            <p
              className="text-2xl font-bold"
              style={{ color: stats.overdueCount > 0 ? "#E44332" : "var(--text)" }}
            >
              {stats.overdueCount}
            </p>
            <p className="text-[13px] text-[var(--dim)]">overdue</p>
          </div>
          {"todayDurationMinutes" in stats && (stats as { todayDurationMinutes?: number }).todayDurationMinutes ? (
            <div>
              <p className="text-2xl font-bold text-[var(--text)]">{Math.floor(((stats as { todayDurationMinutes?: number }).todayDurationMinutes ?? 0) / 60)}h {((stats as { todayDurationMinutes?: number }).todayDurationMinutes ?? 0) % 60}m</p>
              <p className="text-[13px] text-[var(--dim)]">planned today ⏱</p>
            </div>
          ) : null}
        </div>
      </div>

      {/* Weekly bars */}
      <section aria-label="Last 7 days">
        <h2 className="mb-2 text-base font-semibold text-[var(--text)]">
          Last 7 days
        </h2>
        <div className="flex items-end justify-between gap-2 rounded-2xl bg-[var(--surface)] px-4 pb-3 pt-4">
          {stats.weekly.map((d) => {
            const isToday = d.date === today;
            const dow = parseDateStr(d.date).getDay();
            return (
              <div key={d.date} className="flex flex-1 flex-col items-center gap-1.5">
                <span className="text-[12px] font-medium text-[var(--dim)]">
                  {d.count > 0 ? d.count : ""}
                </span>
                <div
                  className="w-full max-w-8 rounded-md"
                  style={{
                    height: `${Math.max(6, (d.count / maxWeekly) * 72)}px`,
                    backgroundColor: isToday ? "#E44332" : "#4CAF7D",
                    opacity: d.count === 0 ? 0.18 : 1,
                  }}
                />
                <span
                  className={`text-[12px] ${isToday ? "font-bold text-[var(--text)]" : "text-[var(--dim)]"}`}
                >
                  {DAY_LETTERS[dow]}
                </span>
              </div>
            );
          })}
        </div>
      </section>

      {/* Recent wins */}
      <section aria-label="Recent completions">
        <h2 className="mb-2 text-base font-semibold text-[var(--text)]">
          Recent wins
        </h2>
        {stats.recentCompletions.length === 0 ? (
          <p className="rounded-2xl bg-[var(--surface)] p-4 text-sm text-[var(--dim)]">
            Nothing completed yet. Check off a task and it lands here — this
            is your Karma trail.
          </p>
        ) : (
          <ul className="space-y-2">
            {stats.recentCompletions.map((c, i) => (
              <li
                key={`${c.title}-${c.date}-${i}`}
                className="flex items-center justify-between gap-3 rounded-xl bg-[var(--surface)] px-3 py-2.5"
              >
                <span className="min-w-0 truncate text-sm text-[var(--text)]">
                  ✓ {c.title}
                </span>
                <span className="shrink-0 text-[13px] text-[#4CAF7D]">
                  {c.date === today
                    ? "Today"
                    : c.date === addDays(today, -1)
                      ? "Yesterday"
                      : fullDateLabel(c.date)}
                </span>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
