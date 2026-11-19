package com.ghostblade.task

import android.content.Context
import org.json.JSONObject
import java.io.File

/** App state: loaded/saved as one JSON file in internal storage
 *  (same shape as the JS editions' localStorage state). */
object Store {
    lateinit var state: State
        private set
    private lateinit var appCtx: Context
    private const val FILE = "gbt_state.json"

    val ctx: Context get() = appCtx

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        val f = File(appCtx.filesDir, FILE)
        state = if (f.exists()) {
            try { State.fromJson(JSONObject(f.readText(Charsets.UTF_8))) }
            catch (e: Exception) { State() }
        } else State()
        if (!state.seeded) seed()
        autoBackup()
        save()
        Db.init(appCtx)
        Db.migrateIfNeeded(state)
        Db.rollover(state)
    }

    fun save() {
        try {
            File(appCtx.filesDir, FILE).writeText(state.toJson().toString(), Charsets.UTF_8)
        } catch (e: Exception) { /* storage full etc. — state survives in memory */ }
        syncWidget()
    }

    /** Mirror today's tasks into the home-screen widget's SharedPreferences. */
    fun syncWidget() {
        val todayList = state.tasks
            .filter { it.dueDate == Dates.today() || Dates.isOverdue(it) }
            .sortedWith(taskComparator)
        val json = JSONObject()
        json.put("open", todayList.size)
        json.put("done", completionsOn(Dates.today()))
        val lines = org.json.JSONArray()
        todayList.take(6).forEach { lines.put((if (Dates.isOverdue(it)) "⚠ " else "• ") + it.title) }
        json.put("lines", lines)
        appCtx.getSharedPreferences(TaskWidget.PREFS, Context.MODE_PRIVATE).edit()
            .putString("data", json.toString()).apply()
        TaskWidget.updateAll(appCtx)
    }

    val taskComparator = Comparator<TaskItem> { a, b ->
        val da = a.dueDate ?: "9999"
        val db = b.dueDate ?: "9999"
        when {
            da != db -> da.compareTo(db)
            a.priority != b.priority -> a.priority - b.priority
            else -> a.createdAt.compareTo(b.createdAt)
        }
    }

    fun sortTasks(list: List<TaskItem>): List<TaskItem> = list.sortedWith(taskComparator)

    fun byId(id: String): TaskItem? = state.tasks.find { it.id == id }

    fun projByName(name: String): ProjectItem {
        var p = state.projects.find { it.name.lowercase() == name.lowercase() }
        if (p == null) {
            p = ProjectItem(uid(), name)
            state.projects.add(p)
        }
        return p
    }

    fun labelByName(name: String): LabelItem {
        var l = state.labels.find { it.name.lowercase() == name.lowercase() }
        if (l == null) {
            l = LabelItem(uid(), name)
            state.labels.add(l)
        }
        return l
    }

    fun logActivity(type: String, text: String) {
        state.activities.add(0, ActivityItem(uid(), type, text, System.currentTimeMillis()))
        if (state.activities.size > 2000) {
            state.activities = state.activities.take(2000).toMutableList()
        }
    }

    fun completionsOn(date: String): Int = state.completions.count { it.date == date }

    fun currentStreak(): Int {
        var streak = 0
        var d = Dates.today()
        if (completionsOn(d) == 0) d = Dates.addDays(d, -1)
        while (completionsOn(d) > 0) {
            streak++
            d = Dates.addDays(d, -1)
        }
        return streak
    }

    fun snapshot(): String = state.toJson().toString()

    fun importData(text: String): Boolean {
        return try {
            val d = JSONObject(text)
            if (!d.has("tasks")) return false
            val fresh = State.fromJson(d)
            state.tasks = fresh.tasks
            state.projects = fresh.projects
            state.labels = fresh.labels
            state.filters = fresh.filters
            state.completions = fresh.completions
            state.activities = fresh.activities
            state.tasks.forEach { Reminders.sync(appCtx, it); Reminders.syncGeo(appCtx, it) }
            save()
            Db.rebuildFromState(state)
            true
        } catch (e: Exception) { false }
    }

    fun autoBackup() {
        val today = Dates.today()
        if (state.lastBackupDay == today) return
        state.lastBackupDay = today
        state.backups.add(0, BackupItem(uid(), System.currentTimeMillis(), true, snapshot()))
        if (state.backups.size > 10) state.backups = state.backups.take(10).toMutableList()
    }

    private fun seed() {
        state.seeded = true
        val must = projByName("Must")
        val defs = listOf(
            "10 minutes Deep Breath 🫁 Meditation Must",
            "10 minutes Workout Must",
            "Writing 1 YouTube Script Must",
            "Make at least One Ai Video"
        )
        defs.forEachIndexed { i, title ->
            state.tasks.add(
                TaskItem(
                    id = uid() + i, title = title, projectId = must.id,
                    priority = 1, dueDate = Dates.today(), recurrence = "daily",
                    createdAt = System.currentTimeMillis()
                )
            )
        }
        listOf(
            "Priority 1" to "p1", "Overdue" to "overdue",
            "No due date" to "no date", "Today & Priority 1" to "today & p1"
        ).forEach { (n, q) -> state.filters.add(FilterItem(uid() + n, n, q)) }
        logActivity("seed", "GhostBlade Task ready")
    }

    /* ---------- task actions ---------- */

    fun addTaskFromParse(p: Parser.Parsed, base: TaskItem? = null): TaskItem {
        val t = base ?: TaskItem(id = uid(), title = "Untitled task")
        t.title = p.title.ifEmpty { "Untitled task" }
        t.priority = p.priority
        t.dueDate = p.dueDate ?: t.dueDate
        t.dueTime = p.dueTime ?: t.dueTime
        t.recurrence = p.recurrence ?: t.recurrence
        t.durationMin = p.durationMin ?: t.durationMin
        if (p.projectName != null) t.projectId = projByName(p.projectName!!).id
        p.labelNames.forEach { n -> t.labelIds.add(labelByName(n).id) }
        if (state.tasks.none { it.id == t.id }) state.tasks.add(t)
        logActivity("create", "Added \"" + t.title + "\"")
        Reminders.sync(appCtx, t)
        save()
        return t
    }

    /** Returns a user-facing result message. */
    fun completeTask(id: String): String {
        val i = state.tasks.indexOfFirst { it.id == id }
        if (i < 0) return ""
        val t = state.tasks[i]
        val comp = CompletionItem(uid(), id, t.title, t.projectId, Dates.today(), System.currentTimeMillis())
        state.completions.add(0, comp)
        Db.logCompletion(comp)
        logActivity("complete", "Completed \"" + t.title + "\"")
        return if (t.recurrence != null) {
            t.dueDate = Dates.advanceDate(t)
            Reminders.sync(appCtx, t)
            save()
            "Done ✅ Next: " + Dates.fmtDay(t.dueDate!!)
        } else {
            Reminders.drop(appCtx, t)
            Reminders.clearGeo(appCtx, t)
            state.tasks.removeAt(i)
            save()
            "Done ✅"
        }
    }

    fun deleteTask(id: String): TaskItem? {
        val i = state.tasks.indexOfFirst { it.id == id }
        if (i < 0) return null
        val t = state.tasks[i]
        Reminders.drop(appCtx, t)
        Reminders.clearGeo(appCtx, t)
        logActivity("delete", "Deleted \"" + t.title + "\"")
        state.tasks.removeAt(i)
        save()
        return t
    }
}
