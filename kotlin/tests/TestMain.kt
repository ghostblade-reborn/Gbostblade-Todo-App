import com.ghostblade.task.ActivityItem
import com.ghostblade.task.ChecklistTemplate
import com.ghostblade.task.CompletionItem
import com.ghostblade.task.CountdownItem
import com.ghostblade.task.Dates
import com.ghostblade.task.DisplayEngine
import com.ghostblade.task.DisplayFilter
import com.ghostblade.task.Filters
import com.ghostblade.task.GroupMode
import com.ghostblade.task.HabitItem
import com.ghostblade.task.HabitLibrary
import com.ghostblade.task.HabitLog
import com.ghostblade.task.HabitLogic
import com.ghostblade.task.Karma
import com.ghostblade.task.Parser
import com.ghostblade.task.ProjectItem
import com.ghostblade.task.LabelItem
import com.ghostblade.task.RepeatLabels
import com.ghostblade.task.SortMode
import com.ghostblade.task.State
import com.ghostblade.task.Stats
import com.ghostblade.task.Subtask
import com.ghostblade.task.TaskItem
import org.json.JSONObject

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

    /* ---------- v5 Dates helpers ---------- */
    // 2026-11-21 is a Saturday.
    check("dow 2026-11-21 saturday", Dates.dayOfWeek("2026-11-21"), 6)
    check("dow monday", Dates.dayOfWeek("2026-11-16"), 1)
    check("dow friday", Dates.dayOfWeek("2026-11-20"), 5)
    check("dow sunday", Dates.dayOfWeek("2026-11-22"), 7)
    check("daysBetween forward", Dates.daysBetween("2026-11-01", "2026-11-21"), 20)
    check("daysBetween negative", Dates.daysBetween("2026-11-21", "2026-11-01"), -20)
    check("daysBetween same", Dates.daysBetween("2026-11-21", "2026-11-21"), 0)
    check("daysBetween year edge", Dates.daysBetween("2026-12-31", "2027-01-01"), 1)
    check("daysBetween year edge neg", Dates.daysBetween("2027-01-01", "2026-12-31"), -1)
    check("daysBetween leap", Dates.daysBetween("2024-02-28", "2024-03-01"), 2)

    /* ---------- v5 Models: v4.0 fixture loads with defaults ---------- */
    val v4Json = """{"tasks":[{"id":"t1","title":"Old task","notes":"","projectId":null,"labelIds":[],"priority":4,"dueDate":"2026-11-20","dueTime":null,"recurrence":null,"durationMin":null,"reminderOffsetMin":null,"subtasks":[],"createdAt":123,"status":"todo","locLat":null,"locLng":null,"sectionId":null,"deadline":null,"assignee":null}],"projects":[],"labels":[],"filters":[],"completions":[],"activities":[],"backups":[],"sections":[],"dailyGoal":5,"weeklyGoal":30,"karmaPoints":0,"seeded":true,"lastBackupDay":"","displayShowCompleted":true,"displayLayout":"list"}"""
    val v4State = State.fromJson(JSONObject(v4Json))
    check("v4 tasks load", v4State.tasks.size, 1)
    check("v4 task title", v4State.tasks[0].title, "Old task")
    check("v4 starred default", v4State.tasks[0].starred, false)
    check("v4 manualOrder default", v4State.tasks[0].manualOrder, null)
    check("v4 habits empty", v4State.habits.isEmpty(), true)
    check("v4 habitLogs empty", v4State.habitLogs.isEmpty(), true)
    check("v4 countdowns empty", v4State.countdowns.isEmpty(), true)
    check("v4 checklistTemplates empty", v4State.checklistTemplates.isEmpty(), true)
    check("v4 dockModules empty", v4State.dockModules.isEmpty(), true)

    /* ---------- v5 Models: round-trip ---------- */
    val v5 = State()
    val starTask = TaskItem(id = "st1", title = "Starred task", starred = true, manualOrder = 20)
    val plainTask = TaskItem(id = "pt1", title = "Plain task")
    v5.tasks.add(starTask); v5.tasks.add(plainTask)
    val habitRt = HabitItem(
        id = "h1", name = "Meditate", description = "desc", icon = "lotus-mind", color = "#bf5af2",
        kind = "build", freqType = "weekdays", freqDays = mutableListOf(1, 3, 5),
        freqCount = 1, freqPeriodDays = 7, tracking = "checklist", targetCount = 1, unit = "",
        checklist = mutableListOf(Subtask("s1", "Sit", false), Subtask("s2", "Breathe", false)),
        reminderPhrase = "Meditate now", reminderTimes = mutableListOf("07:00", "21:00"),
        createdAt = 1700000000000L, archived = false, order = 3, libraryKey = "meditation"
    )
    v5.habits.add(habitRt)
    v5.habitLogs.add(HabitLog(id = "l1", habitId = "h1", date = "2026-11-20", count = 0, doneItems = mutableListOf("s1", "s2"), done = true, at = 1700000001000L))
    v5.countdowns.add(CountdownItem(id = "c1", title = "Goal deadline", targetDate = "2027-07-20", color = "#ff9f0a", createdAt = 1700000002000L))
    v5.checklistTemplates.add(ChecklistTemplate(id = "ct1", name = "Grocery", items = listOf("Milk", "Eggs")))
    v5.dockModules.addAll(listOf("today", "habits", "browse"))
    val v5Restored = State.fromJson(JSONObject(v5.toJson().toString()))
    check("v5 rt tasks size", v5Restored.tasks.size, 2)
    check("v5 rt starred", v5Restored.tasks[0].starred, true)
    check("v5 rt manualOrder", v5Restored.tasks[0].manualOrder, 20)
    check("v5 rt plain starred", v5Restored.tasks[1].starred, false)
    check("v5 rt plain manualOrder", v5Restored.tasks[1].manualOrder, null)
    check("v5 rt habits size", v5Restored.habits.size, 1)
    val rh = v5Restored.habits[0]
    check("v5 rt habit name", rh.name, "Meditate")
    check("v5 rt habit description", rh.description, "desc")
    check("v5 rt habit icon", rh.icon, "lotus-mind")
    check("v5 rt habit color", rh.color, "#bf5af2")
    check("v5 rt habit kind", rh.kind, "build")
    check("v5 rt habit freqType", rh.freqType, "weekdays")
    check("v5 rt habit freqDays", rh.freqDays, mutableListOf(1, 3, 5))
    check("v5 rt habit tracking", rh.tracking, "checklist")
    check("v5 rt habit checklist ids", rh.checklist.map { it.id }, listOf("s1", "s2"))
    check("v5 rt habit checklist titles", rh.checklist.map { it.title }, listOf("Sit", "Breathe"))
    check("v5 rt habit reminderPhrase", rh.reminderPhrase, "Meditate now")
    check("v5 rt habit reminderTimes", rh.reminderTimes, mutableListOf("07:00", "21:00"))
    check("v5 rt habit createdAt", rh.createdAt, 1700000000000L)
    check("v5 rt habit archived", rh.archived, false)
    check("v5 rt habit order", rh.order, 3)
    check("v5 rt habit libraryKey", rh.libraryKey, "meditation")
    check("v5 rt habitLogs size", v5Restored.habitLogs.size, 1)
    val rl = v5Restored.habitLogs[0]
    check("v5 rt log habitId", rl.habitId, "h1")
    check("v5 rt log date", rl.date, "2026-11-20")
    check("v5 rt log doneItems", rl.doneItems, mutableListOf("s1", "s2"))
    check("v5 rt log done", rl.done, true)
    check("v5 rt log at", rl.at, 1700000001000L)
    check("v5 rt countdowns size", v5Restored.countdowns.size, 1)
    check("v5 rt countdown title", v5Restored.countdowns[0].title, "Goal deadline")
    check("v5 rt countdown target", v5Restored.countdowns[0].targetDate, "2027-07-20")
    check("v5 rt countdown color", v5Restored.countdowns[0].color, "#ff9f0a")
    check("v5 rt countdown createdAt", v5Restored.countdowns[0].createdAt, 1700000002000L)
    check("v5 rt checklistTemplates size", v5Restored.checklistTemplates.size, 1)
    check("v5 rt checklistTemplate name", v5Restored.checklistTemplates[0].name, "Grocery")
    check("v5 rt checklistTemplate items", v5Restored.checklistTemplates[0].items, listOf("Milk", "Eggs"))
    check("v5 rt dockModules", v5Restored.dockModules, mutableListOf("today", "habits", "browse"))

    /* ---------- v5 HabitLogic: isDueOn ---------- */
    val hDaily = HabitItem(id = "hd", name = "Daily", freqType = "daily")
    check("due daily any", HabitLogic.isDueOn(hDaily, "2026-11-17"), true)
    val hWeekdays = HabitItem(id = "hw", name = "MWF", freqType = "weekdays", freqDays = mutableListOf(1, 3, 5))
    check("due weekdays mon", HabitLogic.isDueOn(hWeekdays, "2026-11-16"), true)
    check("due weekdays tue", HabitLogic.isDueOn(hWeekdays, "2026-11-17"), false)
    check("due weekdays fri", HabitLogic.isDueOn(hWeekdays, "2026-11-20"), true)
    check("due weekdays sat", HabitLogic.isDueOn(hWeekdays, "2026-11-21"), false)
    val hMonthdays = HabitItem(id = "hm", name = "Monthdays", freqType = "monthdays", freqDays = mutableListOf(1, 15, 31))
    check("due monthdays 15", HabitLogic.isDueOn(hMonthdays, "2026-11-15"), true)
    check("due monthdays 16", HabitLogic.isDueOn(hMonthdays, "2026-11-16"), false)
    check("due monthdays 31 jan", HabitLogic.isDueOn(hMonthdays, "2026-01-31"), true)
    check("due monthdays feb28", HabitLogic.isDueOn(hMonthdays, "2026-02-28"), false)
    // createdAt pinned to 2026-01-01 (Senior Engineer fixture repair):
    // the wall-clock default postdates this fixture's Nov-2026 history,
    // and score() now bounds its window by the habit's existence —
    // the fixture always meant "a long-established habit".
    val hPeriod = HabitItem(id = "hp", name = "Period", freqType = "period", freqCount = 2, freqPeriodDays = 7,
        createdAt = Dates.parse("2026-01-01").timeInMillis)
    check("due period any", HabitLogic.isDueOn(hPeriod, "2026-11-17"), true)

    /* ---------- v5 HabitLogic: resolveDone via isDoneOn ---------- */
    val hTick = HabitItem(id = "ht", name = "Tick", tracking = "tick")
    val tickLogs = listOf(HabitLog(id = "tl1", habitId = "ht", date = "2026-11-20", done = true))
    check("done tick true", HabitLogic.isDoneOn(hTick, tickLogs, "2026-11-20"), true)
    check("done tick no log", HabitLogic.isDoneOn(hTick, tickLogs, "2026-11-19"), false)
    val hCount = HabitItem(id = "hc", name = "Water", tracking = "count", targetCount = 8, unit = "glasses")
    val countLogs = listOf(
        HabitLog(id = "cl1", habitId = "hc", date = "2026-11-20", count = 5, done = false),
        HabitLog(id = "cl2", habitId = "hc", date = "2026-11-21", count = 8, done = false)
    )
    check("done count below", HabitLogic.isDoneOn(hCount, countLogs, "2026-11-20"), false)
    check("done count at target", HabitLogic.isDoneOn(hCount, countLogs, "2026-11-21"), true)
    val hCheck = HabitItem(id = "hch", name = "Routine", tracking = "checklist",
        checklist = mutableListOf(Subtask("s1", "A", false), Subtask("s2", "B", false)))
    val checkLogs = listOf(
        HabitLog(id = "chl1", habitId = "hch", date = "2026-11-20", doneItems = mutableListOf("s1")),
        HabitLog(id = "chl2", habitId = "hch", date = "2026-11-21", doneItems = mutableListOf("s1", "s2"))
    )
    check("done checklist partial", HabitLogic.isDoneOn(hCheck, checkLogs, "2026-11-20"), false)
    check("done checklist full", HabitLogic.isDoneOn(hCheck, checkLogs, "2026-11-21"), true)

    /* ---------- v5 HabitLogic: streaks ---------- */
    // Weekday (Mon-Fri) habit: Fri/Thu/Wed done, Tue missed, Mon done.
    // Today is Saturday (not due, skipped) -> streak 3.
    // createdAt pinned (see hPeriod note): the year grid now renders
    // pre-existence days as NONE, and this fixture's history starts
    // 2026-11-16 — the habit must predate it.
    val hMF = HabitItem(id = "hmf", name = "MF", freqType = "weekdays", freqDays = mutableListOf(1, 2, 3, 4, 5),
        createdAt = Dates.parse("2026-01-01").timeInMillis)
    fun mfLog(d: String) = HabitLog(id = "mf$d", habitId = "hmf", date = d, done = true)
    val mfLogs = listOf(mfLog("2026-11-20"), mfLog("2026-11-19"), mfLog("2026-11-18"), mfLog("2026-11-16"))
    check("streak weekday gaps", HabitLogic.streak(hMF, mfLogs, "2026-11-21"), 3)
    check("bestStreak weekday", HabitLogic.bestStreak(hMF, mfLogs, "2026-11-21"), 3)
    // Today-due-not-done kindness (daily): today not done, yesterday+day before done -> 2.
    val hKind = HabitItem(id = "hk", name = "Kind", freqType = "daily")
    val kindLogs = listOf(
        HabitLog(id = "k1", habitId = "hk", date = "2026-11-20", done = true),
        HabitLog(id = "k2", habitId = "hk", date = "2026-11-19", done = true)
    )
    check("streak today pending kindness", HabitLogic.streak(hKind, kindLogs, "2026-11-21"), 2)
    // Daily streak including today when done.
    val kindLogs2 = kindLogs + HabitLog(id = "k3", habitId = "hk", date = "2026-11-21", done = true)
    check("streak daily incl today", HabitLogic.streak(hKind, kindLogs2, "2026-11-21"), 3)
    // Period streak: 2 done in trailing 7d on both 20th and 21st -> 2.
    val periodLogs = listOf(
        HabitLog(id = "p1", habitId = "hp", date = "2026-11-20", done = true),
        HabitLog(id = "p2", habitId = "hp", date = "2026-11-19", done = true)
    )
    check("streak period", HabitLogic.streak(hPeriod, periodLogs, "2026-11-21"), 2)

    /* ---------- v5 HabitLogic: score ---------- */
    // Window 2026-10-25..2026-11-21 is exactly 4 weeks.
    // Daily habit: 14 done of 28. Weekday habit: 6 done of 20 due. Archived ignored.
    // (14+6)/(28+20)=20/48=41.666.. -> 41.7
    val scoreDaily = HabitItem(id = "sd", name = "SD", freqType = "daily")
    val scoreLogs = mutableListOf<HabitLog>()
    var sdCursor = "2026-10-25"
    for (i in 0 until 14) { scoreLogs.add(HabitLog(id = "sd$i", habitId = "sd", date = sdCursor, done = true)); sdCursor = Dates.addDays(sdCursor, 1) }
    val scoreWeekday = HabitItem(id = "sw", name = "SW", freqType = "weekdays", freqDays = mutableListOf(1, 2, 3, 4, 5))
    listOf("2026-10-26", "2026-10-27", "2026-10-28", "2026-10-29", "2026-10-30", "2026-11-02").forEachIndexed { i, d ->
        scoreLogs.add(HabitLog(id = "sw$i", habitId = "sw", date = d, done = true))
    }
    val scoreArchived = HabitItem(id = "sa", name = "SA", freqType = "daily", archived = true)
    check("score mixed fixture", HabitLogic.score(listOf(scoreDaily, scoreWeekday, scoreArchived), scoreLogs, "2026-11-21"), 41.7)
    check("score empty", HabitLogic.score(emptyList(), emptyList(), "2026-11-21"), 0.0)
    val neverDue = HabitItem(id = "nd", name = "ND", freqType = "weekdays", freqDays = mutableListOf())
    check("score denominator zero", HabitLogic.score(listOf(neverDue), emptyList(), "2026-11-21"), 0.0)
    // Period score: satisfied only on 20th and 21st -> 2/28=7.142.. -> 7.1
    check("score period fixture", HabitLogic.score(listOf(hPeriod), periodLogs, "2026-11-21"), 7.1)

    /* ---------- v5 HabitLogic: gridCells ---------- */
    // createdAt pinned (see hPeriod note): "grid missed cell" expects
    // 2026-11-19 to be a real MISSED day, so the habit must have
    // existed before it — the wall-clock default (run date) does not.
    val gridHabit = HabitItem(id = "hg", name = "Grid", freqType = "daily",
        createdAt = Dates.parse("2026-01-01").timeInMillis)
    val gridLogs = listOf(HabitLog(id = "g1", habitId = "hg", date = "2026-11-20", done = true))
    val cells = HabitLogic.gridCells(gridHabit, gridLogs, "2026-11-21")
    check("grid size 371", cells.size, 371)
    check("grid first date", cells[0].date, Dates.addDays(Stats.weekStart("2026-11-21"), -52 * 7))
    check("grid last col contains today", cells.subList(364, 371).map { it.date }.contains("2026-11-21"), true)
    check("grid done cell", cells.first { it.date == "2026-11-20" }.state, HabitLogic.CellState.DONE)
    check("grid missed cell", cells.first { it.date == "2026-11-19" }.state, HabitLogic.CellState.MISSED)
    check("grid open cell", cells.first { it.date == "2026-11-21" }.state, HabitLogic.CellState.OPEN)
    check("grid future cell", cells.first { it.date == "2026-11-22" }.state, HabitLogic.CellState.FUTURE)
    val mfCells = HabitLogic.gridCells(hMF, mfLogs, "2026-11-21")
    check("grid rest cell saturday", mfCells.first { it.date == "2026-11-14" }.state, HabitLogic.CellState.REST)

    /* ---------- v5 HabitLogic: milestones ---------- */
    val msCreated = Dates.parse("2026-11-01").timeInMillis
    val hMs = HabitItem(id = "hms", name = "MS", freqType = "daily", createdAt = msCreated)
    val msLogs = mutableListOf<HabitLog>()
    var msCursor = "2026-11-01"
    for (i in 0 until 7) { msLogs.add(HabitLog(id = "ms$i", habitId = "hms", date = msCursor, done = true)); msCursor = Dates.addDays(msCursor, 1) }
    msLogs.add(HabitLog(id = "ms7", habitId = "hms", date = "2026-11-09", done = true))
    val ms = HabitLogic.milestones(hMs, msLogs, "2026-11-21")
    check("milestone started present", ms.any { it.label == "Started" && it.date == "2026-11-01" }, true)
    check("milestone 7day present", ms.any { it.label == "7-day streak" && it.date == "2026-11-07" }, true)
    check("milestone count small fixture", ms.size, 2)
    check("milestone best is 7", HabitLogic.bestStreak(hMs, msLogs, "2026-11-21"), 7)

    /* ---------- v5 HabitLibrary ---------- */
    check("library size 24", HabitLibrary.TEMPLATES.size, 24)
    check("library groups", HabitLibrary.GROUPS, listOf("Health", "Life", "Sports", "Mind"))
    check("library health count", HabitLibrary.byGroup("Health").size, 8)
    check("library life count", HabitLibrary.byGroup("Life").size, 6)
    check("library sports count", HabitLibrary.byGroup("Sports").size, 6)
    check("library mind count", HabitLibrary.byGroup("Mind").size, 4)
    check("library popular count", HabitLibrary.TEMPLATES.count { it.popular }, 8)
    check("library drink water", HabitLibrary.byKey("drink-water")?.name, "Drink Water")
    check("library drink water popular", HabitLibrary.byKey("drink-water")?.popular, true)
    check("library drink water color", HabitLibrary.byKey("drink-water")?.color, "#34c759")
    check("library meditation icon", HabitLibrary.byKey("meditation")?.icon, "lotus-mind")
    check("library no-phone kind quit", HabitLibrary.byKey("no-phone-first-hour")?.kind, "quit")
    check("library no-phone icon", HabitLibrary.byKey("no-phone-first-hour")?.icon, "phone-off")
    check("library unknown key", HabitLibrary.byKey("nope"), null)

    /* ---------- v5 RepeatLabels ---------- */
    val rlDate = "2026-11-21" // Saturday, day 21, November
    check("rl menu daily", RepeatLabels.menuLabel("daily", rlDate), "Daily")
    check("rl menu weekly", RepeatLabels.menuLabel("weekly", rlDate), "Weekly (Sat)")
    check("rl menu weekly:6", RepeatLabels.menuLabel("weekly:6", rlDate), "Weekly (Sat)")
    check("rl menu weekly:1", RepeatLabels.menuLabel("weekly:1", rlDate), "Weekly (Mon)")
    check("rl menu monthly", RepeatLabels.menuLabel("monthly", rlDate), "Monthly (The 21 day)")
    check("rl menu yearly", RepeatLabels.menuLabel("yearly", rlDate), "Yearly (on Nov 21)")
    check("rl menu weekday", RepeatLabels.menuLabel("weekday", rlDate), "Every Weekday (Mon–Fri)")
    check("rl menu custom", RepeatLabels.menuLabel("custom", rlDate), "Custom…")
    check("rl menu null", RepeatLabels.menuLabel(null, rlDate), "None")
    check("rl menu unknown", RepeatLabels.menuLabel("bogus", rlDate), "None")
    check("rl menu weekly fallback", RepeatLabels.menuLabel("weekly", null), "Weekly")
    check("rl menu monthly fallback", RepeatLabels.menuLabel("monthly", null), "Monthly")
    check("rl menu yearly fallback", RepeatLabels.menuLabel("yearly", null), "Yearly")
    check("rl summary daily", RepeatLabels.summaryLabel("daily", rlDate), "Daily")
    check("rl summary weekly", RepeatLabels.summaryLabel("weekly", rlDate), "Weekly on Sat")
    check("rl summary weekly:1", RepeatLabels.summaryLabel("weekly:1", rlDate), "Weekly on Mon")
    check("rl summary monthly", RepeatLabels.summaryLabel("monthly", rlDate), "Monthly on the 21 day")
    check("rl summary yearly", RepeatLabels.summaryLabel("yearly", rlDate), "Yearly on November 21")
    check("rl summary weekday", RepeatLabels.summaryLabel("weekday", rlDate), "Every Weekday (Mon–Fri)")
    check("rl summary custom", RepeatLabels.summaryLabel("custom", rlDate), "Custom")
    check("rl summary null", RepeatLabels.summaryLabel(null, rlDate), "None")

    println("----")
    println("$total checks, $failures failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}
