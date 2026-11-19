// Local calendar-date helpers (client mirror of the server's lib/dates).
// Dates are YYYY-MM-DD strings in the viewer's local timezone.

export function toDateStr(date: Date): string {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, "0");
  const d = String(date.getDate()).padStart(2, "0");
  return `${y}-${m}-${d}`;
}

export function todayStr(): string {
  return toDateStr(new Date());
}

export function parseDateStr(value: string): Date {
  const [y = 1970, m = 1, d = 1] = value.split("-").map(Number);
  return new Date(y, m - 1, d, 12, 0, 0);
}

export function addDays(dateStr: string, days: number): string {
  const date = parseDateStr(dateStr);
  date.setDate(date.getDate() + days);
  return toDateStr(date);
}

export function dayOfWeek(dateStr: string): number {
  return parseDateStr(dateStr).getDay();
}

const WEEKDAYS = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
const MONTHS = [
  "Jan", "Feb", "Mar", "Apr", "May", "Jun",
  "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
];

/** "Today" / "Tomorrow" / "Fri" / "12 Dec" style label for a due date. */
export function dueLabel(dateStr: string, today: string): string {
  if (dateStr === today) return "Today";
  if (dateStr === addDays(today, 1)) return "Tomorrow";
  if (dateStr === addDays(today, -1)) return "Yesterday";
  const date = parseDateStr(dateStr);
  const withinWeek =
    dateStr > today && dateStr <= addDays(today, 6);
  if (withinWeek) return WEEKDAYS[date.getDay()] ?? "";
  return `${date.getDate()} ${MONTHS[date.getMonth()] ?? ""}`;
}

/** Full heading label, e.g. "Fri 20 Nov". */
export function fullDateLabel(dateStr: string): string {
  const date = parseDateStr(dateStr);
  return `${WEEKDAYS[date.getDay()]} ${date.getDate()} ${MONTHS[date.getMonth()] ?? ""}`;
}

/** "4:00 PM" style label for an HH:MM time. */
export function timeLabel(hhmm: string): string {
  const [h = 0, m = 0] = hhmm.split(":").map(Number);
  const meridiem = h >= 12 ? "PM" : "AM";
  const hour12 = h % 12 === 0 ? 12 : h % 12;
  return m === 0
    ? `${hour12} ${meridiem}`
    : `${hour12}:${String(m).padStart(2, "0")} ${meridiem}`;
}
