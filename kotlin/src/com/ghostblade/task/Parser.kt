package com.ghostblade.task

/** Natural-language quick-add parser — a faithful port of the JS parser.
 *  Understands: #project, @label, p1–p4, "for 30m" / "for 1h30m",
 *  today / tomorrow / next week / weekday names / "5 jan" / "jan 5",
 *  times like "7am", "7:30pm", "18:30", and "every day|week|month|<weekday>". */
object Parser {

    data class Parsed(
        var title: String = "",
        var dueDate: String? = null,
        var dueTime: String? = null,
        var recurrence: String? = null,
        var projectName: String? = null,
        var labelNames: MutableList<String> = mutableListOf(),
        var priority: Int = 4,
        var durationMin: Int? = null
    )

    private val WEEKDAYS = mapOf(
        "sunday" to 0, "monday" to 1, "tuesday" to 2, "wednesday" to 3,
        "thursday" to 4, "friday" to 5, "saturday" to 6
    )
    private val MONTHS = mapOf(
        "jan" to 0, "feb" to 1, "mar" to 2, "apr" to 3, "may" to 4, "jun" to 5,
        "jul" to 6, "aug" to 7, "sep" to 8, "oct" to 9, "nov" to 10, "dec" to 11
    )
    private fun pad(n: Int) = n.toString().padStart(2, '0')

    fun parse(input: String): Parsed {
        var text = " " + input.trim() + " "
        val out = Parsed()

        text = text.replace(Regex("""#([\p{L}\p{N}_-]+)""")) { m -> out.projectName = m.groupValues[1]; " " }
        text = text.replace(Regex("""@([\p{L}\p{N}_-]+)""")) { m -> out.labelNames.add(m.groupValues[1]); " " }
        text = text.replace(Regex("""\bp([1-4])\b""", RegexOption.IGNORE_CASE)) { m -> out.priority = m.groupValues[1].toInt(); " " }
        text = text.replace(Regex("""\bfor\s+(\d+)\s*h(?:ours?)?\s*(?:(\d+)\s*m(?:in(?:ute)?s?)?)?\b""", RegexOption.IGNORE_CASE)) { m ->
            out.durationMin = m.groupValues[1].toInt() * 60 + (m.groupValues[2].toIntOrNull() ?: 0); " "
        }
        text = text.replace(Regex("""\bfor\s+(\d+)\s*m(?:in(?:ute)?s?)?\b""", RegexOption.IGNORE_CASE)) { m ->
            out.durationMin = m.groupValues[1].toInt(); " "
        }
        text = text.replace(Regex("""\b(\d+)\s*h\s*(\d+)\s*m\b""", RegexOption.IGNORE_CASE)) { m ->
            out.durationMin = m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt(); " "
        }

        val low = text.lowercase()
        val everyDay = Regex("""\bevery\s+(day|daily)\b""").find(low)
        val everyWd = Regex("""\bevery\s+(sunday|monday|tuesday|wednesday|thursday|friday|saturday)\b""").find(low)
        when {
            everyDay != null -> {
                out.recurrence = "daily"
                text = text.replace(Regex("""every\s+(day|daily)""", RegexOption.IGNORE_CASE), " ")
            }
            everyWd != null -> {
                val wd = WEEKDAYS[everyWd.groupValues[1]]!!
                out.recurrence = "weekly:$wd"
                out.dueDate = Dates.nextWeekday(wd)
                text = text.replace(Regex("""every\s+(sunday|monday|tuesday|wednesday|thursday|friday|saturday)""", RegexOption.IGNORE_CASE), " ")
                text = text.replace(Regex("""\b(tomorrow|today)\b""", RegexOption.IGNORE_CASE), " ")
            }
            Regex("""\bevery\s+week\b""").containsMatchIn(low) -> {
                out.recurrence = "weekly"
                text = text.replace(Regex("""every\s+week""", RegexOption.IGNORE_CASE), " ")
            }
            Regex("""\bevery\s+month\b""").containsMatchIn(low) -> {
                out.recurrence = "monthly"
                text = text.replace(Regex("""every\s+month""", RegexOption.IGNORE_CASE), " ")
            }
        }

        if (out.dueDate == null) {
            val mDate1 = Regex("""\b(\d{1,2})\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)(?:[a-z]*)\b""", RegexOption.IGNORE_CASE).find(text)
            val mDate2 = Regex("""\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)(?:[a-z]*)\s+(\d{1,2})\b""", RegexOption.IGNORE_CASE).find(text)
            val mWd = Regex("""\b(sunday|monday|tuesday|wednesday|thursday|friday|saturday)\b""", RegexOption.IGNORE_CASE).find(text)
            when {
                Regex("""\btomorrow\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> {
                    out.dueDate = Dates.addDays(Dates.today(), 1)
                    text = text.replace(Regex("""tomorrow""", RegexOption.IGNORE_CASE), " ")
                }
                Regex("""\btoday\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> {
                    out.dueDate = Dates.today()
                    text = text.replace(Regex("""today""", RegexOption.IGNORE_CASE), " ")
                }
                Regex("""\bnext week\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> {
                    out.dueDate = Dates.addDays(Dates.today(), 7)
                    text = text.replace(Regex("""next week""", RegexOption.IGNORE_CASE), " ")
                }
                mWd != null -> {
                    out.dueDate = Dates.nextWeekday(WEEKDAYS[mWd.groupValues[1].lowercase()]!!)
                    text = text.replace(Regex(mWd.groupValues[1], RegexOption.IGNORE_CASE), " ")
                }
                mDate1 != null -> {
                    out.dueDate = monthDay(MONTHS[mDate1.groupValues[2].lowercase()]!!, mDate1.groupValues[1].toInt())
                    text = text.replace(mDate1.value, " ")
                }
                mDate2 != null -> {
                    out.dueDate = monthDay(MONTHS[mDate2.groupValues[1].lowercase()]!!, mDate2.groupValues[2].toInt())
                    text = text.replace(mDate2.value, " ")
                }
            }
        }

        val mAmPm = Regex("""\b(?:at\s+)?(\d{1,2})(?::(\d{2}))?\s*(am|pm)\b""", RegexOption.IGNORE_CASE).find(text)
        val m24 = Regex("""\b([01]?\d|2[0-3]):([0-5]\d)\b""").find(text)
        when {
            mAmPm != null -> {
                var h = mAmPm.groupValues[1].toInt() % 12
                if (mAmPm.groupValues[3].lowercase() == "pm") h += 12
                out.dueTime = pad(h) + ":" + (mAmPm.groupValues[2].ifEmpty { "00" })
                text = text.replace(mAmPm.value, " ")
            }
            m24 != null -> {
                out.dueTime = pad(m24.groupValues[1].toInt()) + ":" + m24.groupValues[2]
                text = text.replace(m24.value, " ")
            }
        }

        out.title = text.replace(Regex("""\s+"""), " ").trim()
        return out
    }

    private fun monthDay(month: Int, day: Int): String {
        val now = java.util.Calendar.getInstance()
        val c = java.util.Calendar.getInstance()
        // Clamp impossible days ("31 feb") to the month's last day instead
        // of letting the lenient calendar roll them into the next month.
        fun clamped(year: Int): Int {
            val probe = java.util.Calendar.getInstance()
            probe.set(year, month, 1, 0, 0, 0)
            return minOf(day, probe.getActualMaximum(java.util.Calendar.DAY_OF_MONTH))
        }
        val year = now.get(java.util.Calendar.YEAR)
        c.set(year, month, clamped(year), 0, 0, 0)
        var ds = Dates.dstr(c)
        if (ds < Dates.today()) {
            c.set(year + 1, month, clamped(year + 1), 0, 0, 0)
            ds = Dates.dstr(c)
        }
        return ds
    }

    fun describe(p: Parsed): List<String> {
        val chips = mutableListOf<String>()
        if (p.dueDate != null) chips.add(
            "📅 " + when (p.dueDate) {
                Dates.today() -> "Today"
                Dates.addDays(Dates.today(), 1) -> "Tomorrow"
                else -> Dates.fmtDay(p.dueDate!!)
            }
        )
        if (p.dueTime != null) chips.add("🕐 " + p.dueTime)
        if (p.recurrence != null) chips.add("🔁 " + p.recurrence!!.replace("weekly:", "weekly · "))
        if (p.projectName != null) chips.add("#" + p.projectName)
        p.labelNames.forEach { chips.add("@$it") }
        if (p.priority < 4) chips.add("P" + p.priority)
        if (p.durationMin != null) chips.add("⏱ " + p.durationMin + "m")
        return chips
    }
}
