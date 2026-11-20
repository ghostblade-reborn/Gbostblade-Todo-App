package com.ghostblade.task

/** Natural-language quick-add parser — a faithful port of the JS parser.
 *  Understands: #project, @label, p1–p4 and !!1–!!4, "for 30m" / "for 1h30m",
 *  today / tomorrow / next week / weekday names / "5 jan" / "jan 5",
 *  times like "7am", "7:30pm", "18:30", and "every day|week|month|<weekday>". */
object Parser {

    /** One recognised token's span in the ORIGINAL input string, for
     *  rendering inline pills in Quick Add. [start, end) are offsets into
     *  the exact string passed to [parse]: input.substring(start, end)
     *  always equals [text]. [value] is the normalised value (ISO date for
     *  date/deadline, "1".."4" for priority, the bare name for
     *  project/label, the recurrence string, minutes for duration, the
     *  normalised "HH:MM" for time). */
    data class ParsedToken(val kind: String, val text: String, val start: Int, val end: Int, val value: String)

    data class Parsed(
        var title: String = "",
        var dueDate: String? = null,
        var dueTime: String? = null,
        var recurrence: String? = null,
        var projectName: String? = null,
        var labelNames: MutableList<String> = mutableListOf(),
        var priority: Int = 4,
        var durationMin: Int? = null,
        var deadline: String? = null,
        val tokens: List<ParsedToken> = emptyList()
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
        // Priority: p1–p4 and Todoist's !!1–!!4 in ONE left-to-right pass,
        // so when both syntaxes appear the LAST one in the string wins
        // (the old p-only pass already let the last pN win). !!n only
        // counts as a standalone whitespace-delimited token: "!!" alone,
        // !!0 and !!5 never match, and !!12 is not read as !!1.
        text = text.replace(Regex("""\bp([1-4])\b|(?<!\S)!!([1-4])(?!\S)""", RegexOption.IGNORE_CASE)) { m ->
            out.priority = m.groupValues[1].ifEmpty { m.groupValues[2] }.toInt(); " "
        }
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

        // Deadline: "deadline <date>" / "by <date>" — scanned BEFORE the
        // due-date pass so the phrase is consumed here and can never leak
        // into dueDate. A keyword not followed by a recognisable date
        // phrase is left in the title untouched (e.g. "stand by me").
        for (kw in Regex("""\b(deadline|by)\b""", RegexOption.IGNORE_CASE).findAll(text)) {
            val rest = text.substring(kw.range.last + 1)
            val phrase = deadlineDatePhrase(rest) ?: continue
            out.deadline = phrase.first
            text = text.removeRange(kw.range.first, kw.range.last + 1 + phrase.second)
            break
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
        return out.copy(tokens = computeTokens(input))
    }

    /** Locate every token [parse] recognised, as spans in the ORIGINAL
     *  [input]. This re-runs the recognition passes in parse()'s exact
     *  order over a length-preserving masked copy of the input: whenever a
     *  pass consumes a phrase, its characters are blanked to spaces in the
     *  mask (offsets never shift), so later passes see the same separators
     *  parse()'s single-space replacements produce, each occurrence is
     *  claimed at most once, and duplicates resolve left-to-right. It only
     *  computes ranges — parse()'s own mutations above remain the single
     *  source of truth for the parsed fields and the title. */
    private fun computeTokens(input: String): List<ParsedToken> {
        if (input.isEmpty()) return emptyList()
        val tokens = mutableListOf<ParsedToken>()
        val claimed = BooleanArray(input.length)

        fun masked(): String {
            val sb = StringBuilder(input.length)
            for (i in input.indices) sb.append(if (claimed[i]) ' ' else input[i])
            return sb.toString()
        }
        fun claim(start: Int, end: Int, kind: String?, value: String?) {
            if (kind != null && value != null && end > start) {
                tokens.add(ParsedToken(kind, input.substring(start, end), start, end, value))
            }
            for (i in start until end) claimed[i] = true
        }
        fun claimMatch(m: MatchResult, kind: String?, value: String?) =
            claim(m.range.first, m.range.last + 1, kind, value)
        fun claimAll(regex: Regex, kind: String?, valueFor: (MatchResult) -> String?) {
            // Matches are taken from one snapshot; claiming never shifts
            // offsets, so every range stays valid for the whole loop.
            for (m in regex.findAll(masked()).toList()) claimMatch(m, kind, valueFor(m))
        }
        fun claimAllLiteral(literal: String, kind: String, value: String) {
            if (literal.isEmpty()) return
            var from = 0
            while (true) {
                val idx = masked().indexOf(literal, from)
                if (idx < 0) return
                claim(idx, idx + literal.length, kind, value)
                from = idx + literal.length
            }
        }

        // #project, then @label — same order as parse().
        claimAll(Regex("""#([\p{L}\p{N}_-]+)"""), "project") { it.groupValues[1] }
        claimAll(Regex("""@([\p{L}\p{N}_-]+)"""), "label") { it.groupValues[1] }

        // Priority: pN and !!n merged in input order (each occurrence is
        // a pill; parse() lets the last one set the priority).
        run {
            val snap = masked()
            val pHits = Regex("""\bp([1-4])\b""", RegexOption.IGNORE_CASE).findAll(snap)
                .map { it to it.groupValues[1] }
            val bangHits = Regex("""(?<!\S)!!([1-4])(?!\S)""").findAll(snap)
                .map { it to it.groupValues[1] }
            for ((m, digit) in (pHits + bangHits).sortedBy { it.first.range.first }) {
                claimMatch(m, "priority", digit)
            }
        }

        // Durations — parse()'s three patterns, in its order.
        claimAll(Regex("""\bfor\s+(\d+)\s*h(?:ours?)?\s*(?:(\d+)\s*m(?:in(?:ute)?s?)?)?\b""", RegexOption.IGNORE_CASE), "duration") {
            (it.groupValues[1].toInt() * 60 + (it.groupValues[2].toIntOrNull() ?: 0)).toString()
        }
        claimAll(Regex("""\bfor\s+(\d+)\s*m(?:in(?:ute)?s?)?\b""", RegexOption.IGNORE_CASE), "duration") {
            it.groupValues[1].toInt().toString()
        }
        claimAll(Regex("""\b(\d+)\s*h\s*(\d+)\s*m\b""", RegexOption.IGNORE_CASE), "duration") {
            (it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt()).toString()
        }

        // Recurrence — same branch precedence as parse(): daily, then a
        // specific weekday, then week, then month.
        var dueAlreadySet = false
        run {
            val snap = masked()
            val everyDay = Regex("""\bevery\s+(day|daily)\b""", RegexOption.IGNORE_CASE).find(snap)
            val everyWd = Regex("""\bevery\s+(sunday|monday|tuesday|wednesday|thursday|friday|saturday)\b""", RegexOption.IGNORE_CASE).find(snap)
            when {
                everyDay != null ->
                    claimAll(Regex("""\bevery\s+(day|daily)\b""", RegexOption.IGNORE_CASE), "recurrence") { "daily" }
                everyWd != null -> {
                    claimAll(Regex("""\bevery\s+(sunday|monday|tuesday|wednesday|thursday|friday|saturday)\b""", RegexOption.IGNORE_CASE), "recurrence") {
                        "weekly:" + WEEKDAYS[it.groupValues[1].lowercase()]!!
                    }
                    dueAlreadySet = true
                    // parse() also strips a today/tomorrow next to a weekday
                    // recurrence; claim those words silently (no pill kind
                    // fits — the date comes from the recurrence itself) so
                    // the due-date pass below cannot claim them.
                    claimAll(Regex("""\b(tomorrow|today)\b""", RegexOption.IGNORE_CASE), null) { null }
                }
                Regex("""\bevery\s+week\b""", RegexOption.IGNORE_CASE).containsMatchIn(snap) ->
                    claimAll(Regex("""\bevery\s+week\b""", RegexOption.IGNORE_CASE), "recurrence") { "weekly" }
                Regex("""\bevery\s+month\b""", RegexOption.IGNORE_CASE).containsMatchIn(snap) ->
                    claimAll(Regex("""\bevery\s+month\b""", RegexOption.IGNORE_CASE), "recurrence") { "monthly" }
            }
        }

        // Deadline — first keyword followed by a real date phrase wins;
        // the pill spans keyword + date phrase, exactly what parse()
        // removes from the title.
        run {
            val snap = masked()
            for (kw in Regex("""\b(deadline|by)\b""", RegexOption.IGNORE_CASE).findAll(snap)) {
                val restStart = kw.range.last + 1
                val phrase = deadlineDatePhrase(snap.substring(restStart)) ?: continue
                claim(kw.range.first, restStart + phrase.second, "deadline", phrase.first)
                break
            }
        }

        // Due date — skipped when a weekday recurrence already set it,
        // and branch order mirrors parse()'s when: tomorrow, today,
        // next week, weekday name, "5 jan", "jan 5".
        if (!dueAlreadySet) {
            val snap = masked()
            val mDate1 = Regex("""\b(\d{1,2})\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)(?:[a-z]*)\b""", RegexOption.IGNORE_CASE).find(snap)
            val mDate2 = Regex("""\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)(?:[a-z]*)\s+(\d{1,2})\b""", RegexOption.IGNORE_CASE).find(snap)
            val mWd = Regex("""\b(sunday|monday|tuesday|wednesday|thursday|friday|saturday)\b""", RegexOption.IGNORE_CASE).find(snap)
            when {
                Regex("""\btomorrow\b""", RegexOption.IGNORE_CASE).containsMatchIn(snap) ->
                    claimAll(Regex("""tomorrow""", RegexOption.IGNORE_CASE), "date") { Dates.addDays(Dates.today(), 1) }
                Regex("""\btoday\b""", RegexOption.IGNORE_CASE).containsMatchIn(snap) ->
                    claimAll(Regex("""today""", RegexOption.IGNORE_CASE), "date") { Dates.today() }
                Regex("""\bnext week\b""", RegexOption.IGNORE_CASE).containsMatchIn(snap) ->
                    claimAll(Regex("""next week""", RegexOption.IGNORE_CASE), "date") { Dates.addDays(Dates.today(), 7) }
                mWd != null -> {
                    val value = Dates.nextWeekday(WEEKDAYS[mWd.groupValues[1].lowercase()]!!)
                    claimAll(Regex(mWd.groupValues[1], RegexOption.IGNORE_CASE), "date") { value }
                }
                mDate1 != null ->
                    claimAllLiteral(mDate1.value, "date",
                        monthDay(MONTHS[mDate1.groupValues[2].lowercase()]!!, mDate1.groupValues[1].toInt()))
                mDate2 != null ->
                    claimAllLiteral(mDate2.value, "date",
                        monthDay(MONTHS[mDate2.groupValues[1].lowercase()]!!, mDate2.groupValues[2].toInt()))
            }
        }

        // Time — am/pm wins over 24h, as in parse(); parse() removes the
        // matched literal, so identical repeats are claimed too.
        run {
            val snap = masked()
            val mAmPm = Regex("""\b(?:at\s+)?(\d{1,2})(?::(\d{2}))?\s*(am|pm)\b""", RegexOption.IGNORE_CASE).find(snap)
            val m24 = Regex("""\b([01]?\d|2[0-3]):([0-5]\d)\b""").find(snap)
            when {
                mAmPm != null -> {
                    var h = mAmPm.groupValues[1].toInt() % 12
                    if (mAmPm.groupValues[3].lowercase() == "pm") h += 12
                    claimAllLiteral(mAmPm.value, "time", pad(h) + ":" + mAmPm.groupValues[2].ifEmpty { "00" })
                }
                m24 != null ->
                    claimAllLiteral(m24.value, "time", pad(m24.groupValues[1].toInt()) + ":" + m24.groupValues[2])
            }
        }

        return tokens.sortedBy { it.start }
    }

    /** Match a date phrase at the very start of [rest] (the text right
     *  after a deadline keyword), using exactly the phrase forms the
     *  due-date pass understands. Returns (date, chars consumed from
     *  [rest]) or null when no date phrase follows. */
    private fun deadlineDatePhrase(rest: String): Pair<String, Int>? {
        fun anchored(rx: String): MatchResult? =
            Regex("""^\s*(?:$rx)""", RegexOption.IGNORE_CASE).find(rest)
        anchored("""\btomorrow\b""")?.let { return Dates.addDays(Dates.today(), 1) to it.range.last + 1 }
        anchored("""\btoday\b""")?.let { return Dates.today() to it.range.last + 1 }
        anchored("""\bnext week\b""")?.let { return Dates.addDays(Dates.today(), 7) to it.range.last + 1 }
        anchored("""\b(sunday|monday|tuesday|wednesday|thursday|friday|saturday)\b""")?.let {
            return Dates.nextWeekday(WEEKDAYS[it.groupValues[1].lowercase()]!!) to it.range.last + 1
        }
        anchored("""\b(\d{1,2})\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)(?:[a-z]*)\b""")?.let {
            return monthDay(MONTHS[it.groupValues[2].lowercase()]!!, it.groupValues[1].toInt()) to it.range.last + 1
        }
        anchored("""\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)(?:[a-z]*)\s+(\d{1,2})\b""")?.let {
            return monthDay(MONTHS[it.groupValues[1].lowercase()]!!, it.groupValues[2].toInt()) to it.range.last + 1
        }
        return null
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
        if (p.deadline != null) chips.add(
            "⚑ Deadline " + when (p.deadline) {
                Dates.today() -> "Today"
                Dates.addDays(Dates.today(), 1) -> "Tomorrow"
                else -> Dates.fmtDay(p.deadline!!)
            }
        )
        if (p.recurrence != null) chips.add("🔁 " + p.recurrence!!.replace("weekly:", "weekly · "))
        if (p.projectName != null) chips.add("#" + p.projectName)
        p.labelNames.forEach { chips.add("@$it") }
        if (p.priority < 4) chips.add("P" + p.priority)
        if (p.durationMin != null) chips.add("⏱ " + p.durationMin + "m")
        return chips
    }
}
