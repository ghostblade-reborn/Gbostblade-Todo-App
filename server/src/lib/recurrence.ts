// Recurrence rules. A rule is a small string id so new rule kinds can be
// added without a schema change:
//   daily          — every day
//   weekdays       — Monday to Friday
//   weekly         — same weekday, +7 days
//   weekly:<0-6>   — a specific weekday (0 = Sunday)
//   monthly        — same day of month, +1 month (clamped)
//   interval:<n>   — every n days

import { addDays, dayOfWeek, parseDateStr, toDateStr } from "./dates";

export const WEEKDAY_NAMES = [
  "sunday",
  "monday",
  "tuesday",
  "wednesday",
  "thursday",
  "friday",
  "saturday",
] as const;

export function recurrenceLabel(rule: string): string {
  if (rule === "daily") return "Every day";
  if (rule === "weekdays") return "Every weekday";
  if (rule === "weekly") return "Every week";
  if (rule === "monthly") return "Every month";
  if (rule.startsWith("weekly:")) {
    const dow = Number(rule.slice("weekly:".length));
    const name = WEEKDAY_NAMES[dow] ?? "";
    return `Every ${name.charAt(0).toUpperCase()}${name.slice(1)}`;
  }
  if (rule.startsWith("interval:")) {
    const n = Number(rule.slice("interval:".length));
    return n === 1 ? "Every day" : `Every ${n} days`;
  }
  return rule;
}

/** The next due date strictly after `fromDateStr` for the given rule. */
export function nextOccurrence(rule: string, fromDateStr: string): string {
  if (rule === "daily") return addDays(fromDateStr, 1);
  if (rule.startsWith("interval:")) {
    const n = Math.max(1, Number(rule.slice("interval:".length)) || 1);
    return addDays(fromDateStr, n);
  }
  if (rule === "weekdays") {
    let next = addDays(fromDateStr, 1);
    while (dayOfWeek(next) === 0 || dayOfWeek(next) === 6) {
      next = addDays(next, 1);
    }
    return next;
  }
  if (rule === "weekly") return addDays(fromDateStr, 7);
  if (rule.startsWith("weekly:")) {
    const target = Number(rule.slice("weekly:".length));
    let next = addDays(fromDateStr, 1);
    while (dayOfWeek(next) !== target) next = addDays(next, 1);
    return next;
  }
  if (rule === "monthly") {
    const date = parseDateStr(fromDateStr);
    const day = date.getUTCDate();
    date.setUTCMonth(date.getUTCMonth() + 1, 1);
    const lastDay = new Date(
      Date.UTC(date.getUTCFullYear(), date.getUTCMonth() + 1, 0),
    ).getUTCDate();
    date.setUTCDate(Math.min(day, lastDay));
    return toDateStr(date);
  }
  return addDays(fromDateStr, 1);
}
