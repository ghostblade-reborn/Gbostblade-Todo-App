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
    var locLng: Double? = null
)

data class ProjectItem(var id: String, var name: String, var color: String = "#d9a441")
data class LabelItem(var id: String, var name: String)
data class FilterItem(var id: String, var name: String, var query: String)
data class CompletionItem(var id: String, var taskId: String, var title: String, var projectId: String?, var date: String, var at: Long)
data class ActivityItem(var id: String, var type: String, var text: String, var at: Long)
data class BackupItem(var id: String, var at: Long, var auto: Boolean, var data: String)

class State {
    var tasks = mutableListOf<TaskItem>()
    var projects = mutableListOf<ProjectItem>()
    var labels = mutableListOf<LabelItem>()
    var filters = mutableListOf<FilterItem>()
    var completions = mutableListOf<CompletionItem>()
    var activities = mutableListOf<ActivityItem>()
    var backups = mutableListOf<BackupItem>()
    var seeded = false
    var lastBackupDay = ""

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("tasks", JSONArray().apply { tasks.forEach { put(it.toJson()) } })
        o.put("projects", JSONArray().apply { projects.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("color", it.color)) } })
        o.put("labels", JSONArray().apply { labels.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) } })
        o.put("filters", JSONArray().apply { filters.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("query", it.query)) } })
        o.put("completions", JSONArray().apply { completions.forEach { put(JSONObject().put("id", it.id).put("taskId", it.taskId).put("title", it.title).put("projectId", it.projectId ?: JSONObject.NULL).put("date", it.date).put("at", it.at)) } })
        o.put("activities", JSONArray().apply { activities.forEach { put(JSONObject().put("id", it.id).put("type", it.type).put("text", it.text).put("at", it.at)) } })
        o.put("backups", JSONArray().apply { backups.forEach { put(JSONObject().put("id", it.id).put("at", it.at).put("auto", it.auto).put("data", it.data)) } })
        o.put("seeded", seeded)
        o.put("lastBackupDay", lastBackupDay)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): State {
            val s = State()
            s.tasks = jsonArray(o.optJSONArray("tasks")) { taskFromJson(it) }.toMutableList()
            s.projects = jsonArray(o.optJSONArray("projects")) { ProjectItem(it.optString("id"), it.optString("name"), it.optString("color", "#d9a441")) }.toMutableList()
            s.labels = jsonArray(o.optJSONArray("labels")) { LabelItem(it.optString("id"), it.optString("name")) }.toMutableList()
            s.filters = jsonArray(o.optJSONArray("filters")) { FilterItem(it.optString("id"), it.optString("name"), it.optString("query")) }.toMutableList()
            s.completions = jsonArray(o.optJSONArray("completions")) {
                CompletionItem(it.optString("id"), it.optString("taskId"), it.optString("title"),
                    if (it.isNull("projectId")) null else it.optString("projectId"), it.optString("date"), it.optLong("at"))
            }.toMutableList()
            s.activities = jsonArray(o.optJSONArray("activities")) { ActivityItem(it.optString("id"), it.optString("type"), it.optString("text"), it.optLong("at")) }.toMutableList()
            s.backups = jsonArray(o.optJSONArray("backups")) { BackupItem(it.optString("id"), it.optLong("at"), it.optBoolean("auto"), it.optString("data")) }.toMutableList()
            s.seeded = o.optBoolean("seeded", false)
            s.lastBackupDay = o.optString("lastBackupDay", "")
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
    return o
}

fun taskFromJson(o: JSONObject): TaskItem {
    val t = TaskItem(
        id = o.optString("id"),
        title = o.optString("title"),
        notes = o.optString("notes", ""),
        projectId = if (o.isNull("projectId")) null else o.optString("projectId"),
        priority = o.optInt("priority", 4),
        dueDate = if (o.isNull("dueDate")) null else o.optString("dueDate"),
        dueTime = if (o.isNull("dueTime")) null else o.optString("dueTime"),
        recurrence = if (o.isNull("recurrence")) null else o.optString("recurrence"),
        durationMin = if (o.isNull("durationMin")) null else o.optInt("durationMin"),
        reminderOffsetMin = if (o.isNull("reminderOffsetMin")) null else o.optInt("reminderOffsetMin"),
        createdAt = o.optLong("createdAt", System.currentTimeMillis()),
        status = o.optString("status", "todo"),
        locLat = if (o.isNull("locLat")) null else o.optDouble("locLat"),
        locLng = if (o.isNull("locLng")) null else o.optDouble("locLng")
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
