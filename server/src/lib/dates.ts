// Calendar-date helpers. All due dates in this app are local calendar
// dates stored as YYYY-MM-DD strings; arithmetic is done at UTC noon so
// DST and timezone offsets can never shift a day.

export function parseDateStr(value: string): Date {
  const [y = 1970, m = 1, d = 1] = value.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, d, 12, 0, 0));
}

export function toDateStr(date: Date): string {
  const y = date.getUTCFullYear();
  const m = String(date.getUTCMonth() + 1).padStart(2, "0");
  const d = String(date.getUTCDate()).padStart(2, "0");
  return `${y}-${m}-${d}`;
}

export function addDays(dateStr: string, days: number): string {
  const date = parseDateStr(dateStr);
  date.setUTCDate(date.getUTCDate() + days);
  return toDateStr(date);
}

export function dayOfWeek(dateStr: string): number {
  return parseDateStr(dateStr).getUTCDay(); // 0 = Sunday
}

/** Today (YYYY-MM-DD) in a specific IANA timezone. */
export function todayIn(timeZone: string): string {
  return new Intl.DateTimeFormat("en-CA", {
    timeZone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(new Date());
}
