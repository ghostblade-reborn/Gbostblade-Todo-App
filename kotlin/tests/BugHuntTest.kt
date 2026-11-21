import com.ghostblade.task.Dates
import com.ghostblade.task.HabitItem
import com.ghostblade.task.HabitLog
import com.ghostblade.task.HabitLogic
import com.ghostblade.task.HabitReminders
import com.ghostblade.task.Karma
import com.ghostblade.task.RepeatLabels
import com.ghostblade.task.State
import com.ghostblade.task.Stats
import com.ghostblade.task.Store
import com.ghostblade.task.Subtask
import com.ghostblade.task.TaskItem
import com.ghostblade.task.CompletionItem
import org.json.JSONObject
import java.util.Calendar

/* BUG TEAM adversarial suite — separate from tests/TestMain.kt.
   check() asserts CORRECT behaviour: a FAIL line is a demonstrated bug.
   doc() asserts the OBSERVED (current) behaviour where the code's intent
   is ambiguous: it documents behaviour as evidence for the report and
   passes when the observation matches what the code actually does. */

var bhFailures = 0
var bhTotal = 0

fun check(name: String, actual: Any?, expected: Any?) {
    bhTotal++
    if (actual == expected) println("PASS  $name")
    else { bhFailures++; println("FAIL  $name -> expected [$expected] but got [$actual]") }
}

fun doc(name: String, actual: Any?, observed: Any?) {
    bhTotal++
    if (actual == observed) println("DOC   $name -> observed [$actual] (as documented)")
    else { bhFailures++; println("FAIL  $name -> doc expected observed [$observed] but got [$actual]") }
}

fun throws(name: String, block: () -> Unit): Boolean {
    return try { block(); println("DOC   $name -> no exception"); false }
    catch (e: Exception) { println("DOC   $name -> threw ${e.javaClass.simpleName}"); true }
}

fun millisOf(date: String): Long = Dates.parse(date).timeInMillis

fun habit(
    id: String = "h1",
    freqType: String = "daily",
    freqDays: List<Int> = emptyList(),
    freqCount: Int = 1,
    freqPeriodDays: Int = 7,
    tracking: String = "tick",
    targetCount: Int = 1,
    checklist: List<Subtask> = emptyList(),
    createdAt: Long = millisOf("2025-01-01"),
    archived: Boolean = false
): HabitItem = HabitItem(
    id = id, name = "Habit $id", freqType = freqType,
    freqDays = freqDays.toMutableList(), freqCount = freqCount,
    freqPeriodDays = freqPeriodDays, tracking = tracking,
    targetCount = targetCount, checklist = checklist.toMutableList(),
    createdAt = createdAt, archived = archived
)

fun log(habitId: String, date: String, done: Boolean = true, count: Int = 0, doneItems: List<String> = emptyList()): HabitLog =
    HabitLog(id = "l-$habitId-$date", habitId = habitId, date = date, count = count,
        doneItems = doneItems.toMutableList(), done = done)

fun logsOn(habitId: String, dates: List<String>): List<HabitLog> = dates.map { log(habitId, it) }

fun dateRange(from: String, n: Int): List<String> = (0 until n).map { Dates.addDays(from, it) }

fun main() {
    println("==== HabitLogic: streaks across boundaries ====")
    run {
        val h = habit()
        check("streak month boundary Jan30-Feb1",
            HabitLogic.streak(h, logsOn("h1", listOf("2026-01-30", "2026-01-31", "2026-02-01")), "2026-02-01"), 3)
        check("streak year boundary Dec30-Jan1",
            HabitLogic.streak(h, logsOn("h1", listOf("2025-12-30", "2025-12-31", "2026-01-01")), "2026-01-01"), 3)
        check("streak leap day 2024-02-28/29 + 03-01",
            HabitLogic.streak(h, logsOn("h1", listOf("2024-02-28", "2024-02-29", "2024-03-01")), "2024-03-01"), 3)
        check("streak non-leap Feb28 + Mar1",
            HabitLogic.streak(h, logsOn("h1", listOf("2026-02-28", "2026-03-01")), "2026-03-01"), 2)
        check("streak broken by missed due day",
            HabitLogic.streak(h, logsOn("h1", listOf("2026-03-01", "2026-03-02", "2026-03-04")), "2026-03-04"), 1)
        check("streak today still winnable",
            HabitLogic.streak(h, logsOn("h1", listOf("2026-03-01", "2026-03-02")), "2026-03-03"), 2)
        check("bestStreak 100 consecutive days",
            HabitLogic.bestStreak(h, logsOn("h1", dateRange("2026-01-01", 100)), "2026-04-10"), 100)
        check("streak 100 consecutive days",
            HabitLogic.streak(h, logsOn("h1", dateRange("2026-01-01", 100)), "2026-04-10"), 100)
    }

    println("==== HabitLogic: weekday / monthday due rules ====")
    run {
        val mon = habit(freqType = "weekdays", freqDays = listOf(1))
        check("weekdays Mon due on Monday", HabitLogic.isDueOn(mon, "2026-11-16"), true)
        check("weekdays Mon not due Tuesday", HabitLogic.isDueOn(mon, "2026-11-17"), false)
        check("weekdays Mon streak over 3 Mondays",
            HabitLogic.streak(mon, logsOn("h1", listOf("2026-11-02", "2026-11-09", "2026-11-16")), "2026-11-16"), 3)
        check("weekdays Mon streak broken by missed Monday",
            HabitLogic.streak(mon, logsOn("h1", listOf("2026-11-02", "2026-11-16")), "2026-11-16"), 1)

        val dead = habit(freqType = "weekdays", freqDays = emptyList())
        check("weekdays empty freqDays never due", HabitLogic.isDueOn(dead, "2026-11-16"), false)
        check("weekdays empty freqDays streak 0",
            HabitLogic.streak(dead, logsOn("h1", listOf("2026-11-16")), "2026-11-16"), 0)

        val m31 = habit(freqType = "monthdays", freqDays = listOf(31))
        check("monthdays 31 due Jan31", HabitLogic.isDueOn(m31, "2026-01-31"), true)
        check("monthdays 31 not due Feb28", HabitLogic.isDueOn(m31, "2026-02-28"), false)
        check("monthdays 31 streak skips February",
            HabitLogic.streak(m31, logsOn("h1", listOf("2026-01-31", "2026-03-31")), "2026-03-31"), 2)
        check("monthdays 31 streak in Feb still counts Jan",
            HabitLogic.streak(m31, logsOn("h1", listOf("2026-01-31")), "2026-02-28"), 1)
        val m29 = habit(freqType = "monthdays", freqDays = listOf(29))
        check("monthdays 29 due leap Feb29", HabitLogic.isDueOn(m29, "2024-02-29"), true)
        check("monthdays 29 not due non-leap Feb28", HabitLogic.isDueOn(m29, "2026-02-28"), false)
        val m0 = habit(freqType = "monthdays", freqDays = listOf(0, 32))
        check("monthdays 0/32 never due", HabitLogic.isDueOn(m0, "2026-01-31"), false)
        val unknown = habit(freqType = "yearly")
        // FIXED (P3-6): unknown freqType fails safe — never due — instead
        // of silently behaving as daily. (The loader also normalises it
        // to "daily" at the trust boundary.)
        check("unknown freqType never due", HabitLogic.isDueOn(unknown, "2026-11-21"), false)
    }

    println("==== HabitLogic: period quota edges ====")
    run {
        val p3 = habit(freqType = "period", freqCount = 3, freqPeriodDays = 7)
        check("period 3/7 streak counts satisfied run only",
            HabitLogic.streak(p3, logsOn("h1", listOf("2026-03-01", "2026-03-04", "2026-03-07")), "2026-03-07"), 1)
        val p1 = habit(freqType = "period", freqCount = 1, freqPeriodDays = 7)
        doc("period 1/7 single tick yields 7 satisfied days",
            HabitLogic.streak(p1, logsOn("h1", listOf("2026-03-01")), "2026-03-07"), 7)
        check("period 1/7 tick 8 days ago expired",
            HabitLogic.streak(p1, logsOn("h1", listOf("2026-03-01")), "2026-03-09"), 0)
        val p0 = habit(freqType = "period", freqCount = 0, freqPeriodDays = 7)
        check("period freqCount 0 never satisfied",
            HabitLogic.streak(p0, logsOn("h1", dateRange("2026-03-01", 7)), "2026-03-07"), 0)
        val pOver = habit(freqType = "period", freqCount = 8, freqPeriodDays = 7)
        check("period freqCount>periodDays never satisfied",
            HabitLogic.streak(pOver, logsOn("h1", dateRange("2026-03-01", 7)), "2026-03-07"), 0)
    }

    println("==== HabitLogic: count / checklist resolution ====")
    run {
        val c0 = habit(tracking = "count", targetCount = 0)
        check("count no log not done", HabitLogic.resolveDone(c0, null), false)
        // FIXED (P3-2): a target of 0 or less no longer completes the
        // habit by itself — the effective target is at least 1.
        check("count target 0 not done with empty log", HabitLogic.resolveDone(c0, log("h1", "2026-03-01", done = false, count = 0)), false)
        val cNeg = habit(tracking = "count", targetCount = -3)
        check("count target -3 not done with empty log", HabitLogic.resolveDone(cNeg, log("h1", "2026-03-01", done = false, count = 0)), false)
        val cBig = habit(tracking = "count", targetCount = Int.MAX_VALUE)
        check("count huge target met at MAX", HabitLogic.resolveDone(cBig, log("h1", "2026-03-01", done = false, count = Int.MAX_VALUE)), true)
        check("count huge target unmet at MAX-1", HabitLogic.resolveDone(cBig, log("h1", "2026-03-01", done = false, count = Int.MAX_VALUE - 1)), false)

        val items = listOf(Subtask("a", "A", false), Subtask("b", "B", false))
        val cl = habit(tracking = "checklist", checklist = items)
        check("checklist all items done", HabitLogic.resolveDone(cl, log("h1", "2026-03-01", done = false, doneItems = listOf("a", "b"))), true)
        check("checklist extra unknown ids tolerated", HabitLogic.resolveDone(cl, log("h1", "2026-03-01", done = false, doneItems = listOf("a", "b", "zzz"))), true)
        check("checklist stale ids after edit not done", HabitLogic.resolveDone(cl, log("h1", "2026-03-01", done = false, doneItems = listOf("x", "y"))), false)
        val clEmpty = habit(tracking = "checklist", checklist = emptyList())
        check("checklist empty checklist never done", HabitLogic.resolveDone(clEmpty, log("h1", "2026-03-01", done = false, doneItems = listOf("a"))), false)
    }

    println("==== HabitLogic: score ====")
    run {
        check("score no habits", HabitLogic.score(emptyList(), emptyList(), "2026-11-21"), 0.0)
        check("score all archived", HabitLogic.score(listOf(habit(archived = true)), emptyList(), "2026-11-21"), 0.0)
        check("score dead weekdays habit denom 0", HabitLogic.score(
            listOf(habit(freqType = "weekdays", freqDays = emptyList())), emptyList(), "2026-11-21"), 0.0)
        val old = habit()
        check("score rounding 1 of 28",
            HabitLogic.score(listOf(old), logsOn("h1", listOf("2026-03-28")), "2026-03-28"), 3.6)
        val mf = habit(freqType = "weekdays", freqDays = listOf(1, 2, 3, 4, 5))
        val mfLogs = dateRange("2026-03-02", 28).filter { Dates.dayOfWeek(it) in 1..5 }.map { log("h1", it) }
        check("score Mon-Fri all done = 100", HabitLogic.score(listOf(mf), mfLogs, "2026-03-29"), 100.0)
        // FIXED (P2-1): a habit created TODAY, ticked today. score now
        // honours createdAt via rangeStart, like streak/bestStreak/
        // milestones always did.
        val newborn = habit(createdAt = millisOf("2026-11-21"))
        check("score newborn habit ticked today",
            HabitLogic.score(listOf(newborn), logsOn("h1", listOf("2026-11-21")), "2026-11-21"), 100.0)
        // Orphan logs (habit deleted) must not leak into another habit's score.
        val withGhost = logsOn("h1", listOf("2026-03-28")) + log("ghost", "2026-03-28")
        check("score orphan logs ignored", HabitLogic.score(listOf(old), withGhost, "2026-03-28"), 3.6)
    }

    println("==== HabitLogic: year grid ====")
    run {
        val h = habit()
        val cells = HabitLogic.gridCells(h, emptyList(), "2026-11-21")
        check("grid has 371 cells", cells.size, 371)
        check("grid first cell is weekStart-364",
            cells.first().date, Dates.addDays(Stats.weekStart("2026-11-21"), -364))
        check("grid contains today exactly once", cells.count { it.date == "2026-11-21" }, 1)
        val leap = HabitLogic.gridCells(h, emptyList(), "2024-02-29")
        check("grid leap day cell is OPEN",
            leap.firstOrNull { it.date == "2024-02-29" }?.state, HabitLogic.CellState.OPEN)
        val mf = habit(freqType = "weekdays", freqDays = listOf(1, 2, 3, 4, 5))
        val mfCells = HabitLogic.gridCells(mf, emptyList(), "2026-11-21")
        check("grid Saturday cell is REST for Mon-Fri habit",
            mfCells.firstOrNull { it.date == "2026-11-21" }?.state, HabitLogic.CellState.REST)
        // FIXED (P2-2): habit created today -> past days render NONE
        // (pre-existence), never MISSED.
        val newborn = habit(createdAt = millisOf("2026-11-21"))
        val nCells = HabitLogic.gridCells(newborn, emptyList(), "2026-11-21")
        check("grid newborn habit has no MISSED cells",
            nCells.count { it.state == HabitLogic.CellState.MISSED }, 0)
    }

    println("==== HabitLogic: milestones ====")
    run {
        val h = habit(createdAt = millisOf("2026-03-01"))
        val week = HabitLogic.milestones(h, logsOn("h1", dateRange("2026-03-01", 7)), "2026-03-07")
        check("milestone Started first", week.first().label, "Started")
        check("milestone Started date", week.first().date, "2026-03-01")
        check("milestone 7-day streak present", week.any { it.label == "7-day streak" }, true)
        check("milestone 7-day streak date", week.firstOrNull { it.label == "7-day streak" }?.date, "2026-03-07")
        val hundred = HabitLogic.milestones(habit(createdAt = millisOf("2026-01-01")),
            logsOn("h1", dateRange("2026-01-01", 100)), "2026-04-10")
        check("milestone 100 ticks date", hundred.firstOrNull { it.label == "100 ticks" }?.date, "2026-04-10")
        check("milestone 30-day streak date", hundred.firstOrNull { it.label == "30-day streak" }?.date, "2026-01-30")
        // FIXED (P2-3): period habit — milestones() now runs over the
        // same satisfied days as streak() (12 here), so the earned
        // 7-day streak milestone appears.
        val per = habit(freqType = "period", freqCount = 3, freqPeriodDays = 7, createdAt = millisOf("2026-03-01"))
        val perLogs = logsOn("h1", listOf("2026-03-01", "2026-03-02", "2026-03-03", "2026-03-08", "2026-03-09", "2026-03-10"))
        check("period streak uses satisfied days", HabitLogic.streak(per, perLogs, "2026-03-14"), 12)
        check("period milestones include 7-day streak",
            HabitLogic.milestones(per, perLogs, "2026-03-14").any { it.label == "7-day streak" }, true)
    }

    println("==== RepeatLabels ====")
    run {
        check("rl null menu", RepeatLabels.menuLabel(null, "2026-11-23"), "None")
        check("rl null summary", RepeatLabels.summaryLabel(null, null), "None")
        check("rl daily", RepeatLabels.menuLabel("daily", null), "Daily")
        check("rl weekly from due date", RepeatLabels.menuLabel("weekly", "2026-11-23"), "Weekly (Mon)")
        check("rl weekly summary", RepeatLabels.summaryLabel("weekly", "2026-11-23"), "Weekly on Mon")
        check("rl weekly:0 Sun", RepeatLabels.menuLabel("weekly:0", null), "Weekly (Sun)")
        check("rl weekly:1 Mon", RepeatLabels.menuLabel("weekly:1", null), "Weekly (Mon)")
        check("rl weekly:6 Sat", RepeatLabels.menuLabel("weekly:6", null), "Weekly (Sat)")
        check("rl weekly:7 falls back to due weekday", RepeatLabels.menuLabel("weekly:7", "2026-11-23"), "Weekly (Mon)")
        check("rl weekly:7 no due date", RepeatLabels.menuLabel("weekly:7", null), "Weekly")
        check("rl weekly: empty suffix", RepeatLabels.menuLabel("weekly:", null), "Weekly")
        check("rl weekly:abc", RepeatLabels.menuLabel("weekly:abc", null), "Weekly")
        check("rl monthly menu", RepeatLabels.menuLabel("monthly", "2026-01-31"), "Monthly (The 31 day)")
        check("rl monthly summary", RepeatLabels.summaryLabel("monthly", "2026-01-31"), "Monthly on the 31 day")
        check("rl monthly no date", RepeatLabels.menuLabel("monthly", null), "Monthly")
        check("rl yearly menu", RepeatLabels.menuLabel("yearly", "2026-11-23"), "Yearly (on Nov 23)")
        check("rl yearly summary", RepeatLabels.summaryLabel("yearly", "2026-11-23"), "Yearly on November 23")
        check("rl weekday kind", RepeatLabels.menuLabel("weekday", null), "Every Weekday (Mon\u2013Fri)")
        check("rl custom menu", RepeatLabels.menuLabel("custom", null), "Custom\u2026")
        check("rl custom summary", RepeatLabels.summaryLabel("custom", null), "Custom")
        check("rl unknown kind", RepeatLabels.menuLabel("fortnightly", "2026-11-23"), "None")
        check("rl empty kind", RepeatLabels.summaryLabel("", "2026-11-23"), "None")
        check("rl garbage due date no crash", RepeatLabels.menuLabel("weekly", "garbage"), "Weekly")
        doc("rl monthly lenient Feb31", RepeatLabels.menuLabel("monthly", "2026-02-31"), "Monthly (The 3 day)")
    }

    println("==== Heatmap inputs (pure aggregation) ====")
    run {
        val comps = listOf(
            CompletionItem("a", "t", "x", null, "2025-12-31", 1L),
            CompletionItem("b", "t", "x", null, "2025-12-31", 2L),
            CompletionItem("c", "t", "x", null, "2026-01-01", 3L)
        )
        check("heatmap countOn empty", Stats.countOn(emptyList(), "2026-01-01"), 0)
        check("heatmap lastNDays across year boundary",
            Stats.lastNDaysCounts(comps, "2026-01-01", 3),
            listOf("2026-01-01" to 1, "2025-12-31" to 2, "2025-12-30" to 0))
        check("heatmap weekStart of Jan1 2026", Stats.weekStart("2026-01-01"), "2025-12-29")
        check("heatmap cols input daysBetween", Dates.daysBetween("2025-12-29", "2026-12-31"), 367)
        val dirty = comps + CompletionItem("z", "t", "x", null, "n/a", 4L) +
            CompletionItem("y", "t", "x", null, "2026/01/01", 5L)
        check("stats streak ignores garbage dates", Stats.currentStreak(dirty, "2026-01-01"), 2)
        // The Db.bestStreak() crash primitive: it calls Dates.addDays(prev, 1)
        // on every stored completion date (Db.kt bestStreak).
        check("addDays on slash date throws", throws("addDays 2026/01/01") { Dates.addDays("2026/01/01", 1) }, true)
        check("addDays on empty date throws", throws("addDays empty") { Dates.addDays("", 1) }, true)
    }

    println("==== manualOrder / taskComparator ====")
    run {
        val cmp = Store.taskComparator
        fun t(id: String, due: String?, prio: Int = 4, mo: Int? = null, created: Long = 0L) =
            TaskItem(id = id, title = id, priority = prio, dueDate = due, manualOrder = mo, createdAt = created)
        check("cmp null orders use due date", cmp.compare(t("a", "2026-11-22"), t("b", "2026-11-21")) > 0, true)
        check("cmp manual beats null even when due later",
            cmp.compare(t("a", "2026-12-01", mo = 50), t("b", "2026-11-01")) < 0, true)
        check("cmp duplicate manualOrder falls back to due date",
            cmp.compare(t("a", "2026-11-22", mo = 10), t("b", "2026-11-21", mo = 10)) > 0, true)
        check("cmp negative manualOrder first",
            cmp.compare(t("a", null, mo = -20), t("b", null, mo = 0)) < 0, true)
        // FIXED (P2-5): the comparator uses compareTo (no subtraction
        // overflow), and taskFromJson clamps priority into 1..4 so
        // extreme values can no longer arrive via import either.
        val extreme = cmp.compare(t("a", "2026-11-21", prio = Int.MAX_VALUE), t("b", "2026-11-21", prio = -1))
        check("cmp extreme priorities keep sign", extreme > 0, true)
        var threw = false
        var ordered = true
        try {
            val many = (0 until 40).map { i ->
                t("t$i", "2026-11-21", prio = if (i % 2 == 0) Int.MAX_VALUE else -2147483647, created = i.toLong())
            }
            val sorted = many.sortedWith(cmp)
            for (i in 0 until sorted.size - 1) {
                if (sorted[i].priority.toLong() > sorted[i + 1].priority.toLong()) ordered = false
            }
        } catch (e: IllegalArgumentException) { threw = true }
        doc("cmp TimSort on extreme priorities threw", threw, false)
        check("cmp extreme priority sort is ordered", ordered, true)
    }

    println("==== Countdown date math ====")
    run {
        check("cd today", Dates.daysBetween("2026-11-21", "2026-11-21"), 0)
        check("cd yesterday", Dates.daysBetween("2026-11-21", "2026-11-20"), -1)
        check("cd goal date", Dates.daysBetween("2026-11-21", "2027-07-20"), 241)
        check("cd far future", Dates.daysBetween("2026-11-21", "9999-12-31"), 2912118)
        check("cd invalid day chars throw", throws("daysBetween 2027-07-2x") {
            Dates.daysBetween("2026-11-21", "2027-07-2x") }, true)
        check("cd slash date throws", throws("daysBetween 2027/07/20") {
            Dates.daysBetween("2026-11-21", "2027/07/20") }, true)
        check("cd two-part date throws", throws("daysBetween 2027-07") {
            Dates.daysBetween("2026-11-21", "2027-07") }, true)
    }

    println("==== JSON round-trip / corruption ====")
    run {
        val v4 = JSONObject("""{
          "tasks":[{"id":"t1","title":"Old task","notes":"","projectId":null,"labelIds":[],
            "priority":2,"dueDate":"2026-11-21","dueTime":null,"recurrence":"daily",
            "durationMin":null,"reminderOffsetMin":null,"subtasks":[],"createdAt":1000,
            "status":"todo","locLat":null,"locLng":null,"sectionId":null,"deadline":null,"assignee":null}],
          "projects":[{"id":"p1","name":"Must","color":"#fff"}],
          "completions":[],"dailyGoal":5,"weeklyGoal":30,"karmaPoints":120,"seeded":true}""")
        val st = State.fromJson(v4)
        check("v4 fixture task loads", st.tasks.size, 1)
        check("v4 fixture habits default empty", st.habits.size, 0)
        check("v4 fixture countdowns default empty", st.countdowns.size, 0)
        check("v4 fixture dock default empty", st.dockModules.size, 0)
        check("v4 fixture starred default", st.tasks[0].starred, false)
        check("v4 fixture manualOrder default", st.tasks[0].manualOrder, null)

        val full = State()
        val hb = habit(freqDays = listOf(1, 3, 5))
        hb.reminderTimes = mutableListOf("08:00", "21:30")
        full.habits.add(hb)
        full.countdowns.add(com.ghostblade.task.CountdownItem("c1", "Launch", "2027-07-20"))
        val tk = TaskItem(id = "x1", title = "Star me"); tk.starred = true; tk.manualOrder = 30
        full.tasks.add(tk)
        val rt = State.fromJson(full.toJson())
        check("roundtrip habit freqDays", rt.habits[0].freqDays, listOf(1, 3, 5))
        check("roundtrip habit reminderTimes", rt.habits[0].reminderTimes, listOf("08:00", "21:30"))
        check("roundtrip countdown date", rt.countdowns[0].targetDate, "2027-07-20")
        check("roundtrip starred", rt.tasks[0].starred, true)
        check("roundtrip manualOrder", rt.tasks[0].manualOrder, 30)

        check("corrupt root tasks-as-object", State.fromJson(JSONObject("""{"tasks":{"a":1}}""")).tasks.size, 0)
        check("corrupt habits non-objects skipped",
            State.fromJson(JSONObject("""{"habits":[1,"x",null]}""")).habits.size, 0)
        check("empty json defaults dailyGoal", State.fromJson(JSONObject("{}")).dailyGoal, 5)
        check("json dailyGoal 0 stored raw", State.fromJson(JSONObject("""{"dailyGoal":0}""")).dailyGoal, 0)
        check("broken json string throws at JSONObject", throws("JSONObject broken") { JSONObject("{broken") }, true)

        val freqPoison = State.fromJson(JSONObject("""{"habits":[{"id":"hp","name":"P","freqType":"monthdays","freqDays":["x",3]}]}"""))
        check("freqDays string coerced to 0", freqPoison.habits[0].freqDays.contains(0), true)
        check("freqDays valid entry kept", freqPoison.habits[0].freqDays.contains(3), true)
        check("freqDays poison never due on day 0 basis",
            HabitLogic.isDueOn(freqPoison.habits[0], "2026-01-03"), true)

        // FIXED (P2-4): countdown targetDate and completion date are
        // validated on load with Dates.isValidDateStr, exactly like
        // task dueDate/deadline — poisoned entries are dropped, so the
        // unguarded consumers (renderCountdowns' daysBetween,
        // Db.bestStreak's addDays) can never receive a malformed date.
        val cdPoison = State.fromJson(JSONObject("""{"countdowns":[{"id":"c9","title":"Bad","targetDate":"not-a-date"}]}"""))
        check("countdown poison dropped on load", cdPoison.countdowns.size, 0)
        check("every loaded countdown date is valid",
            cdPoison.countdowns.all { Dates.isValidDateStr(it.targetDate) }, true)
        // Same class: completion dates load raw and reach Db.bestStreak().
        val compPoison = State.fromJson(JSONObject("""{"completions":[{"id":"k1","taskId":"t","title":"x","projectId":null,"date":"2026/01/01","at":1}]}"""))
        check("completion poison dropped on load", compPoison.completions.size, 0)

        check("karma negative points clamp", Karma.levelFor(-500).name, "Beginner")
        check("karma negative progress", Karma.levelFor(-500).progress, 0f)
    }

    println("==== HabitReminders ====")
    run {
        val today = Dates.today()
        val midnight = Dates.parse(today).timeInMillis
        fun at(date: String, hh: Int, mm: Int = 0): Long {
            val c = Dates.parse(date); c.set(Calendar.HOUR_OF_DAY, hh); c.set(Calendar.MINUTE, mm)
            c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0); return c.timeInMillis
        }
        val daily = habit()
        check("rem daily later today", HabitReminders.nextTrigger(daily, "08:00", midnight), at(today, 8))
        check("rem daily passed -> tomorrow",
            HabitReminders.nextTrigger(daily, "08:00", at(today, 9)), at(Dates.addDays(today, 1), 8))
        val tomorrowDow = Dates.dayOfWeek(Dates.addDays(today, 1))
        val wd = habit(freqType = "weekdays", freqDays = listOf(tomorrowDow))
        check("rem weekdays next due tomorrow",
            HabitReminders.nextTrigger(wd, "08:00", at(today, 9)), at(Dates.addDays(today, 1), 8))
        check("rem monthdays empty -> null",
            HabitReminders.nextTrigger(habit(freqType = "monthdays", freqDays = emptyList()), "08:00", midnight), null)
        check("rem weekdays empty -> null",
            HabitReminders.nextTrigger(habit(freqType = "weekdays", freqDays = emptyList()), "08:00", midnight), null)
        check("rem empty time -> null", HabitReminders.nextTrigger(daily, "", midnight), null)
        // FIXED (P3-3): malformed times are rejected (no alarm), never
        // leniently reinterpreted — "8" is not 08:00 and "25:99" does
        // not roll to next-day 02:39.
        check("rem time '8' rejected", HabitReminders.nextTrigger(daily, "8", midnight), null)
        check("rem time '25:99' rejected", HabitReminders.nextTrigger(daily, "25:99", midnight), null)
        check("rem alarmId roundtrip", HabitReminders.habitIdOf(HabitReminders.alarmId("h1", "08:00")), "h1")
        // FIXED (P3-4): the habit id is parsed from the end, so an id
        // containing ":" round-trips intact.
        check("rem colon habit id roundtrips", HabitReminders.habitIdOf(HabitReminders.alarmId("a:b", "08:00")), "a:b")
        check("rem duplicate times share alarm id",
            HabitReminders.alarmId("h1", "08:00") == HabitReminders.alarmId("h1", "08:00"), true)
    }

    println("==== Dates validation ====")
    run {
        check("valid leap 2024-02-29", Dates.isValidDateStr("2024-02-29"), true)
        check("invalid non-leap 2026-02-29", Dates.isValidDateStr("2026-02-29"), false)
        check("invalid Feb31", Dates.isValidDateStr("2026-02-31"), false)
        check("invalid month 13", Dates.isValidDateStr("2026-13-01"), false)
        check("invalid empty", Dates.isValidDateStr(""), false)
        check("daysBetween DST-safe span", Dates.daysBetween("2026-03-27", "2026-03-30"), 3)
    }

    println("----")
    println("BUGHUNT: $bhTotal checks, $bhFailures failures")
}
