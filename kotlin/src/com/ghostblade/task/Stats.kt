package com.ghostblade.task

import java.util.Calendar

/* Productivity statistics over the completion log. Pure functions:
   every "current" date is passed in as [today] ("yyyy-MM-dd") so the
   results are deterministic and testable. */
object Stats {

    fun countOn(completions: List<CompletionItem>, date: String): Int =
        completions.count { it.date == date }

    /** Per-day counts for the last [n] days ending [today], newest first (today at index 0). */
    fun lastNDaysCounts(completions: List<CompletionItem>, today: String, n: Int = 7): List<Pair<String, Int>> =
        (0 until n).map { back ->
            val day = Dates.addDays(today, -back)
            day to countOn(completions, day)
        }

    /** Monday of the week containing [date]. */
    fun weekStart(date: String): String {
        val c = Dates.parse(date)
        c.firstDayOfWeek = Calendar.MONDAY
        // Days elapsed since Monday: Mon = 0 … Sun = 6.
        val sinceMonday = (c.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
        c.add(Calendar.DAY_OF_MONTH, -sinceMonday)
        return Dates.dstr(c)
    }

    private fun countInWeek(completions: List<CompletionItem>, monday: String): Int {
        val nextMonday = Dates.addDays(monday, 7)
        return completions.count { it.date >= monday && it.date < nextMonday }
    }

    /** Per-week counts for the last [n] weeks ending with this week,
     *  keyed by each week's Monday, newest first (today at index 0). */
    fun lastNWeeksCounts(completions: List<CompletionItem>, today: String, n: Int = 4): List<Pair<String, Int>> {
        val thisMonday = weekStart(today)
        return (0 until n).map { back ->
            val monday = Dates.addDays(thisMonday, -7 * back)
            monday to countInWeek(completions, monday)
        }
    }

    /** Consecutive days with at least one completion, ending today — or
     *  ending yesterday when today has none yet (the day is still pending,
     *  so it must not break the streak). */
    fun currentStreak(completions: List<CompletionItem>, today: String): Int {
        val days = completions.map { it.date }.toSet()
        var cursor = if (today in days) today else Dates.addDays(today, -1)
        var streak = 0
        while (cursor in days) {
            streak++
            cursor = Dates.addDays(cursor, -1)
        }
        return streak
    }

    /** Consecutive Monday-start weeks whose completion count reached
     *  [weeklyGoal], scanning backwards. The current week only counts if
     *  the goal is already met; otherwise the scan starts last week. */
    fun weeklyStreak(completions: List<CompletionItem>, today: String, weeklyGoal: Int): Int {
        if (weeklyGoal <= 0) return 0 // a non-positive goal would "meet" every empty week forever
        val thisMonday = weekStart(today)
        var cursor = if (countInWeek(completions, thisMonday) >= weeklyGoal) {
            thisMonday
        } else {
            Dates.addDays(thisMonday, -7)
        }
        var streak = 0
        while (countInWeek(completions, cursor) >= weeklyGoal) {
            streak++
            cursor = Dates.addDays(cursor, -7)
        }
        return streak
    }

    /** Completion counts per projectId, all time. A null key counts
     *  completions that belonged to no project (Inbox). Keys appear in
     *  first-seen order. */
    fun byProject(completions: List<CompletionItem>): Map<String?, Int> {
        val out = LinkedHashMap<String?, Int>()
        for (c in completions) out[c.projectId] = (out[c.projectId] ?: 0) + 1
        return out
    }

    /** Per-day counts for one project over the last [n] days ending
     *  [today], newest first (today at index 0), zero-filled. Pass a null [projectId]
     *  for Inbox completions. */
    fun projectLastNDays(
        completions: List<CompletionItem>,
        projectId: String?,
        today: String,
        n: Int = 7
    ): List<Pair<String, Int>> =
        lastNDaysCounts(completions.filter { it.projectId == projectId }, today, n)

    data class GoalProgress(val dailyDone: Int, val dailyGoal: Int, val dailyFraction: Float,
                            val weeklyDone: Int, val weeklyGoal: Int, val weeklyFraction: Float)

    /** Daily and weekly goal progress for the ring cards. [dailyDone] is
     *  completions on [today]; [weeklyDone] is completions in the current
     *  week (Monday of today's week through today). Fractions are
     *  done/goal clamped to 0f..1f; a non-positive goal gives fraction 0f. */
    fun goalProgress(completions: List<CompletionItem>, today: String, dailyGoal: Int, weeklyGoal: Int): GoalProgress {
        val dailyDone = countOn(completions, today)
        val monday = weekStart(today)
        val weeklyDone = completions.count { it.date >= monday && it.date <= today }
        fun fraction(done: Int, goal: Int): Float =
            if (goal <= 0) 0f else (done.toFloat() / goal.toFloat()).coerceIn(0f, 1f)
        return GoalProgress(
            dailyDone = dailyDone,
            dailyGoal = dailyGoal,
            dailyFraction = fraction(dailyDone, dailyGoal),
            weeklyDone = weeklyDone,
            weeklyGoal = weeklyGoal,
            weeklyFraction = fraction(weeklyDone, weeklyGoal)
        )
    }

    /** Group activity entries by their local calendar day. Input arrives
     *  newest-first: days come out newest first and entries within a day
     *  keep their input order. */
    fun groupActivitiesByDay(items: List<ActivityItem>): List<Pair<String, List<ActivityItem>>> {
        val byDay = LinkedHashMap<String, MutableList<ActivityItem>>()
        val cal = Calendar.getInstance()
        for (item in items) {
            cal.timeInMillis = item.at
            byDay.getOrPut(Dates.dstr(cal)) { ArrayList() }.add(item)
        }
        // "yyyy-MM-dd" sorts lexicographically, so descending = newest first
        // even if the input was not perfectly ordered.
        return byDay.entries
            .sortedByDescending { it.key }
            .map { it.key to it.value.toList() }
    }
}
