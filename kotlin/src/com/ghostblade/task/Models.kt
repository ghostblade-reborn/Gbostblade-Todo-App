package com.ghostblade.task

import org.json.JSONArray
import org.json.JSONObject

/* Data model — JSON format is identical to the web/Android-JS editions,
   so sync files and backups are interchangeable between versions. */

data class Subtask(var id: String, var title: String, var done: Boolean)

data class TaskItem(
    var id: String,
    var title: String,
    var notes: String = "",
    var projectId: String? = null,
    var labelIds: MutableList<String> = mutableListOf(),
    var priority: Int = 4,
    var dueDate: String? = null,
    var dueTime: String? = null,
    var recurrence: String? = null,
    var durationMin: Int? = null,
    var reminderOffsetMin: Int? = null,
    var subtasks: MutableList<Subtask> = mutableListOf(),
    var createdAt: Long = System.currentTimeMillis(),
    var status: String = "todo",
    var locLat: Double? = null,
    var locLng: Double? = null,
    var sectionId: String? = null,
    var deadline: String? = null,
    // Single-user app: assignee is a free-form owner tag ("me" by default in Todoist).
    var assignee: String? = null,
    var starred: Boolean = false,
    var manualOrder: Int? = null
)

data class SectionItem(var id: String, var projectId: String, var name: String, var collapsed: Boolean = false, var order: Int = 0)
data class ProjectItem(var id: String, var name: String, var color: String = "#d9a441")
data class LabelItem(var id: String, var name: String)
data class FilterItem(var id: String, var name: String, var query: String)
data class CompletionItem(var id: String, var taskId: String, var title: String, var projectId: String?, var date: String, var at: Long)
data class ActivityItem(var id: String, var type: String, var text: String, var at: Long, var projectId: String? = null)
data class BackupItem(var id: String, var at: Long, var auto: Boolean, var data: String)
data class CommentItem(var id: String, var projectId: String?, var taskId: String?, var text: String, var at: Long)
data class TemplateItem(var id: String, var name: String, var projectName: String, var color: String, var sectionNames: List<String>, var taskTitles: List<String>)

data class HabitItem(
    var id: String,
    var name: String,
    var description: String = "",
    var icon: String = "circle",
    var color: String = "#d9a441",
    var kind: String = "build",
    var freqType: String = "daily",
    var freqDays: MutableList<Int> = mutableListOf(),
    var freqCount: Int = 1,
    var freqPeriodDays: Int = 7,
    var tracking: String = "tick",
    var targetCount: Int = 1,
    var unit: String = "",
    var checklist: MutableList<Subtask> = mutableListOf(),
    var reminderPhrase: String = "",
    var reminderTimes: MutableList<String> = mutableListOf(),
    var createdAt: Long = System.currentTimeMillis(),
    var archived: Boolean = false,
    var order: Int = 0,
    var libraryKey: String? = null
)

data class HabitLog(
    var id: String,
    var habitId: String,
    var date: String,
    var count: Int = 0,
    var doneItems: MutableList<String> = mutableListOf(),
    var done: Boolean = false,
    var at: Long = System.currentTimeMillis()
)

data class CountdownItem(
    var id: String,
    var title: String,
    var targetDate: String,
    var color: String = "#d9a441",
    var createdAt: Long = System.currentTimeMillis()
)

data class ChecklistTemplate(
    var id: String,
    var name: String,
    var items: List<String> = emptyList()
)

class State {
    var tasks = mutableListOf<TaskItem>()
    var projects = mutableListOf<ProjectItem>()
    var labels = mutableListOf<LabelItem>()
    var filters = mutableListOf<FilterItem>()
    var completions = mutableListOf<CompletionItem>()
    var activities = mutableListOf<ActivityItem>()
    var backups = mutableListOf<BackupItem>()
    var sections = mutableListOf<SectionItem>()
    var dailyGoal = 5
    var weeklyGoal = 30
    var karmaPoints = 0
    var quickAddChips = mutableListOf<String>()
    var quickAddHiddenChips = mutableListOf<String>()
    var comments = mutableListOf<CommentItem>()
    var templates = mutableListOf<TemplateItem>()
    var seeded = false
    var lastBackupDay = ""
    var displayShowCompleted: Boolean = true
    var displayLayout: String = "list"
    var habits = mutableListOf<HabitItem>()
    var habitLogs = mutableListOf<HabitLog>()
    var countdowns = mutableListOf<CountdownItem>()
    var checklistTemplates = mutableListOf<ChecklistTemplate>()
    var dockModules = mutableListOf<String>()

    fun toJson(includeBackups: Boolean = true): JSONObject {
        val o = JSONObject()
        o.put("tasks", JSONArray().apply { tasks.forEach { put(it.toJson()) } })
        o.put("projects", JSONArray().apply { projects.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("color", it.color)) } })
        o.put("labels", JSONArray().apply { labels.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) } })
        o.put("filters", JSONArray().apply { filters.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("query", it.query)) } })
        o.put("completions", JSONArray().apply { completions.forEach { put(JSONObject().put("id", it.id).put("taskId", it.taskId).put("title", it.title).put("projectId", it.projectId ?: JSONObject.NULL).put("date", it.date).put("at", it.at)) } })
        o.put("activities", JSONArray().apply { activities.forEach { put(JSONObject().put("id", it.id).put("type", it.type).put("text", it.text).put("at", it.at).put("projectId", it.projectId ?: JSONObject.NULL)) } })
        // Snapshots (backups, sync exports) pass includeBackups = false: a
        // backup must not embed all previous backups inside itself, which
        // made the state file grow multiplicatively. The on-disk state file
        // keeps the default (backups included); restores never read the
        // embedded copies, so nothing user-visible changes.
        if (includeBackups) o.put("backups", JSONArray().apply { backups.forEach { put(JSONObject().put("id", it.id).put("at", it.at).put("auto", it.auto).put("data", it.data)) } })
        o.put("sections", JSONArray().apply { sections.forEach { put(JSONObject().put("id", it.id).put("projectId", it.projectId).put("name", it.name).put("collapsed", it.collapsed).put("order", it.order)) } })
        o.put("dailyGoal", dailyGoal)
        o.put("weeklyGoal", weeklyGoal)
        o.put("karmaPoints", karmaPoints)
        o.put("quickAddChips", JSONArray(quickAddChips))
        o.put("quickAddHiddenChips", JSONArray(quickAddHiddenChips))
        o.put("comments", JSONArray().apply { comments.forEach { put(JSONObject().put("id", it.id).put("projectId", it.projectId ?: JSONObject.NULL).put("taskId", it.taskId ?: JSONObject.NULL).put("text", it.text).put("at", it.at)) } })
        o.put("templates", JSONArray().apply { templates.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("projectName", it.projectName).put("color", it.color).put("sectionNames", JSONArray(it.sectionNames)).put("taskTitles", JSONArray(it.taskTitles))) } })
        o.put("seeded", seeded)
        o.put("lastBackupDay", lastBackupDay)
        o.put("displayShowCompleted", displayShowCompleted)
        o.put("displayLayout", displayLayout)
        o.put("habits", JSONArray().apply { habits.forEach { put(it.toJson()) } })
        o.put("habitLogs", JSONArray().apply { habitLogs.forEach { put(it.toJson()) } })
        o.put("countdowns", JSONArray().apply { countdowns.forEach { put(it.toJson()) } })
        o.put("checklistTemplates", JSONArray().apply { checklistTemplates.forEach { put(it.toJson()) } })
        o.put("dockModules", JSONArray(dockModules))
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): State {
            val s = State()
            s.tasks = jsonArray(o.optJSONArray("tasks")) { taskFromJson(it) }.toMutableList()
            s.projects = jsonArray(o.optJSONArray("projects")) { ProjectItem(it.optString("id"), it.optString("name"), it.optString("color", "#d9a441")) }.toMutableList()
            s.labels = jsonArray(o.optJSONArray("labels")) { LabelItem(it.optString("id"), it.optString("name")) }.toMutableList()
            s.filters = jsonArray(o.optJSONArray("filters")) { FilterItem(it.optString("id"), it.optString("name"), it.optString("query")) }.toMutableList()
            // Completion dates are validated like task dates: a raw
            // malformed date would reach Db.bestStreak()'s date math
            // verbatim and crash the Performance screen. Entries whose
            // date is not a real calendar date are dropped at load.
            s.completions = jsonArray(o.optJSONArray("completions")) {
                CompletionItem(it.optString("id"), it.optString("taskId"), it.optString("title"),
                    if (it.isNull("projectId")) null else it.optString("projectId"), it.optString("date"), it.optLong("at"))
            }.filter { Dates.isValidDateStr(it.date) }.toMutableList()
            s.activities = jsonArray(o.optJSONArray("activities")) { ActivityItem(it.optString("id"), it.optString("type"), it.optString("text"), it.optLong("at"), if (it.isNull("projectId")) null else it.optString("projectId")) }.toMutableList()
            s.backups = jsonArray(o.optJSONArray("backups")) { BackupItem(it.optString("id"), it.optLong("at"), it.optBoolean("auto"), it.optString("data")) }.toMutableList()
            s.sections = jsonArray(o.optJSONArray("sections")) { SectionItem(it.optString("id"), it.optString("projectId"), it.optString("name"), it.optBoolean("collapsed", false), it.optInt("order", 0)) }.toMutableList()
            s.dailyGoal = o.optInt("dailyGoal", 5)
            s.weeklyGoal = o.optInt("weeklyGoal", 30)
            s.karmaPoints = o.optInt("karmaPoints", 0)
            val qc = o.optJSONArray("quickAddChips")
            if (qc != null) for (i in 0 until qc.length()) s.quickAddChips.add(qc.optString(i))
            val qh = o.optJSONArray("quickAddHiddenChips")
            if (qh != null) for (i in 0 until qh.length()) s.quickAddHiddenChips.add(qh.optString(i))
            s.comments = jsonArray(o.optJSONArray("comments")) {
                CommentItem(it.optString("id"), if (it.isNull("projectId")) null else it.optString("projectId"),
                    if (it.isNull("taskId")) null else it.optString("taskId"), it.optString("text"), it.optLong("at"))
            }.toMutableList()
            s.templates = jsonArray(o.optJSONArray("templates")) {
                val sn = mutableListOf<String>()
                val sa = it.optJSONArray("sectionNames")
                if (sa != null) for (j in 0 until sa.length()) sn.add(sa.optString(j))
                val tt = mutableListOf<String>()
                val ta = it.optJSONArray("taskTitles")
                if (ta != null) for (j in 0 until ta.length()) tt.add(ta.optString(j))
                TemplateItem(it.optString("id"), it.optString("name"), it.optString("projectName"), it.optString("color", "#d9a441"), sn, tt)
            }.toMutableList()
            s.seeded = o.optBoolean("seeded", false)
            s.lastBackupDay = o.optString("lastBackupDay", "")
            s.displayShowCompleted = o.optBoolean("displayShowCompleted", true)
            s.displayLayout = o.optString("displayLayout", "list")
            s.habits = jsonArray(o.optJSONArray("habits")) { habitFromJson(it) }.toMutableList()
            s.habitLogs = jsonArray(o.optJSONArray("habitLogs")) { habitLogFromJson(it) }.toMutableList()
            // A countdown is meaningless without a real target date,
            // and renderCountdowns does date math on it unguarded —
            // drop entries whose targetDate is not a valid date.
            s.countdowns = jsonArray(o.optJSONArray("countdowns")) { countdownFromJson(it) }
                .filter { Dates.isValidDateStr(it.targetDate) }.toMutableList()
            s.checklistTemplates = jsonArray(o.optJSONArray("checklistTemplates")) { checklistTemplateFromJson(it) }.toMutableList()
            val dm = o.optJSONArray("dockModules")
            if (dm != null) for (i in 0 until dm.length()) s.dockModules.add(dm.optString(i))
            return s
        }
    }
}

private fun <T> jsonArray(arr: JSONArray?, f: (JSONObject) -> T): List<T> {
    if (arr == null) return emptyList()
    val out = ArrayList<T>(arr.length())
    for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(f(it)) }
    return out
}

fun TaskItem.toJson(): JSONObject {
    val o = JSONObject()
    o.put("id", id); o.put("title", title); o.put("notes", notes)
    o.put("projectId", projectId ?: JSONObject.NULL)
    o.put("labelIds", JSONArray(labelIds))
    o.put("priority", priority)
    o.put("dueDate", dueDate ?: JSONObject.NULL)
    o.put("dueTime", dueTime ?: JSONObject.NULL)
    o.put("recurrence", recurrence ?: JSONObject.NULL)
    o.put("durationMin", durationMin ?: JSONObject.NULL)
    o.put("reminderOffsetMin", reminderOffsetMin ?: JSONObject.NULL)
    o.put("subtasks", JSONArray().apply { subtasks.forEach { put(JSONObject().put("id", it.id).put("title", it.title).put("done", it.done)) } })
    o.put("createdAt", createdAt)
    o.put("status", status)
    o.put("locLat", locLat ?: JSONObject.NULL)
    o.put("locLng", locLng ?: JSONObject.NULL)
    o.put("sectionId", sectionId ?: JSONObject.NULL)
    o.put("deadline", deadline ?: JSONObject.NULL)
    o.put("assignee", assignee ?: JSONObject.NULL)
    o.put("starred", starred)
    o.put("manualOrder", manualOrder ?: JSONObject.NULL)
    return o
}

fun taskFromJson(o: JSONObject): TaskItem {
    val t = TaskItem(
        id = o.optString("id").ifBlank { uid() },
        title = o.optString("title"),
        notes = o.optString("notes", ""),
        projectId = if (o.isNull("projectId")) null else o.optString("projectId"),
        // Clamped to the real 1..4 range: an out-of-range value can
        // only arrive via a crafted import, and downstream code
        // (spinner index, comparator) assumes 1..4.
        priority = o.optInt("priority", 4).coerceIn(1, 4),
        dueDate = if (o.isNull("dueDate")) null else o.optString("dueDate").takeIf { Dates.isValidDateStr(it) },
        dueTime = if (o.isNull("dueTime")) null else o.optString("dueTime").takeIf { Dates.isValidTimeStr(it) },
        recurrence = if (o.isNull("recurrence")) null else o.optString("recurrence"),
        durationMin = if (o.isNull("durationMin")) null else o.optInt("durationMin"),
        reminderOffsetMin = if (o.isNull("reminderOffsetMin")) null else o.optInt("reminderOffsetMin"),
        createdAt = o.optLong("createdAt", System.currentTimeMillis()),
        status = o.optString("status", "todo"),
        locLat = if (o.isNull("locLat")) null else o.optDouble("locLat"),
        locLng = if (o.isNull("locLng")) null else o.optDouble("locLng"),
        sectionId = if (o.isNull("sectionId")) null else o.optString("sectionId"),
        deadline = if (o.isNull("deadline")) null else o.optString("deadline").takeIf { Dates.isValidDateStr(it) },
        assignee = if (o.isNull("assignee")) null else o.optString("assignee"),
        starred = o.optBoolean("starred", false),
        manualOrder = if (o.isNull("manualOrder") || !o.has("manualOrder")) null else o.optInt("manualOrder")
    )
    val la = o.optJSONArray("labelIds")
    if (la != null) for (i in 0 until la.length()) t.labelIds.add(la.optString(i))
    val sa = o.optJSONArray("subtasks")
    if (sa != null) for (i in 0 until sa.length()) {
        val so = sa.optJSONObject(i) ?: continue
        t.subtasks.add(Subtask(so.optString("id"), so.optString("title"), so.optBoolean("done")))
    }
    return t
}

fun HabitItem.toJson(): JSONObject {
    val o = JSONObject()
    o.put("id", id); o.put("name", name); o.put("description", description)
    o.put("icon", icon); o.put("color", color); o.put("kind", kind)
    o.put("freqType", freqType)
    o.put("freqDays", JSONArray(freqDays))
    o.put("freqCount", freqCount); o.put("freqPeriodDays", freqPeriodDays)
    o.put("tracking", tracking); o.put("targetCount", targetCount); o.put("unit", unit)
    o.put("checklist", JSONArray().apply { checklist.forEach { put(JSONObject().put("id", it.id).put("title", it.title).put("done", it.done)) } })
    o.put("reminderPhrase", reminderPhrase)
    o.put("reminderTimes", JSONArray(reminderTimes))
    o.put("createdAt", createdAt); o.put("archived", archived); o.put("order", order)
    o.put("libraryKey", libraryKey ?: JSONObject.NULL)
    return o
}

/** The only frequency kinds the app creates; anything else in an
 *  imported file is normalised to "daily" at load (visible default at
 *  the trust boundary), and HabitLogic.isDueOn fails safe on unknown
 *  values that reach it any other way. */
private val HABIT_FREQ_TYPES = setOf("daily", "weekdays", "monthdays", "period")

fun habitFromJson(o: JSONObject): HabitItem {
    val h = HabitItem(
        id = o.optString("id").ifBlank { uid() },
        name = o.optString("name"),
        description = o.optString("description", ""),
        icon = o.optString("icon", "circle"),
        color = o.optString("color", "#d9a441"),
        kind = o.optString("kind", "build"),
        freqType = o.optString("freqType", "daily").takeIf { it in HABIT_FREQ_TYPES } ?: "daily",
        freqCount = o.optInt("freqCount", 1),
        freqPeriodDays = o.optInt("freqPeriodDays", 7),
        tracking = o.optString("tracking", "tick"),
        // A count target below 1 would make every log "done" — clamp.
        targetCount = o.optInt("targetCount", 1).coerceAtLeast(1),
        unit = o.optString("unit", ""),
        reminderPhrase = o.optString("reminderPhrase", ""),
        createdAt = o.optLong("createdAt", System.currentTimeMillis()),
        archived = o.optBoolean("archived", false),
        order = o.optInt("order", 0),
        libraryKey = if (o.isNull("libraryKey") || !o.has("libraryKey")) null else o.optString("libraryKey")
    )
    val fd = o.optJSONArray("freqDays")
    if (fd != null) for (i in 0 until fd.length()) h.freqDays.add(fd.optInt(i))
    val ca = o.optJSONArray("checklist")
    if (ca != null) for (i in 0 until ca.length()) {
        val so = ca.optJSONObject(i) ?: continue
        h.checklist.add(Subtask(so.optString("id"), so.optString("title"), so.optBoolean("done")))
    }
    val rt = o.optJSONArray("reminderTimes")
    // Strict "HH:mm" only (same rule as task dueTime): a malformed
    // entry like "8" or "25:99" must be dropped, never leniently
    // reinterpreted into a different alarm time.
    if (rt != null) for (i in 0 until rt.length()) {
        val t = rt.optString(i)
        if (Dates.isValidTimeStr(t)) h.reminderTimes.add(t)
    }
    return h
}

fun HabitLog.toJson(): JSONObject {
    val o = JSONObject()
    o.put("id", id); o.put("habitId", habitId); o.put("date", date)
    o.put("count", count)
    o.put("doneItems", JSONArray(doneItems))
    o.put("done", done); o.put("at", at)
    return o
}

fun habitLogFromJson(o: JSONObject): HabitLog {
    val l = HabitLog(
        id = o.optString("id").ifBlank { uid() },
        habitId = o.optString("habitId"),
        date = o.optString("date"),
        count = o.optInt("count", 0),
        done = o.optBoolean("done", false),
        at = o.optLong("at", System.currentTimeMillis())
    )
    val di = o.optJSONArray("doneItems")
    if (di != null) for (i in 0 until di.length()) l.doneItems.add(di.optString(i))
    return l
}

fun CountdownItem.toJson(): JSONObject {
    val o = JSONObject()
    o.put("id", id); o.put("title", title); o.put("targetDate", targetDate)
    o.put("color", color); o.put("createdAt", createdAt)
    return o
}

fun countdownFromJson(o: JSONObject): CountdownItem {
    return CountdownItem(
        id = o.optString("id").ifBlank { uid() },
        title = o.optString("title"),
        targetDate = o.optString("targetDate"),
        color = o.optString("color", "#d9a441"),
        createdAt = o.optLong("createdAt", System.currentTimeMillis())
    )
}

fun ChecklistTemplate.toJson(): JSONObject {
    val o = JSONObject()
    o.put("id", id); o.put("name", name); o.put("items", JSONArray(items))
    return o
}

fun checklistTemplateFromJson(o: JSONObject): ChecklistTemplate {
    val items = mutableListOf<String>()
    val ia = o.optJSONArray("items")
    if (ia != null) for (i in 0 until ia.length()) items.add(ia.optString(i))
    return ChecklistTemplate(
        id = o.optString("id").ifBlank { uid() },
        name = o.optString("name"),
        items = items
    )
}
