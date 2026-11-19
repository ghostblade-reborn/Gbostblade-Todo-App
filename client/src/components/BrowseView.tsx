import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api, type ApiResponse } from "../api";
import { actionFields } from "@hatch/space-sdk/fields" with { type: "macro" };
import type { Actions } from "../../../server/src/actions";

const FIELDS = actionFields<typeof Actions>();

type Bootstrap = ApiResponse<typeof api, "getBootstrap">;

const COLOR_CHOICES = [
  "#E44332", "#FFA726", "#FDD835", "#4CAF7D",
  "#42A5F5", "#AB47BC", "#EC407A", "#808080",
];

interface Props {
  bootstrap: Bootstrap;
  inboxCount: number;
  onOpenProject: (id: number, name: string) => void;
  onOpenLabel: (id: number, name: string) => void;
  onOpenCompleted: () => void;
  onOpenStats: () => void;
  onOpenFilter: (id: number, name: string, query: string) => void;
  onOpenCalendar: () => void;
  onOpenRamble: () => void;
  onOpenAssist: () => void;
  onOpenHistory: () => void;
  onOpenInsights: () => void;
  onOpenBackups: () => void;
}

function SectionTitle({ children }: { children: string }) {
  return (
    <h2 className="mb-2 mt-6 text-[13px] font-semibold uppercase tracking-wider text-[var(--dim)]">
      {children}
    </h2>
  );
}

export function BrowseView({
  bootstrap,
  inboxCount,
  onOpenProject,
  onOpenLabel,
  onOpenCompleted,
  onOpenStats,
  onOpenFilter,
  onOpenCalendar,
  onOpenRamble,
  onOpenAssist,
  onOpenHistory,
  onOpenInsights,
  onOpenBackups,
}: Props) {
  const queryClient = useQueryClient();
  const invalidate = () => void queryClient.invalidateQueries();
  const [projectName, setProjectName] = useState("");
  const [projectColor, setProjectColor] = useState(COLOR_CHOICES[0] ?? "#E44332");
  const [labelName, setLabelName] = useState("");
  const [labelColor, setLabelColor] = useState(COLOR_CHOICES[4] ?? "#42A5F5");
  const [formError, setFormError] = useState<string | null>(null);
  const [filterName, setFilterName] = useState("");
  const [filterQuery, setFilterQuery] = useState("");
  const [filterColor, setFilterColor] = useState(COLOR_CHOICES[0] ?? "#E44332");
  const [filterError, setFilterError] = useState<string | null>(null);

  const addFilter = useMutation({
    mutationFn: async () => await api.addFilter({ name: filterName.trim(), query: filterQuery.trim(), color: filterColor }),
    onSuccess: () => { setFilterName(""); setFilterQuery(""); invalidate(); },
  });
  const deleteFilter = useMutation({
    mutationFn: async (id: number) => await api.deleteFilter({ id }),
    onSuccess: invalidate,
  });

  const addProject = useMutation({
    mutationFn: async () =>
      await api.addProject({ name: projectName.trim(), color: projectColor }),
    onSuccess: () => {
      setProjectName("");
      invalidate();
    },
  });
  const deleteProject = useMutation({
    mutationFn: async (id: number) => await api.deleteProject({ id }),
    onSuccess: invalidate,
  });
  const addLabel = useMutation({
    mutationFn: async () =>
      await api.addLabel({ name: labelName.trim(), color: labelColor }),
    onSuccess: () => {
      setLabelName("");
      invalidate();
    },
  });
  const deleteLabel = useMutation({
    mutationFn: async (id: number) => await api.deleteLabel({ id }),
    onSuccess: invalidate,
  });

  const rowCls =
    "flex w-full items-center gap-3 rounded-xl bg-[var(--surface)] px-3 py-3 text-left";

  return (
    <div>
      <div className="mt-2 rounded-2xl border border-[#FFA726]/40 bg-[#2b2118] p-3">
        <p className="text-sm font-bold text-[#FFA726]">⭐ PRO — ALL UNLOCKED</p>
        <p className="mt-0.5 text-[13px] text-[var(--dim)]">300 projects · 150 filters · Calendar · Reminders · Durations · Auto backups · Full history · Ramble · Assist · Insights — no paywall in your own app.</p>
      </div>

      <SectionTitle>Pro features</SectionTitle>
      <div className="space-y-2">
        <button type="button" className={rowCls} onClick={onOpenCalendar}><span aria-hidden>📅</span><span className="flex-1 text-base text-[var(--text)]">Calendar</span><span className="text-[var(--dim)]">›</span></button>
        <button type="button" className={rowCls} onClick={onOpenRamble}><span aria-hidden>🎙️</span><span className="flex-1 text-base text-[var(--text)]">Ramble — voice capture</span><span className="rounded-full bg-[#2b2118] px-2 py-0.5 text-[11px] font-bold text-[#FFA726]">UNLIMITED</span><span className="text-[var(--dim)]">›</span></button>
        <button type="button" className={rowCls} onClick={onOpenAssist}><span aria-hidden>✨</span><span className="flex-1 text-base text-[var(--text)]">Task &amp; Email Assist</span><span className="text-[var(--dim)]">›</span></button>
        <button type="button" className={rowCls} onClick={onOpenHistory}><span aria-hidden>🕘</span><span className="flex-1 text-base text-[var(--text)]">History — full reporting</span><span className="text-[var(--dim)]">›</span></button>
        <button type="button" className={rowCls} onClick={onOpenInsights}><span aria-hidden>💡</span><span className="flex-1 text-base text-[var(--text)]">Project insights</span><span className="text-[var(--dim)]">›</span></button>
        <button type="button" className={rowCls} onClick={onOpenBackups}><span aria-hidden>💾</span><span className="flex-1 text-base text-[var(--text)]">Backups &amp; settings</span><span className="text-[var(--dim)]">›</span></button>
        <button type="button" className={rowCls} onClick={onOpenStats}>
          <span aria-hidden>📈</span>
          <span className="flex-1 text-base text-[var(--text)]">Productivity</span>
          <span className="text-[var(--dim)]">›</span>
        </button>
        <button type="button" className={rowCls} onClick={onOpenCompleted}>
          <span aria-hidden>✅</span>
          <span className="flex-1 text-base text-[var(--text)]">Completed tasks</span>
          <span className="text-[var(--dim)]">›</span>
        </button>
      </div>

      <SectionTitle>{`Filter views — ${bootstrap.filters.length}/150`}</SectionTitle>
      <div className="space-y-2">
        {bootstrap.filters.map((f) => (
          <div key={f.id} className={rowCls}>
            <button type="button" aria-label={`Open filter ${f.name}`} onClick={() => onOpenFilter(f.id, f.name, f.query)} className="flex flex-1 items-center gap-3 text-left">
              <span className="h-3 w-3 shrink-0 rounded-full" style={{ backgroundColor: f.color }} aria-hidden />
              <span className="min-w-0 flex-1"><span className="block truncate text-base text-[var(--text)]">{f.name}</span><span className="block truncate text-[12px] text-[var(--dim)]">{f.query}</span></span>
              <span className="text-sm text-[var(--dim)]">{f.count}</span>
            </button>
            <button type="button" aria-label={`Delete filter ${f.name}`} onClick={() => deleteFilter.mutate(f.id)} className="px-2 text-lg text-[var(--dim)]">×</button>
          </div>
        ))}
        {bootstrap.filters.length === 0 && <p className="text-sm text-[var(--dim)]">No filters yet — create one below.</p>}
      </div>
      <form className="mt-3 rounded-xl bg-[var(--surface)] p-3" onSubmit={(e) => { e.preventDefault(); if (!filterName.trim() || !filterQuery.trim()) { setFilterError("Give the filter a name and a query — e.g. name “Urgent”, query “today & p1”."); return; } setFilterError(null); addFilter.mutate(); }}>
        <label htmlFor="new-filter-name" className="text-sm font-medium text-[var(--text)]">New filter view</label>
        <input id="new-filter-name" {...FIELDS.addFilter.name} value={filterName} onChange={(e) => setFilterName(e.target.value)} placeholder="Filter name, e.g. Urgent today" className="mt-2 w-full rounded-lg bg-[var(--surface-alt)] px-3 py-2.5 text-base text-[var(--text)] outline-none placeholder:text-[var(--dim)] focus:ring-2 focus:ring-[var(--accent)]" />
        <input aria-label="Filter query" {...FIELDS.addFilter.query} value={filterQuery} onChange={(e) => setFilterQuery(e.target.value)} placeholder="Query: today, overdue, p1, #Project, @label, no date — join with &" className="mt-2 w-full rounded-lg bg-[var(--surface-alt)] px-3 py-2.5 text-base text-[var(--text)] outline-none placeholder:text-[var(--dim)] focus:ring-2 focus:ring-[var(--accent)]" />
        <div className="mt-2 flex items-center gap-2">
          <div className="flex gap-2" role="group" aria-label="Filter color">
            {COLOR_CHOICES.map((c) => (
              <button key={c} type="button" aria-label={`Filter color ${c}`} aria-pressed={filterColor === c} onClick={() => setFilterColor(c)} className="h-7 w-7 rounded-full" style={{ backgroundColor: c, outline: filterColor === c ? "2px solid #fff" : "none", outlineOffset: 2 }} />
            ))}
          </div>
          <button type="submit" disabled={addFilter.isPending} className="ml-auto rounded-lg bg-[var(--accent)] px-4 py-2.5 font-semibold text-white disabled:opacity-50">Add filter</button>
        </div>
        {filterError && <p className="mt-2 text-sm text-[#E44332]">{filterError}</p>}
        <p className="mt-2 text-[12px] text-[var(--dim)]">Examples: <code>overdue</code> · <code>p1</code> · <code>today &amp; p1</code> · <code>#ShadowRise</code> · <code>@focus</code> · <code>no date</code></p>
      </form>

      <SectionTitle>{`Projects — ${bootstrap.projects.length}/300`}</SectionTitle>
      <div className="space-y-2">
        <div className={rowCls} aria-label="Inbox summary">
          <span
            className="h-3 w-3 rounded-full"
            style={{ backgroundColor: "#808080" }}
            aria-hidden
          />
          <span className="flex-1 text-base text-[var(--text)]">Inbox</span>
          <span className="text-sm text-[var(--dim)]">{inboxCount}</span>
        </div>
        {bootstrap.projects.map((p) => (
          <div key={p.id} className={rowCls}>
            <button
              type="button"
              aria-label={`Open project ${p.name}`}
              onClick={() => onOpenProject(p.id, p.name)}
              className="flex flex-1 items-center gap-3 text-left"
            >
              <span
                className="h-3 w-3 shrink-0 rounded-full"
                style={{ backgroundColor: p.color }}
                aria-hidden
              />
              <span className="flex-1 text-base text-[var(--text)]">{p.name}</span>
              <span className="text-sm text-[var(--dim)]">{p.activeCount}</span>
            </button>
            <button
              type="button"
              aria-label={`Delete project ${p.name}`}
              onClick={() => deleteProject.mutate(p.id)}
              className="px-2 text-lg text-[var(--dim)]"
            >
              ×
            </button>
          </div>
        ))}
        {bootstrap.projects.length === 0 && (
          <p className="text-sm text-[var(--dim)]">
            No projects yet — group tasks by channel, goal, or area below.
          </p>
        )}
      </div>

      <form
        className="mt-3 rounded-xl bg-[var(--surface)] p-3"
        onSubmit={(e) => {
          e.preventDefault();
          if (!projectName.trim()) {
            setFormError("Give the project a name first.");
            return;
          }
          setFormError(null);
          addProject.mutate();
        }}
      >
        <label htmlFor="new-project-name" className="text-sm font-medium text-[var(--text)]">
          New project
        </label>
        <div className="mt-2 flex gap-2">
          <input
            id="new-project-name"
            {...FIELDS.addProject.name}
            value={projectName}
            onChange={(e) => setProjectName(e.target.value)}
            placeholder="e.g. Shadow Rise"
            className="min-w-0 flex-1 rounded-lg bg-[var(--surface-alt)] px-3 py-2.5 text-base text-[var(--text)] outline-none placeholder:text-[var(--dim)] focus:ring-2 focus:ring-[var(--accent)]"
          />
          <button
            type="submit"
            disabled={addProject.isPending}
            className="rounded-lg bg-[var(--accent)] px-4 py-2.5 font-semibold text-white disabled:opacity-50"
          >
            Add
          </button>
        </div>
        <div className="mt-2 flex gap-2" role="group" aria-label="Project color">
          {COLOR_CHOICES.map((c) => (
            <button
              key={c}
              type="button"
              aria-label={`Project color ${c}`}
              aria-pressed={projectColor === c}
              onClick={() => setProjectColor(c)}
              className="h-7 w-7 rounded-full"
              style={{
                backgroundColor: c,
                outline: projectColor === c ? "2px solid #fff" : "none",
                outlineOffset: 2,
              }}
            />
          ))}
        </div>
      </form>

      <SectionTitle>Labels</SectionTitle>
      <div className="flex flex-wrap gap-2">
        {bootstrap.labels.map((l) => (
          <span
            key={l.id}
            className="inline-flex items-center gap-1 rounded-full bg-[var(--surface)] py-1 pl-3 pr-1.5"
          >
            <button
              type="button"
              aria-label={`Open label ${l.name}`}
              onClick={() => onOpenLabel(l.id, l.name)}
              className="text-sm font-medium"
              style={{ color: l.color }}
            >
              @{l.name} · {l.activeCount}
            </button>
            <button
              type="button"
              aria-label={`Delete label ${l.name}`}
              onClick={() => deleteLabel.mutate(l.id)}
              className="px-1 text-base text-[var(--dim)]"
            >
              ×
            </button>
          </span>
        ))}
        {bootstrap.labels.length === 0 && (
          <p className="text-sm text-[var(--dim)]">
            No labels yet — tag tasks with @label in Quick Add, or create one here.
          </p>
        )}
      </div>

      <form
        className="mt-3 rounded-xl bg-[var(--surface)] p-3"
        onSubmit={(e) => {
          e.preventDefault();
          if (!labelName.trim()) {
            setFormError("Give the label a name first.");
            return;
          }
          setFormError(null);
          addLabel.mutate();
        }}
      >
        <label htmlFor="new-label-name" className="text-sm font-medium text-[var(--text)]">
          New label
        </label>
        <div className="mt-2 flex gap-2">
          <input
            id="new-label-name"
            {...FIELDS.addLabel.name}
            value={labelName}
            onChange={(e) => setLabelName(e.target.value)}
            placeholder="e.g. focus"
            className="min-w-0 flex-1 rounded-lg bg-[var(--surface-alt)] px-3 py-2.5 text-base text-[var(--text)] outline-none placeholder:text-[var(--dim)] focus:ring-2 focus:ring-[var(--accent)]"
          />
          <button
            type="submit"
            disabled={addLabel.isPending}
            className="rounded-lg bg-[var(--accent)] px-4 py-2.5 font-semibold text-white disabled:opacity-50"
          >
            Add
          </button>
        </div>
        <div className="mt-2 flex gap-2" role="group" aria-label="Label color">
          {COLOR_CHOICES.map((c) => (
            <button
              key={c}
              type="button"
              aria-label={`Label color ${c}`}
              aria-pressed={labelColor === c}
              onClick={() => setLabelColor(c)}
              className="h-7 w-7 rounded-full"
              style={{
                backgroundColor: c,
                outline: labelColor === c ? "2px solid #fff" : "none",
                outlineOffset: 2,
              }}
            />
          ))}
        </div>
      </form>

      {formError && <p className="mt-3 text-sm text-[#E44332]">{formError}</p>}
    </div>
  );
}
