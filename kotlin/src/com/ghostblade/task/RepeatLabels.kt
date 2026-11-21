package com.ghostblade.task

import java.util.Calendar

/* Date-generated repeat labels (TickTick pattern) — pure, no Android imports.
 *
 * Mapping to the REAL stored recurrence strings in v4.0
 * (Parser.kt, Dates.advanceDate, MainActivity.buildQaRepeat / editor):
 *   null          -> no repeat
 *   "daily"       -> every day
 *   "weekly"      -> every week on the due date's weekday
 *   "weekly:N"    -> every week on weekday N, where N is JS-style
 *                    0=Sunday, 1=Monday, ..., 6=Saturday (Parser emits
 *                    "weekly:1" for "every monday", buildQaRepeat emits
 *                    "weekly:0".."weekly:6")
 *   "monthly"     -> every month on the due date's day-of-month
 * v4.0 stores NO other recurrence values. The kinds below are
 * forward-compatible labels for values the design (§8.4) names, so a
 * future stored value renders correctly without a code change:
 *   "yearly"      -> every year on the due date's month/day
 *   "weekday" / "weekdays" / "every-weekday" -> every weekday Mon-Fri
 *   "custom"      -> custom recurrence
 * Any other / unknown kind renders as "None" (menu) / "None" (summary).
 */
object RepeatLabels {

    private val SHORT_WEEKDAYS_JS = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    private val SHORT_MONTHS = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val FULL_MONTHS = arrayOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

    private val WEEKDAY_KINDS = setOf("weekday", "weekdays", "every-weekday", "every_weekday", "every weekday")

    private fun isWeekdayKind(kind: String): Boolean = kind in WEEKDAY_KINDS

    private fun shortWeekdayOf(date: String): String? = try {
        SHORT_WEEKDAYS_JS[Dates.jsDayOfWeek(Dates.parse(date))]
    } catch (e: Exception) { null }

    private fun dayOfMonth(date: String): Int? = try {
        Dates.parse(date).get(Calendar.DAY_OF_MONTH)
    } catch (e: Exception) { null }

    private fun monthIndex(date: String): Int? = try {
        Dates.parse(date).get(Calendar.MONTH)
    } catch (e: Exception) { null }

    private fun weeklyShort(kind: String, dueDate: String?): String? {
        if (kind.startsWith("weekly:")) {
            val n = kind.substringAfter(':').toIntOrNull()
            if (n != null && n in 0..6) return SHORT_WEEKDAYS_JS[n]
            // Out-of-range N: fall back to the due date's weekday.
            return dueDate?.let { shortWeekdayOf(it) }
        }
        return dueDate?.let { shortWeekdayOf(it) }
    }

    fun menuLabel(kind: String?, dueDate: String?): String {
        if (kind == null) return "None"
        return when {
            kind == "daily" -> "Daily"
            kind == "weekly" || kind.startsWith("weekly:") -> {
                val wd = weeklyShort(kind, dueDate)
                if (wd != null) "Weekly ($wd)" else "Weekly"
            }
            kind == "monthly" -> {
                val d = dueDate?.let { dayOfMonth(it) }
                if (d != null) "Monthly (The $d day)" else "Monthly"
            }
            kind == "yearly" -> {
                val m = dueDate?.let { monthIndex(it) }
                val d = dueDate?.let { dayOfMonth(it) }
                if (m != null && d != null) "Yearly (on ${SHORT_MONTHS[m]} $d)" else "Yearly"
            }
            isWeekdayKind(kind) -> "Every Weekday (Mon–Fri)"
            kind == "custom" -> "Custom…"
            else -> "None"
        }
    }

    fun summaryLabel(kind: String?, dueDate: String?): String {
        if (kind == null) return "None"
        return when {
            kind == "daily" -> "Daily"
            kind == "weekly" || kind.startsWith("weekly:") -> {
                val wd = weeklyShort(kind, dueDate)
                if (wd != null) "Weekly on $wd" else "Weekly"
            }
            kind == "monthly" -> {
                val d = dueDate?.let { dayOfMonth(it) }
                if (d != null) "Monthly on the $d day" else "Monthly"
            }
            kind == "yearly" -> {
                val m = dueDate?.let { monthIndex(it) }
                val d = dueDate?.let { dayOfMonth(it) }
                if (m != null && d != null) "Yearly on ${FULL_MONTHS[m]} $d" else "Yearly"
            }
            isWeekdayKind(kind) -> "Every Weekday (Mon–Fri)"
            kind == "custom" -> "Custom"
            else -> "None"
        }
    }
}
