// Filter query engine — Pro feature: saved filter views.
// Syntax (case-insensitive), terms combined with & or AND (all must match):
//   today | overdue | tomorrow | "no date" | nodate
//   p1 | p2 | p3 | p4   (Todoist priority labels; internal 4=P1 … 1=P4)
//   #ProjectName        (project, spaces as _ or exact name in quotes not needed — match by normalized name)
//   @label
//   recurring | "has duration" | "has reminder" handled loosely
// Unknown terms are treated as title substring search.

import { addDays } from "./dates";

export interface FilterableTask {
  title: string;
  priority: number;
  dueDate: string | null;
  projectName: string | null;
  labels: { name: string }[];
  recurrenceRule: string | null;
  durationMinutes: number | null;
}

function norm(s: string): string {
  return s.toLowerCase().replace(/[\s_-]+/g, "");
}

export function matchesFilter(task: FilterableTask, query: string, today: string): boolean {
  const rawTerms = query
    .split(/\s*(?:&|\bAND\b)\s*/i)
    .map((t) => t.trim())
    .filter(Boolean);
  const terms = rawTerms.length ? rawTerms : [query.trim()];
  for (const termRaw of terms) {
    const term = termRaw.trim();
    const low = term.toLowerCase();
    if (!term) continue;
    if (low === "today") {
      if (!(task.dueDate != null && task.dueDate <= today)) return false;
    } else if (low === "overdue") {
      if (!(task.dueDate != null && task.dueDate < today)) return false;
    } else if (low === "tomorrow") {
      if (task.dueDate !== addDays(today, 1)) return false;
    } else if (low === "no date" || low === "nodate" || low === "no due date") {
      if (task.dueDate != null) return false;
    } else if (low === "recurring") {
      if (!task.recurrenceRule) return false;
    } else if (/^p[1-4]$/.test(low)) {
      const want = 5 - Number(low[1]);
      if (task.priority !== want) return false;
    } else if (term.startsWith("#")) {
      const want = norm(term.slice(1));
      if (!task.projectName || norm(task.projectName) !== want) return false;
    } else if (term.startsWith("@")) {
      const want = norm(term.slice(1));
      if (!task.labels.some((l) => norm(l.name) === want)) return false;
    } else {
      // free text: title contains
      if (!task.title.toLowerCase().includes(low)) return false;
    }
  }
  return true;
}

export const STARTER_FILTERS: { name: string; query: string; color: string }[] = [
  { name: "Priority 1", query: "p1", color: "#E44332" },
  { name: "Overdue", query: "overdue", color: "#E44332" },
  { name: "No due date", query: "no date", color: "#808080" },
  { name: "Today — P1 & P2", query: "today & p1", color: "#FFA726" },
];
