// Natural-language parser for Quick Add, shared logic with the client
// preview (client/src/lib/nlp.ts mirrors this file). It pulls scheduling
// phrases out of free text Todoist-style:
//   "Write script tomorrow at 4pm #YouTube @focus p1"
//   "Gym every friday"  /  "Meditate every day"  /  "Pay rent on dec 25"
//
// Returns the cleaned title plus structured fields. Anything it does not
// recognise stays in the title untouched.

import { addDays, dayOfWeek } from "./dates";
import { WEEKDAY_NAMES } from "./recurrence";

export interface ParsedQuickAdd {
  title: string;
  dueDate: string | null;
  dueTime: string | null;
  recurrenceRule: string | null;
  priority: number; // 1 normal … 4 highest
  projectName: string | null;
  labelNames: string[];
  durationMinutes: number | null;
}

export function parseDurationFromText(text: string): { minutes: number | null; cleaned: string } {
  let minutes: number | null = null;
  let cleaned = text;
  const tryMatch = (re: RegExp, calc: (m: RegExpMatchArray) => number | null) => {
    if (minutes != null) return;
    const m = cleaned.match(re);
    if (m) {
      const v = calc(m);
      if (v != null && v > 0 && v <= 24 * 60) {
        minutes = v;
        cleaned = cleaned.replace(re, " ");
      }
    }
  };
  // "for 1h 30m" / "for 1 hour 30 minutes"
  tryMatch(/\bfor\s+(\d+)\s*h(?:ours?)?\s+(\d+)\s*m(?:in(?:ute)?s?)?\b/i, (m) => Number(m[1]) * 60 + Number(m[2]));
  // "for 2h" / "for 1 hour"
  tryMatch(/\bfor\s+(\d+)\s*h(?:ours?)?\b/i, (m) => Number(m[1]) * 60);
  // "for 30m" / "for 30 min"
  tryMatch(/\bfor\s+(\d+)\s*m(?:in(?:ute)?s?)?\b/i, (m) => Number(m[1]));
  // bare "30 min" / "45 minutes" / "2 hours" / "1h" / "30m" (require word boundary, avoid matching times like 4:30)
  tryMatch(/\b(\d+)\s*hours?\b/i, (m) => Number(m[1]) * 60);
  tryMatch(/\b(\d+)\s*min(?:ute)?s?\b/i, (m) => Number(m[1]));
  tryMatch(/\b(\d+)h\b/i, (m) => Number(m[1]) * 60);
  tryMatch(/\b(\d+)m\b/i, (m) => Number(m[1]));
  return { minutes, cleaned };
}

const MONTHS: Record<string, number> = {
  jan: 0, january: 0, feb: 1, february: 1, mar: 2, march: 2, apr: 3,
  april: 3, may: 4, jun: 5, june: 5, jul: 6, july: 6, aug: 7, august: 7,
  sep: 8, sept: 8, september: 8, oct: 9, october: 9, nov: 10, november: 10,
  dec: 11, december: 11,
};

function pad(n: number): string {
  return String(n).padStart(2, "0");
}

export function parseQuickAdd(raw: string, today: string): ParsedQuickAdd {
  let text = ` ${raw.trim()} `;
  let dueDate: string | null = null;
  let dueTime: string | null = null;
  let recurrenceRule: string | null = null;
  let priority = 1;
  let projectName: string | null = null;
  const labelNames: string[] = [];

  const strip = (pattern: RegExp) => {
    text = text.replace(pattern, " ");
  };

  // --- Recurrence (checked first: "every friday" also names a date) ---
  const everyNDays = text.match(/\bevery\s+(\d+)\s+days?\b/i);
  if (everyNDays?.[1]) {
    recurrenceRule = `interval:${everyNDays[1]}`;
    strip(/\bevery\s+\d+\s+days?\b/i);
  } else if (/\bevery\s+day\b/i.test(text) || /\bdaily\b/i.test(text)) {
    recurrenceRule = "daily";
    strip(/\bevery\s+day\b|\bdaily\b/i);
  } else if (/\bevery\s+weekdays?\b/i.test(text)) {
    recurrenceRule = "weekdays";
    strip(/\bevery\s+weekdays?\b/i);
  } else if (/\bevery\s+weeks?\b/i.test(text) || /\bweekly\b/i.test(text)) {
    recurrenceRule = "weekly";
    strip(/\bevery\s+weeks?\b|\bweekly\b/i);
  } else if (/\bevery\s+months?\b/i.test(text) || /\bmonthly\b/i.test(text)) {
    recurrenceRule = "monthly";
    strip(/\bevery\s+months?\b|\bmonthly\b/i);
  } else {
    for (let dow = 0; dow < 7; dow++) {
      const name = WEEKDAY_NAMES[dow];
      if (new RegExp(`\\bevery\\s+${name}\\b`, "i").test(text)) {
        recurrenceRule = `weekly:${dow}`;
        strip(new RegExp(`\\bevery\\s+${name}\\b`, "i"));
        break;
      }
    }
  }

  // --- Explicit calendar date: "dec 25" / "25 dec" / "on 2026-12-25" ---
  const iso = text.match(/\b(\d{4}-\d{2}-\d{2})\b/);
  if (iso?.[1]) {
    dueDate = iso[1];
    strip(/\b\d{4}-\d{2}-\d{2}\b/);
  } else {
    const monthFirst = text.match(
      /\b(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\s+(\d{1,2})(?:st|nd|rd|th)?\b/i,
    );
    const dayFirst = text.match(
      /\b(\d{1,2})(?:st|nd|rd|th)?\s+(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\b/i,
    );
    const m = monthFirst ?? dayFirst;
    if (m) {
      const monthName = (monthFirst ? m[1] : m[2])?.toLowerCase() ?? "";
      const dayNum = Number(monthFirst ? m[2] : m[1]);
      const month = MONTHS[monthName.slice(0, 3)] ?? MONTHS[monthName];
      if (month !== undefined && dayNum >= 1 && dayNum <= 31) {
        const [ty = 1970] = today.split("-").map(Number);
        const thisYear = `${ty}-${pad(month + 1)}-${pad(dayNum)}`;
        // A month/day already past this year means next year.
        dueDate = thisYear < today ? `${ty + 1}-${pad(month + 1)}-${pad(dayNum)}` : thisYear;
        strip(new RegExp(m[0].replace(/[.*+?^${}()|[\]\\]/g, "\\$&").replace(/\s+/g, "\\s+"), "i"));
      }
    }
  }

  // --- Relative dates ---
  if (!dueDate) {
    if (/\bday after tomorrow\b/i.test(text)) {
      dueDate = addDays(today, 2);
      strip(/\bday after tomorrow\b/i);
    } else if (/\btomorrow\b/i.test(text)) {
      dueDate = addDays(today, 1);
      strip(/\btomorrow\b/i);
    } else if (/\btoday\b/i.test(text)) {
      dueDate = today;
      strip(/\btoday\b/i);
    } else if (/\btonight\b/i.test(text)) {
      dueDate = today;
      if (!dueTime) dueTime = "20:00";
      strip(/\btonight\b/i);
    } else {
      const inDays = text.match(/\bin\s+(\d+)\s+days?\b/i);
      if (inDays?.[1]) {
        dueDate = addDays(today, Number(inDays[1]));
        strip(/\bin\s+\d+\s+days?\b/i);
      } else if (/\bnext week\b/i.test(text)) {
        dueDate = addDays(today, 7);
        strip(/\bnext week\b/i);
      } else {
        // Bare weekday name: the upcoming one ("friday"); "next friday"
        // skips a same-day match to the following week.
        for (let dow = 0; dow < 7 && !dueDate; dow++) {
          const name = WEEKDAY_NAMES[dow];
          const re = new RegExp(`\\b(next\\s+)?${name}\\b`, "i");
          const match = text.match(re);
          if (match) {
            let delta = (dow - dayOfWeek(today) + 7) % 7;
            if (match[1] && delta === 0) delta = 7;
            dueDate = addDays(today, delta);
            strip(re);
          }
        }
      }
    }
  }

  // Recurring tasks with no explicit date start today (or their weekday).
  if (!dueDate && recurrenceRule) {
    if (recurrenceRule.startsWith("weekly:")) {
      const target = Number(recurrenceRule.slice("weekly:".length));
      const delta = (target - dayOfWeek(today) + 7) % 7;
      dueDate = addDays(today, delta);
    } else {
      dueDate = today;
    }
  }

  // --- Time: "at 4pm", "at 16:30", "4:30 pm" ---
  const timeMatch =
    text.match(/\bat\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)\b/i) ??
    text.match(/\bat\s+(\d{1,2}):(\d{2})\b/i) ??
    text.match(/\b(\d{1,2}):(\d{2})\s*(am|pm)\b/i);
  if (timeMatch) {
    let hour = Number(timeMatch[1]);
    const minute = Number(timeMatch[2] ?? 0);
    const meridiem = (timeMatch[3] ?? "").toLowerCase();
    if (meridiem === "pm" && hour < 12) hour += 12;
    if (meridiem === "am" && hour === 12) hour = 0;
    if (hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59) {
      dueTime = `${pad(hour)}:${pad(minute)}`;
      if (!dueDate) dueDate = today;
    }
    strip(timeMatch[0] ? new RegExp(timeMatch[0].trim().replace(/[.*+?^${}()|[\]\\]/g, "\\$&").replace(/\s+/g, "\\s+"), "i") : /$^/);
  }

  // --- Priority: p1 (highest) … p4 ---
  const prio = text.match(/\bp([1-4])\b/i);
  if (prio?.[1]) {
    priority = 5 - Number(prio[1]);
    strip(/\bp[1-4]\b/i);
  }

  // --- Project #Name and labels @name ---
  const proj = text.match(/#([\p{L}\p{N}_-]+)/u);
  if (proj?.[1]) {
    projectName = proj[1];
    strip(/#[\p{L}\p{N}_-]+/u);
  }
  for (const m of text.matchAll(/@([\p{L}\p{N}_-]+)/gu)) {
    if (m[1]) labelNames.push(m[1]);
  }
  if (labelNames.length) strip(/@[\p{L}\p{N}_-]+/gu);

  const dur = parseDurationFromText(text);
  text = dur.cleaned;
  const title = text.replace(/\s+/g, " ").trim() || raw.trim();
  return { title, dueDate, dueTime, recurrenceRule, priority, projectName, labelNames, durationMinutes: dur.minutes };
}
