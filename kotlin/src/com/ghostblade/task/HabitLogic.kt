package com.ghostblade.task

import java.util.Calendar
import kotlin.math.roundToLong

/* Pure habit logic — sibling of Stats/Karma. No Android imports.
   All dates are "yyyy-MM-dd" strings; every function takes today
   explicitly so results are deterministic and JVM-testable. */
object HabitLogic {

    enum class CellState { DONE, MISSED, OPEN, REST, FUTURE, NONE }

    data class Cell(val date: String, val state: CellState)

    data class Milestone(val date: String, val label: String)

    private fun dayOfMonth(date: String): Int =
        date.split("-").getOrNull(2)?.toIntOrNull() ?: 0

    private fun dateOfMillis(ms: Long): String {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        return Dates.dstr(c)
    }

    fun logFor(habit: HabitItem, logs: List<HabitLog>, date: String): HabitLog? =
        logs.firstOrNull { it.habitId == habit.id && it.date == date }

    fun isDueOn(habit: HabitItem, date: String): Boolean {
        return when (habit.freqType) {
            "daily" -> true
            "weekdays" -> Dates.dayOfWeek(date) in habit.freqDays
            "monthdays" -> dayOfMonth(date) in habit.freqDays
            "period" -> true
            // Unknown freqType (only reachable via a crafted import —
            // habitFromJson normalises it to "daily" at load): fail safe
            // and treat the habit as never due instead of silently daily.
            else -> false
        }
    }

    /** Resolved done state for a single log under the habit's tracking mode.
     *  tick: log.done as stored; count: count >= targetCount;
     *  checklist: checklist non-empty and doneItems contains every checklist id. */
    fun resolveDone(habit: HabitItem, log: HabitLog?): Boolean {
        if (log == null) return false
        return when (habit.tracking) {
            // A target of 0 or less (crafted import — the loader clamps
            // it to 1 as well) must not make every log "done": the
            // effective target is at least 1.
            "count" -> log.count >= maxOf(habit.targetCount, 1)
            "checklist" -> habit.checklist.isNotEmpty() &&
                log.doneItems.containsAll(habit.checklist.map { it.id })
            else -> log.done // "tick" and any unknown mode
        }
    }

    fun isDoneOn(habit: HabitItem, logs: List<HabitLog>, date: String): Boolean =
        resolveDone(habit, logFor(habit, logs, date))

    /** Period helper: a day is satisfied when the number of done days in
     *  the trailing freqPeriodDays window ending on [day] (inclusive)
     *  is at least freqCount. */
    private fun isPeriodSatisfied(habit: HabitItem, logs: List<HabitLog>, day: String): Boolean {
        if (habit.freqCount <= 0) return false
        val period = if (habit.freqPeriodDays > 0) habit.freqPeriodDays else 7
        var doneCount = 0
        for (back in 0 until period) {
            val d = Dates.addDays(day, -back)
            if (isDoneOn(habit, logs, d)) doneCount++
        }
        return doneCount >= habit.freqCount
    }

    fun streak(habit: HabitItem, logs: List<HabitLog>, today: String): Int {
        if (habit.freqType == "period") {
            // Period streak: consecutive satisfied days ending today.
            // If today is not yet satisfied, start the count at yesterday
            // (today is still winnable — its window can still fill).
            var cursor = if (isPeriodSatisfied(habit, logs, today)) today else Dates.addDays(today, -1)
            var count = 0
            var guard = 0
            while (guard < 3660 && isPeriodSatisfied(habit, logs, cursor)) {
                count++
                cursor = Dates.addDays(cursor, -1)
                guard++
            }
            return count
        }
        // Daily / weekdays / monthdays: walk backwards over DUE days only.
        // A due+missed day breaks the streak; a non-due day is skipped
        // (neither breaks nor extends). If today is due but not yet done,
        // start at yesterday (today is still winnable).
        var cursor = today
        if (isDueOn(habit, cursor) && !isDoneOn(habit, logs, cursor)) {
            cursor = Dates.addDays(cursor, -1)
        }
        var count = 0
        var guard = 0
        while (guard < 3660) {
            guard++
            if (!isDueOn(habit, cursor)) {
                cursor = Dates.addDays(cursor, -1)
                continue
            }
            if (isDoneOn(habit, logs, cursor)) {
                count++
                cursor = Dates.addDays(cursor, -1)
            } else {
                break
            }
        }
        return count
    }

    /** First day the habit can be held accountable for: its creation
     *  date, extended earlier by log evidence (imported history can
     *  predate the stored createdAt), never after [today] and never
     *  more than ~10 years back. Score, the year grid and the Habits
     *  header all bound their windows by this so a young habit is never
     *  charged for days before it existed. */
    fun existenceStart(habit: HabitItem, logs: List<HabitLog>, today: String): String =
        rangeStart(habit, logs, today)

    private fun rangeStart(habit: HabitItem, logs: List<HabitLog>, today: String): String {
        var start = dateOfMillis(habit.createdAt)
        if (start > today) start = today
        val earliestLog = logs.filter { it.habitId == habit.id }.minOfOrNull { it.date }
        if (earliestLog != null && earliestLog < start) start = earliestLog
        // Safety clamp: never scan more than ~10 years.
        val floor = Dates.addDays(today, -3660)
        if (start < floor) start = floor
        return start
    }

    fun bestStreak(habit: HabitItem, logs: List<HabitLog>, today: String): Int {
        if (habit.freqType == "period") {
            val start = rangeStart(habit, logs, today)
            var run = 0
            var best = 0
            var cursor = start
            while (cursor <= today) {
                if (isPeriodSatisfied(habit, logs, cursor)) {
                    run++
                    if (run > best) best = run
                } else {
                    run = 0
                }
                cursor = Dates.addDays(cursor, 1)
            }
            return best
        }
        val start = rangeStart(habit, logs, today)
        var run = 0
        var best = 0
        var cursor = start
        while (cursor <= today) {
            when {
                !isDueOn(habit, cursor) -> { /* neutral: run unchanged */ }
                isDoneOn(habit, logs, cursor) -> {
                    run++
                    if (run > best) best = run
                }
                else -> run = 0
            }
            cursor = Dates.addDays(cursor, 1)
        }
        return best
    }

    /** Habit Score % over the trailing 28 days (today-27 .. today),
     *  across all non-archived habits, rounded to one decimal.
     *  Denominator 0 -> 0.0. Days before a habit existed (its
     *  existenceStart, the same bound streak/bestStreak/milestones
     *  clamp by) count in neither numerator nor denominator — a habit
     *  created and ticked today scores 100, not 3.6.
     *  Period reading: for a period habit every day in the window counts
     *  toward the denominator (isDueOn is always true for period), and a
     *  day counts as done when the trailing-window quota is satisfied
     *  that day (isPeriodSatisfied) — not merely when that single day
     *  has a tick. This matches the period streak's notion of a
     *  satisfied day, so score and streak stay consistent. */
    fun score(habits: List<HabitItem>, logs: List<HabitLog>, today: String): Double {
        var numerator = 0
        var denominator = 0
        for (habit in habits) {
            if (habit.archived) continue
            val startBound = rangeStart(habit, logs, today)
            for (back in 0 until 28) {
                val day = Dates.addDays(today, -back)
                if (day < startBound) continue
                if (habit.freqType == "period") {
                    denominator++
                    if (isPeriodSatisfied(habit, logs, day)) numerator++
                } else {
                    if (!isDueOn(habit, day)) continue
                    denominator++
                    if (isDoneOn(habit, logs, day)) numerator++
                }
            }
        }
        if (denominator == 0) return 0.0
        val raw = numerator.toDouble() * 100.0 / denominator.toDouble()
        return (raw * 10.0).roundToLong() / 10.0
    }

    /** 371 cells = 53 columns x 7 rows, column-major (index = col*7 + row,
     *  row 0 = Monday). First column starts at Stats.weekStart(today)
     *  minus 52*7 days, so the last column is today's week and contains
     *  today. */
    fun gridCells(habit: HabitItem, logs: List<HabitLog>, today: String): List<Cell> {
        val firstMonday = Dates.addDays(Stats.weekStart(today), -52 * 7)
        // Days before the habit existed are NONE, never MISSED: a newly
        // adopted habit must not open showing a year of fake failures.
        // (existenceStart never exceeds the earliest log date, so a day
        // with a real log can never be swallowed by this bound.)
        val startBound = rangeStart(habit, logs, today)
        val out = ArrayList<Cell>(371)
        for (idx in 0 until 371) {
            val date = Dates.addDays(firstMonday, idx)
            val state = when {
                date > today -> CellState.FUTURE
                date < startBound -> CellState.NONE
                !isDueOn(habit, date) -> CellState.REST
                isDoneOn(habit, logs, date) -> CellState.DONE
                date == today -> CellState.OPEN
                else -> CellState.MISSED
            }
            out.add(Cell(date, state))
        }
        return out
    }

    /** Derived milestones, never stored. Streak milestones are computed
     *  with a single forward pass over due days: due+done extends the
     *  run, due+missed resets it, non-due days are neutral. Tick
     *  milestones count resolved done days in chronological order.
     *  Period habits: the streak run advances over SATISFIED days
     *  (isPeriodSatisfied) — the exact notion streak() reports — so the
     *  timeline can never contradict the streak on the same screen;
     *  tick milestones still count raw done days. */
    fun milestones(habit: HabitItem, logs: List<HabitLog>, today: String): List<Milestone> {
        val out = mutableListOf<Milestone>()
        val startedDate = dateOfMillis(habit.createdAt)
        out.add(Milestone(startedDate, "Started"))

        val start = rangeStart(habit, logs, today)
        var run = 0
        var best = 0
        var bestDate: String? = null
        var ticks = 0
        var sevenDate: String? = null
        var thirtyDate: String? = null
        var hundredDate: String? = null
        var threeSixFiveDate: String? = null

        val period = habit.freqType == "period"
        var cursor = start
        while (cursor <= today) {
            val due = isDueOn(habit, cursor)
            val done = isDoneOn(habit, logs, cursor)
            if (done) {
                ticks++
                if (ticks == 100) hundredDate = cursor
                if (ticks == 365) threeSixFiveDate = cursor
            }
            // A day extends the streak run when it is a "streak day":
            // for period habits a satisfied day, otherwise a due+done
            // day. Non-period non-due days are neutral (no reset);
            // every other non-streak day resets the run.
            val streakDay = if (period) isPeriodSatisfied(habit, logs, cursor) else (due && done)
            if (streakDay) {
                run++
                if (run == 7 && sevenDate == null) sevenDate = cursor
                if (run == 30 && thirtyDate == null) thirtyDate = cursor
                if (run > best) {
                    best = run
                    bestDate = cursor
                }
            } else if (period || due) {
                run = 0
            }
            cursor = Dates.addDays(cursor, 1)
        }

        if (sevenDate != null) out.add(Milestone(sevenDate, "7-day streak"))
        if (thirtyDate != null) out.add(Milestone(thirtyDate, "30-day streak"))
        if (hundredDate != null) out.add(Milestone(hundredDate, "100 ticks"))
        if (threeSixFiveDate != null) out.add(Milestone(threeSixFiveDate, "365 ticks"))
        if (best > 7 && bestDate != null) out.add(Milestone(bestDate, "Best streak beaten"))

        // Chronological order; stable sort keeps "Started" first on ties
        // because it was inserted first.
        return out.sortedBy { it.date }
    }
}
