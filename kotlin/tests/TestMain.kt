import com.ghostblade.task.Dates
import com.ghostblade.task.Filters
import com.ghostblade.task.Parser
import com.ghostblade.task.ProjectItem
import com.ghostblade.task.LabelItem
import com.ghostblade.task.State
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

    println("----")
    println("$total checks, $failures failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}
