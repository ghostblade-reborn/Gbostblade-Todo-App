import com.ghostblade.task.ActivityItem
import com.ghostblade.task.CompletionItem
import com.ghostblade.task.Dates
import com.ghostblade.task.DisplayEngine
import com.ghostblade.task.DisplayFilter
import com.ghostblade.task.Filters
import com.ghostblade.task.GroupMode
import com.ghostblade.task.Karma
import com.ghostblade.task.Parser
import com.ghostblade.task.ProjectItem
import com.ghostblade.task.LabelItem
import com.ghostblade.task.SortMode
import com.ghostblade.task.State
import com.ghostblade.task.Stats
import com.ghostblade.task.TaskItem

var failures = 0
var total = 0
fun check(name: String, actual: Any?, expected: Any?) {
    total++
    if (actual == expected) println("PASS  $name")
    else { failures++; println("FAIL  $name -> expected [$expected] but got [$actual]") }
}

fun main() {
    val today = Dates.today()
    val tomorrow = Dates.addDays(today, 1)

    /* ---------- Parser ---------- */
    var p = Parser.parse("Buy milk")
    check("plain title", p.title, "Buy milk")
    check("plain date", p.dueDate, null)
    check("plain priority", p.priority, 4)

    p = Parser.parse("Gym tomorrow at 7am every day #Health p2 for 45m")
    check("combo title", p.title, "Gym")
    check("combo date", p.dueDate, tomorrow)
    check("combo time", p.dueTime, "07:00")
    check("combo recurrence", p.recurrence, "daily")
    check("combo project", p.projectName, "Health")
    check("combo priority", p.priority, 2)
    check("combo duration", p.durationMin, 45)

    p = Parser.parse("Call mom friday")
    check("weekday title", p.title, "Call mom")
    check("weekday date", p.dueDate, Dates.nextWeekday(5))

    p = Parser.parse("Submit report 5 jan p1")
    check("dmy title", p.title, "Submit report")
    check("dmy priority", p.priority, 1)
    check("dmy month", p.dueDate?.substring(5), "01-05")

    p = Parser.parse("Water plants every monday")
    check("weekly-x recurrence", p.recurrence, "weekly:1")
    check("weekly-x date", p.dueDate, Dates.nextWeekday(1))

    p = Parser.parse("Pay rent every month")
    check("monthly recurrence", p.recurrence, "monthly")

    p = Parser.parse("Team sync every week")
    check("weekly recurrence", p.recurrence, "weekly")

    p = Parser.parse("Meeting 18:30 tomorrow")
    check("24h time", p.dueTime, "18:30")
    check("24h date", p.dueDate, tomorrow)
    check("24h title", p.title, "Meeting")

    p = Parser.parse("Read book for 1h30m")
    check("duration 1h30m", p.durationMin, 90)

    p = Parser.parse("Deep work for 2h")
    check("duration 2h", p.durationMin, 120)

    p = Parser.parse("Task p3 @home @urgent")
    check("labels", p.labelNames, listOf("home", "urgent"))
    check("p3", p.priority, 3)
    check("label title", p.title, "Task")

    p = Parser.parse("Lunch at 12pm")
    check("12pm", p.dueTime, "12:00")
    p = Parser.parse("Lunch at 12am")
    check("12am", p.dueTime, "00:00")
    p = Parser.parse("Call at 7:30pm")
    check("7:30pm", p.dueTime, "19:30")

    p = Parser.parse("Flight next week")
    check("next week", p.dueDate, Dates.addDays(today, 7))

    /* ---------- Dates ---------- */
    check("addDays month edge", Dates.addDays("2026-01-31", 1), "2026-02-01")
    check("addDays year edge", Dates.addDays("2026-12-31", 1), "2027-01-01")
    check("addDays non-leap feb", Dates.addDays("2026-02-28", 1), "2026-03-01")
    check("addDays negative", Dates.addDays("2026-03-01", -1), "2026-02-28")
    check("fmtDay", Dates.fmtDay("2026-11-20"), "Fri 20 Nov")

    val daily = TaskItem(id = "x", title = "t", dueDate = "2026-11-20", recurrence = "daily")
    check("advance daily", Dates.advanceDate(daily), "2026-11-21")
    val weekly = TaskItem(id = "x", title = "t", dueDate = "2026-11-20", recurrence = "weekly")
    check("advance weekly", Dates.advanceDate(weekly), "2026-11-27")
    val monthly = TaskItem(id = "x", title = "t", dueDate = "2026-01-15", recurrence = "monthly")
    check("advance monthly", Dates.advanceDate(monthly), "2026-02-15")
    // 2026-11-20 is a Friday; weekly:3 = Wednesday -> next is 2026-11-25
    val w3 = TaskItem(id = "x", title = "t", dueDate = "2026-11-20", recurrence = "weekly:3")
    check("advance weekly:3", Dates.advanceDate(w3), "2026-11-25")
    val nw = Dates.nextWeekday(1)
    check("nextWeekday is monday", Dates.jsDayOfWeek(Dates.parse(nw)), 1)
    check("nextWeekday future", nw > today, true)

    /* ---------- Filters ---------- */
    val s = State()
    s.projects.add(ProjectItem("p1", "Work", "#fff"))
    s.labels.add(LabelItem("l1", "home"))
    val t1 = TaskItem(id = "a", title = "Fix bug", projectId = "p1", labelIds = mutableListOf("l1"), priority = 1, dueDate = today)
    check("f today&p1", Filters.eval(s, t1, "today & p1"), true)
    check("f today&p2", Filters.eval(s, t1, "today & p2"), false)
    check("f #work", Filters.eval(s, t1, "#work"), true)
    check("f @home", Filters.eval(s, t1, "@home"), true)
    check("f overdue", Filters.eval(s, t1, "overdue"), false)
    check("f text", Filters.eval(s, t1, "fix"), true)
    check("f or", Filters.eval(s, t1, "p2 | p1"), true)
    check("f upcoming", Filters.eval(s, t1, "upcoming"), false)
    val t2 = TaskItem(id = "b", title = "Someday", dueDate = null)
    check("f no date", Filters.eval(s, t2, "no date"), true)
    val t3 = TaskItem(id = "c", title = "Old", dueDate = Dates.addDays(today, -2), recurrence = "daily")
    check("f overdue2", Filters.eval(s, t3, "overdue"), true)
    check("f recurring", Filters.eval(s, t3, "recurring"), true)

    /* ---------- Parser / Dates hardening ---------- */
    p = Parser.parse("Pay rent 31 feb")
    check("feb31 stays in feb", p.dueDate?.substring(5, 7), "02")
    check("feb31 clamps to month end", listOf("02-28", "02-29").contains(p.dueDate?.substring(5)), true)
    val tBad = TaskItem(id = "x", title = "Bad weekday", dueDate = today, recurrence = "weekly:9")
    check("advance weekly:9 stays put", Dates.advanceDate(tBad), today)

    /* ---------- Dates validators (import sanitising) ---------- */
    check("valid date", Dates.isValidDateStr("2026-11-20"), true)
    check("valid leap day", Dates.isValidDateStr("2024-02-29"), true)
    check("invalid non-leap feb29", Dates.isValidDateStr("2026-02-29"), false)
    check("invalid feb31", Dates.isValidDateStr("2026-02-31"), false)
    check("invalid month13", Dates.isValidDateStr("2026-13-01"), false)
    check("invalid empty date", Dates.isValidDateStr(""), false)
    check("invalid text date", Dates.isValidDateStr("tomorrow"), false)
    check("invalid unpadded date", Dates.isValidDateStr("2026-2-9"), false)
    check("valid time", Dates.isValidTimeStr("07:30"), true)
    check("valid time 23:59", Dates.isValidTimeStr("23:59"), true)
    check("invalid time 24:00", Dates.isValidTimeStr("24:00"), false)
    check("invalid time 7:30", Dates.isValidTimeStr("7:30"), false)
    check("invalid time 12:60", Dates.isValidTimeStr("12:60"), false)

    /* ---------- Parser deadlines (Team Two) ---------- */
    p = Parser.parse("Task deadline 5 jan")
    check("deadline title", p.title, "Task")
    check("deadline month", p.deadline?.substring(5), "01-05")
    check("deadline leaves dueDate empty", p.dueDate, null)
    p = Parser.parse("Call mom by tomorrow")
    check("by-deadline", p.deadline, tomorrow)
    check("by-deadline title", p.title, "Call mom")
    p = Parser.parse("Stand by me")
    check("by without date ignored", p.deadline, null)
    check("by without date title", p.title, "Stand by me")
    p = Parser.parse("Task tomorrow deadline jan 5")
    check("due+deadline due", p.dueDate, tomorrow)
    check("due+deadline deadline", p.deadline?.substring(5), "01-05")

    /* ---------- Karma (Team Two) ---------- */
    check("karma p1", Karma.pointsFor(1), 50)
    check("karma p2", Karma.pointsFor(2), 30)
    check("karma p3", Karma.pointsFor(3), 20)
    check("karma p4", Karma.pointsFor(4), 10)
    check("karma clamp", Karma.pointsFor(99), 10)
    check("karma level 0", Karma.levelFor(0).name, "Beginner")
    check("karma level 140 next", Karma.levelFor(140).pointsToNext, 360)
    check("karma level 500", Karma.levelFor(500).name, "Novice")
    check("karma level max", Karma.levelFor(60000).name, "Grandmaster")
    check("karma level max next", Karma.levelFor(60000).nextName, null)
    check("karma level max progress", Karma.levelFor(60000).progress, 1f)

    /* ---------- Stats (Team Two) ---------- */
    fun comp(id: String, date: String, proj: String? = null) =
        CompletionItem(id, "t$id", "Task $id", proj, date, 0L)
    val comps = listOf(
        comp("1", Dates.addDays(today, -2), "pa"),
        comp("2", Dates.addDays(today, -1), "pa"),
        comp("3", Dates.addDays(today, -1), "pb"),
        comp("4", today, "pa")
    )
    check("stats countOn", Stats.countOn(comps, Dates.addDays(today, -1)), 2)
    check("stats last3 counts", Stats.lastNDaysCounts(comps, today, 3).map { it.second }, listOf(1, 2, 1))
    check("stats last3 dates", Stats.lastNDaysCounts(comps, today, 3).map { it.first },
        listOf(today, Dates.addDays(today, -1), Dates.addDays(today, -2)))
    check("stats weekStart friday", Stats.weekStart("2026-11-20"), "2026-11-16")
    check("stats weekStart monday", Stats.weekStart("2026-11-16"), "2026-11-16")
    check("stats last4weeks size", Stats.lastNWeeksCounts(comps, today, 4).size, 4)
    check("stats last4weeks sum", Stats.lastNWeeksCounts(comps, today, 4).sumOf { it.second }, 4)
    check("stats streak 3", Stats.currentStreak(comps, today), 3)
    check("stats streak gap", Stats.currentStreak(listOf(comp("9", Dates.addDays(today, -3))), today), 0)
    check("stats karma trend", Karma.trend(comps, today), listOf(1, 2, 1, 0, 0, 0, 0))
    check("stats byProject pa", Stats.byProject(comps)["pa"], 3)
    check("stats byProject pb", Stats.byProject(comps)["pb"], 1)
    check("stats projectLast3 pa", Stats.projectLastNDays(comps, "pa", today, 3).map { it.second }, listOf(1, 1, 1))
    val cal1 = Dates.parse(Dates.addDays(today, -1)); cal1.set(java.util.Calendar.HOUR_OF_DAY, 10)
    val cal2 = Dates.parse(today); cal2.set(java.util.Calendar.HOUR_OF_DAY, 9)
    val acts = listOf(
        ActivityItem("a2", "complete", "later day item", cal2.timeInMillis),
        ActivityItem("a1", "create", "earlier day item", cal1.timeInMillis)
    )
    val groupedActs = Stats.groupActivitiesByDay(acts)
    check("stats activity groups", groupedActs.map { it.first }, listOf(today, Dates.addDays(today, -1)))
    check("stats activity group sizes", groupedActs.map { it.second.size }, listOf(1, 1))
    // Correction Round 2 (F-1): entries carry their project into the
    // day groups (Reporting shows it right-aligned); legacy entries
    // constructed without one default to null.
    val actsProj = listOf(
        ActivityItem("a4", "complete", "done in project", cal2.timeInMillis, "pa"),
        ActivityItem("a3", "create", "added in project", cal2.timeInMillis, "pa")
    )
    check("activity projectId kept in group", Stats.groupActivitiesByDay(actsProj)[0].second.map { it.projectId }, listOf("pa", "pa"))
    check("activity projectId default null", acts[0].projectId, null)
    // F-1 paging: grouping a 150-entry page of a longer log keeps all
    // paged entries (the screen pages instead of truncating at 150).
    val longLog = (1..200).map { i -> ActivityItem("x$i", "create", "entry $i", cal2.timeInMillis - i * 1000L) }
    check("activity page keeps 150", Stats.groupActivitiesByDay(longLog.take(150)).sumOf { it.second.size }, 150)

    /* ---------- Display engine (Team Two) ---------- */
    val ds = State()
    ds.projects.add(ProjectItem("pa", "Alpha", "#fff"))
    val dOver = TaskItem(id = "o", title = "Over", priority = 2, dueDate = Dates.addDays(today, -1))
    val dToday = TaskItem(id = "t", title = "Tod", priority = 1, dueDate = today, projectId = "pa")
    val dTom = TaskItem(id = "tm", title = "Tom", priority = 3, dueDate = tomorrow)
    val dNone = TaskItem(id = "n", title = "None", priority = 4, dueDate = null)
    val dAll = listOf(dNone, dTom, dToday, dOver)
    check("display filter range excludes nodate",
        DisplayEngine.applyFilter(dAll, DisplayFilter(dateFrom = today, dateTo = tomorrow)).map { it.id },
        listOf("tm", "t"))
    check("display filter priority",
        DisplayEngine.applyFilter(dAll, DisplayFilter(priorities = setOf(1, 4))).map { it.id },
        listOf("n", "t"))
    check("display sort date", DisplayEngine.sortTasks(dAll, SortMode.DATE).map { it.id }, listOf("o", "t", "tm", "n"))
    check("display sort priority", DisplayEngine.sortTasks(dAll, SortMode.PRIORITY).map { it.id }, listOf("t", "o", "tm", "n"))
    check("display sort title", DisplayEngine.sortTasks(dAll, SortMode.TITLE).map { it.id }, listOf("n", "o", "t", "tm"))
    check("display sort manual stable", DisplayEngine.sortTasks(dAll, SortMode.MANUAL).map { it.id }, listOf("n", "tm", "t", "o"))
    val gDate = DisplayEngine.groupTasks(ds, dAll, GroupMode.DATE)
    check("display group date keys", gDate.map { it.key }, listOf("overdue", "today", "tomorrow", "nodate"))
    val gProj = DisplayEngine.groupTasks(ds, dAll, GroupMode.PROJECT)
    check("display group project keys", gProj.map { it.key }, listOf("inbox", "pa"))
    check("display group project inbox size", gProj[0].tasks.size, 3)
    val gPri = DisplayEngine.groupTasks(ds, dAll, GroupMode.PRIORITY)
    check("display group priority keys", gPri.map { it.key }, listOf("p1", "p2", "p3", "p4"))
    check("display group none", DisplayEngine.groupTasks(ds, dAll, GroupMode.NONE).size, 1)

    /* ---------- Correction Round 1 (Team Two) ---------- */
    // R1-1 SMART sort: overdue oldest-first -> today (timed first) -> future by date -> no date
    val sOverOld = TaskItem(id = "oo", title = "OO", priority = 4, dueDate = Dates.addDays(today, -3))
    val sOverNew = TaskItem(id = "on", title = "ON", priority = 1, dueDate = Dates.addDays(today, -1))
    val sTimed = TaskItem(id = "tt", title = "TT", priority = 3, dueDate = today, dueTime = "09:00")
    val sPlain = TaskItem(id = "tp", title = "TP", priority = 1, dueDate = today)
    val sFar = TaskItem(id = "ff", title = "FF", priority = 1, dueDate = Dates.addDays(today, 2))
    val sNear = TaskItem(id = "fn", title = "FN", priority = 4, dueDate = tomorrow)
    val sNone = TaskItem(id = "nn", title = "NN", priority = 2, dueDate = null)
    check("smart sort order",
        DisplayEngine.sortTasks(listOf(sNone, sFar, sPlain, sOverNew, sTimed, sNear, sOverOld), SortMode.SMART).map { it.id },
        listOf("oo", "on", "tt", "tp", "fn", "ff", "nn"))
    // R1-2 label + assignee filter dimensions
    val lOne = TaskItem(id = "l1t", title = "L1", labelIds = mutableListOf("l1"), assignee = "me")
    val lTwo = TaskItem(id = "l2t", title = "L2", labelIds = mutableListOf("l2"), assignee = "sam")
    val lNone = TaskItem(id = "lnt", title = "LN")
    check("filter label dimension",
        DisplayEngine.applyFilter(listOf(lOne, lTwo, lNone), DisplayFilter(labelIds = setOf("l1"))).map { it.id },
        listOf("l1t"))
    check("filter assignee dimension",
        DisplayEngine.applyFilter(listOf(lOne, lTwo, lNone), DisplayFilter(assignee = "me")).map { it.id },
        listOf("l1t"))
    // R1-3/R1-4 persisted display state defaults
    check("display default showCompleted", State().displayShowCompleted, true)
    check("display default layout", State().displayLayout, "list")
    // R1-9 goal progress
    val gp = Stats.goalProgress(comps, today, 5, 30)
    check("goal daily done", gp.dailyDone, 1)
    check("goal daily fraction", gp.dailyFraction, 0.2f)
    check("goal weekly done", gp.weeklyDone, 4)
    check("goal weekly fraction", gp.weeklyFraction, 4f / 30f)
    check("goal zero-goal fraction", Stats.goalProgress(comps, today, 0, 0).dailyFraction, 0f)
    // R1-5 !! priority syntax
    p = Parser.parse("Hnm !!1")
    check("!!1 priority", p.priority, 1)
    check("!!1 title", p.title, "Hnm")
    p = Parser.parse("Task !!3 p1")
    check("!! then p last wins", p.priority, 1)
    p = Parser.parse("Task !!5")
    check("!!5 not a token", p.priority, 4)
    check("!!5 stays in title", p.title, "Task !!5")
    // R1-6 token ranges
    val toks = Parser.parse("Hnm !!1").tokens
    check("token count !!1", toks.size, 1)
    check("token kind", toks[0].kind, "priority")
    check("token range", listOf(toks[0].start, toks[0].end), listOf(4, 7))
    check("token value", toks[0].value, "1")
    val combo = "Buy milk #Home p2 tomorrow"
    val ctoks = Parser.parse(combo).tokens
    check("token combo kinds", ctoks.map { it.kind }, listOf("project", "priority", "date"))
    check("token combo substrings", ctoks.all { combo.substring(it.start, it.end) == it.text }, true)

    println("----")
    println("$total checks, $failures failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}
