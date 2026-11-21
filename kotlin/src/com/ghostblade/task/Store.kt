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

    /** Default Quick Add chip ids, in display order (Customize Quick Add). */
    val DEFAULT_CHIPS = listOf("date", "attachment", "priority", "reminders", "project", "labels", "description", "deadline", "location")

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        val f = File(appCtx.filesDir, FILE)
        state = if (f.exists()) {
            try { State.fromJson(JSONObject(f.readText(Charsets.UTF_8))) }
            catch (e: Exception) { State() }
        } else State()
        if (!state.seeded) seed()
        seedChecklistTemplatesIfNeeded()
        autoBackup()
        save()
        Db.init(appCtx)
        Db.migrateIfNeeded(state)
        // A single bad row must never brick startup — rollover is idempotent
        // and will simply run again on the next launch / day change.
        try { Db.rollover(state) } catch (e: Exception) { /* analytics only */ }
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
        // The day this snapshot was computed for — the widget refreshes on
        // its own schedule and can otherwise keep showing yesterday's
        // tasks and counts until the next in-app save.
        json.put("date", Dates.today())
        json.put("open", todayList.size)
        json.put("done", completionsOn(Dates.today()))
        val lines = org.json.JSONArray()
        todayList.take(6).forEach { lines.put((if (Dates.isOverdue(it)) "⚠ " else "• ") + it.title) }
        json.put("lines", lines)
        appCtx.getSharedPreferences(TaskWidget.PREFS, Context.MODE_PRIVATE).edit()
            .putString("data", json.toString()).apply()
        TaskWidget.updateAll(appCtx)
    }

    /** MANUAL order (v5 drag-sort): tasks that carry a manualOrder sort
     *  by it, ahead of unordered tasks; everything else keeps the v4.0
     *  rule (dueDate, priority, createdAt). See SHADOW-THREE-DESIGN §2. */
    val taskComparator = Comparator<TaskItem> { a, b ->
        val am = a.manualOrder
        val bm = b.manualOrder
        when {
            am != null && bm != null && am != bm -> am.compareTo(bm)
            am != null && bm == null -> -1
            am == null && bm != null -> 1
            else -> {
                val da = a.dueDate ?: "9999"
                val db = b.dueDate ?: "9999"
                when {
                    da != db -> da.compareTo(db)
                    a.priority != b.priority -> a.priority.compareTo(b.priority)
                    else -> a.createdAt.compareTo(b.createdAt)
                }
            }
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

    fun logActivity(type: String, text: String, projectId: String? = null) {
        state.activities.add(0, ActivityItem(uid(), type, text, System.currentTimeMillis(), projectId))
        // Full reporting history: the activity cap is effectively unbounded
        // (100,000 entries). Completions are never truncated anywhere —
        // only this activity feed has a (very high) safety cap.
        if (state.activities.size > 100000) {
            state.activities = state.activities.take(100000).toMutableList()
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

    fun snapshot(): String = state.toJson(includeBackups = false).toString()

    fun importData(text: String): Boolean {
        return try {
            val d = JSONObject(text)
            if (!d.has("tasks")) return false
            val fresh = State.fromJson(d)
            // Cancel the outgoing tasks' alarms/geofences first, so an import
            // never leaves orphan reminders firing for tasks it replaced.
            state.tasks.forEach { Reminders.drop(appCtx, it); Reminders.clearGeo(appCtx, it) }
            state.habits.forEach { HabitReminders.cancel(appCtx, it.id) }
            state.tasks = fresh.tasks
            state.projects = fresh.projects
            state.labels = fresh.labels
            state.filters = fresh.filters
            state.completions = fresh.completions
            state.activities = fresh.activities
            state.sections = fresh.sections
            state.dailyGoal = fresh.dailyGoal
            state.weeklyGoal = fresh.weeklyGoal
            state.karmaPoints = fresh.karmaPoints
            state.quickAddChips = fresh.quickAddChips
            state.quickAddHiddenChips = fresh.quickAddHiddenChips
            state.comments = fresh.comments
            state.templates = fresh.templates
            state.habits = fresh.habits
            state.habitLogs = fresh.habitLogs
            state.countdowns = fresh.countdowns
            state.checklistTemplates = fresh.checklistTemplates
            state.dockModules = fresh.dockModules
            state.tasks.forEach { Reminders.sync(appCtx, it); Reminders.syncGeo(appCtx, it) }
            state.habits.forEach { HabitReminders.sync(appCtx, it) }
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
        t.deadline = p.deadline ?: t.deadline
        if (p.projectName != null) t.projectId = projByName(p.projectName!!).id
        p.labelNames.forEach { n -> t.labelIds.add(labelByName(n).id) }
        if (state.tasks.none { it.id == t.id }) state.tasks.add(t)
        logActivity("create", "Added \"" + t.title + "\"", t.projectId)
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
        // Karma rule: see Karma.kt — points by the completed task's priority.
        state.karmaPoints += karmaFor(t.priority)
        logActivity("complete", "Completed \"" + t.title + "\"", t.projectId)
        return if (t.recurrence != null) {
            // Advance until the next occurrence is no longer in the past —
            // completing a daily task that is 5 days overdue used to move it
            // only one day, leaving it still overdue (and every extra tap
            // logged a phantom completion). Guard mirrors Db's 400 cap and
            // also stops unrecognised recurrences, which advance in place.
            var guard = 0
            do { t.dueDate = Dates.advanceDate(t) } while (
                t.dueDate != null && t.dueDate!! < Dates.today() && ++guard < 400
            )
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
        logActivity("delete", "Deleted \"" + t.title + "\"", t.projectId)
        state.tasks.removeAt(i)
        save()
        return t
    }

    /* ---------- karma ---------- */

    // Delegates to Karma.kt — single source for the points table.
    private fun karmaFor(priority: Int): Int = Karma.pointsFor(priority)

    /* ---------- reschedule overdue ---------- */

    /** Move every overdue task to today (recurring tasks advance by their
     *  recurrence until no longer overdue). Returns (taskId → previous
     *  dueDate) pairs in task order, for Undo via restoreDueDates. */
    fun rescheduleOverdue(): List<Pair<String, String?>> {
        val today = Dates.today()
        val previous = mutableListOf<Pair<String, String?>>()
        for (t in state.tasks) {
            val due = t.dueDate ?: continue
            if (due >= today) continue
            previous.add(t.id to t.dueDate)
            if (t.recurrence != null) {
                var guard = 0
                do { t.dueDate = Dates.advanceDate(t) } while (
                    t.dueDate != null && t.dueDate!! < today && ++guard < 400
                )
            } else {
                t.dueDate = today
            }
            Reminders.sync(appCtx, t)
        }
        if (previous.isNotEmpty()) {
            logActivity("reschedule", "Rescheduled " + previous.size + " overdue task(s) to today")
            save()
        }
        return previous
    }

    fun restoreDueDates(previous: List<Pair<String, String?>>) {
        for ((id, old) in previous) {
            val t = byId(id) ?: continue
            t.dueDate = old
            Reminders.sync(appCtx, t)
        }
        save()
    }

    /* ---------- bulk (multi-select) operations ---------- */

    fun bulkMoveToProject(ids: Collection<String>, projectId: String?) {
        val set = ids.toSet()
        state.tasks.forEach { if (it.id in set) it.projectId = projectId }
        save()
    }

    fun bulkAddLabel(ids: Collection<String>, labelId: String) {
        val set = ids.toSet()
        state.tasks.forEach { if (it.id in set && labelId !in it.labelIds) it.labelIds.add(labelId) }
        save()
    }

    fun bulkSetPriority(ids: Collection<String>, priority: Int) {
        val set = ids.toSet()
        val p = priority.coerceIn(1, 4)
        state.tasks.forEach { if (it.id in set) it.priority = p }
        save()
    }

    fun bulkSetSection(ids: Collection<String>, sectionId: String?) {
        val set = ids.toSet()
        state.tasks.forEach { if (it.id in set) it.sectionId = sectionId }
        save()
    }

    /** Same reminder cleanup as deleteTask; returns the removed tasks so
     *  the UI can undo. One activity entry, one save. */
    fun bulkDelete(ids: Collection<String>): List<TaskItem> {
        val set = ids.toSet()
        val removed = state.tasks.filter { it.id in set }
        if (removed.isEmpty()) return removed
        removed.forEach { Reminders.drop(appCtx, it); Reminders.clearGeo(appCtx, it) }
        state.tasks.removeAll { it.id in set }
        logActivity("delete", "Deleted " + removed.size + " tasks")
        save()
        return removed
    }

    /* ---------- sections ---------- */

    fun addSection(projectId: String, name: String): SectionItem {
        val next = (state.sections.filter { it.projectId == projectId }.maxOfOrNull { it.order } ?: -1) + 1
        val s = SectionItem(uid(), projectId, name, false, next)
        state.sections.add(s)
        save()
        return s
    }

    fun renameSection(id: String, name: String) {
        state.sections.find { it.id == id }?.name = name
        save()
    }

    fun deleteSection(id: String) {
        state.tasks.forEach { if (it.sectionId == id) it.sectionId = null }
        state.sections.removeAll { it.id == id }
        save()
    }

    fun setSectionCollapsed(id: String, collapsed: Boolean) {
        state.sections.find { it.id == id }?.collapsed = collapsed
        save()
    }

    /* ---------- goals & Quick Add chip prefs ---------- */

    fun setDailyGoal(n: Int) {
        state.dailyGoal = n.coerceIn(1, 999)
        save()
    }

    fun setWeeklyGoal(n: Int) {
        state.weeklyGoal = n.coerceIn(1, 999)
        save()
    }

    /* ---------- display prefs ---------- */

    fun setDisplayShowCompleted(show: Boolean) {
        state.displayShowCompleted = show
        save()
    }

    fun setDisplayLayout(layout: String) {
        if (layout !in listOf("list", "board", "calendar")) return
        state.displayLayout = layout
        save()
    }

    fun quickAddChipOrder(): List<String> =
        if (state.quickAddChips.isNotEmpty()) state.quickAddChips.toList() else DEFAULT_CHIPS

    fun setQuickAddChips(order: List<String>, hidden: List<String>) {
        state.quickAddChips = order.toMutableList()
        state.quickAddHiddenChips = hidden.toMutableList()
        save()
    }

    /* ---------- comments ---------- */

    /** Blank text is not stored; the returned item then has id "" so the
     *  caller can tell nothing was added (contract keeps a non-null
     *  CommentItem return). */
    fun addComment(projectId: String?, taskId: String?, text: String): CommentItem {
        val clean = text.trim()
        if (clean.isEmpty()) return CommentItem("", projectId, taskId, "", System.currentTimeMillis())
        val c = CommentItem(uid(), projectId, taskId, clean, System.currentTimeMillis())
        state.comments.add(c)
        save()
        return c
    }

    fun deleteComment(id: String) {
        state.comments.removeAll { it.id == id }
        save()
    }

    /** Comments for one project (null = global/inbox), newest first. */
    fun commentsFor(projectId: String?): List<CommentItem> =
        state.comments.filter { it.projectId == projectId }.sortedByDescending { it.at }

    /* ---------- project templates ---------- */

    fun saveProjectAsTemplate(projectId: String, name: String): TemplateItem? {
        val p = state.projects.find { it.id == projectId } ?: return null
        val tpl = TemplateItem(
            id = uid(),
            name = name,
            projectName = p.name,
            color = p.color,
            sectionNames = state.sections.filter { it.projectId == projectId }
                .sortedBy { it.order }.map { it.name },
            taskTitles = state.tasks.filter { it.projectId == projectId }.map { it.title }
        )
        state.templates.add(tpl)
        save()
        return tpl
    }

    fun applyTemplate(templateId: String): ProjectItem? {
        val tpl = state.templates.find { it.id == templateId } ?: return null
        val p = ProjectItem(uid(), tpl.projectName, tpl.color)
        state.projects.add(p)
        tpl.sectionNames.forEachIndexed { i, n ->
            state.sections.add(SectionItem(uid(), p.id, n, false, i))
        }
        // Tasks were captured without a section mapping, so they land
        // sectionless in the new project (documented limitation).
        tpl.taskTitles.forEach { title ->
            state.tasks.add(TaskItem(id = uid(), title = title, projectId = p.id, priority = 4))
        }
        save()
        return p
    }

    /* ---------- Task & Email Assist (offline text import) ---------- */

    /** One task per non-empty line; each line runs through the full
     *  natural-language Parser. A non-null [projectId] forces every
     *  created task into that project (overriding any #project in the
     *  line). Returns how many tasks were created. */
    fun importTasksFromText(text: String, projectId: String?): Int {
        var count = 0
        for (line in text.lines()) {
            val clean = line.trim()
            if (clean.isEmpty()) continue
            val t = addTaskFromParse(Parser.parse(clean))
            if (projectId != null) t.projectId = projectId
            count++
        }
        if (count > 0) save()
        return count
    }

    /* ---------- v5: checklist template seeds ---------- */

    /** The two starter checklist templates (SHADOW-THREE-DESIGN §8.3)
     *  are seeded exactly once per install, guarded by a prefs flag so
     *  a user who deletes them never gets them back uninvited. */
    private fun seedChecklistTemplatesIfNeeded() {
        val prefs = appCtx.getSharedPreferences("gbt_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("cl_templates_seeded", false)) return
        if (state.checklistTemplates.isEmpty()) {
            state.checklistTemplates.add(
                ChecklistTemplate(
                    uid(), "Grocery Shopping Checklist",
                    listOf("Fruit", "Vegetables", "Milk", "Eggs", "Bread", "Rice", "Chicken", "Spices")
                )
            )
            state.checklistTemplates.add(
                ChecklistTemplate(
                    uid(), "Pre-Trip Preparation Checklist",
                    listOf(
                        "Check passport and visa", "Confirm transport",
                        "Confirm accommodation", "Pack chargers", "Download tickets offline"
                    )
                )
            )
        }
        prefs.edit().putBoolean("cl_templates_seeded", true).apply()
    }

    /* ---------- v5: habits ---------- */

    fun habitById(id: String): HabitItem? = state.habits.find { it.id == id }

    /** Get-or-create the single log for (habitId, date) — never two. */
    fun habitLogFor(habitId: String, date: String): HabitLog {
        var l = state.habitLogs.find { it.habitId == habitId && it.date == date }
        if (l == null) {
            l = HabitLog(uid(), habitId, date)
            state.habitLogs.add(l)
        }
        return l
    }

    fun addHabit(h: HabitItem): HabitItem {
        h.order = (state.habits.maxOfOrNull { it.order } ?: -1) + 1
        state.habits.add(h)
        logActivity("habit", "Started habit \"" + h.name + "\"")
        HabitReminders.sync(appCtx, h)
        save()
        return h
    }

    fun updateHabit(h: HabitItem) {
        logActivity("habit", "Updated habit \"" + h.name + "\"")
        HabitReminders.sync(appCtx, h)
        save()
    }

    fun archiveHabit(id: String, archived: Boolean) {
        val h = habitById(id) ?: return
        h.archived = archived
        if (archived) HabitReminders.cancel(appCtx, id) else HabitReminders.sync(appCtx, h)
        logActivity("habit", (if (archived) "Archived" else "Unarchived") + " habit \"" + h.name + "\"")
        save()
    }

    /** Removes the habit and all its logs; returns them for Undo. */
    fun deleteHabit(id: String): Pair<HabitItem, List<HabitLog>>? {
        val h = habitById(id) ?: return null
        val logs = state.habitLogs.filter { it.habitId == id }
        HabitReminders.cancel(appCtx, id)
        state.habits.removeAll { it.id == id }
        state.habitLogs.removeAll { it.habitId == id }
        logActivity("habit", "Deleted habit \"" + h.name + "\"")
        save()
        return h to logs
    }

    fun restoreHabit(h: HabitItem, logs: List<HabitLog>) {
        if (state.habits.none { it.id == h.id }) state.habits.add(h)
        logs.forEach { l -> if (state.habitLogs.none { it.id == l.id }) state.habitLogs.add(l) }
        HabitReminders.sync(appCtx, h)
        save()
    }

    fun habitSetTick(habitId: String, date: String, done: Boolean) {
        if (habitById(habitId) == null) return
        val l = habitLogFor(habitId, date)
        l.done = done
        l.at = System.currentTimeMillis()
        save()
    }

    fun habitAddCount(habitId: String, date: String, delta: Int) {
        val h = habitById(habitId) ?: return
        val l = habitLogFor(habitId, date)
        l.count = (l.count + delta).coerceAtLeast(0)
        l.done = HabitLogic.resolveDone(h, l)
        l.at = System.currentTimeMillis()
        save()
    }

    fun habitToggleChecklist(habitId: String, date: String, itemId: String) {
        val h = habitById(habitId) ?: return
        val l = habitLogFor(habitId, date)
        if (itemId in l.doneItems) l.doneItems.remove(itemId) else l.doneItems.add(itemId)
        l.done = HabitLogic.resolveDone(h, l)
        l.at = System.currentTimeMillis()
        save()
    }

    fun habitScore(): Double = HabitLogic.score(state.habits, state.habitLogs, Dates.today())

    fun habitDueCount(date: String): Int =
        state.habits.count { !it.archived && HabitLogic.isDueOn(it, date) }

    /* ---------- v5: star + skip ---------- */

    fun toggleStar(id: String) {
        val t = byId(id) ?: return
        t.starred = !t.starred
        save()
    }

    /** Skip: move the due date one day out WITHOUT completing (no
     *  completion record, no Karma). A daily-recurring task's instance
     *  moves +1 day from its current due date; anything else goes to
     *  tomorrow. (SHADOW-THREE-DESIGN §2.) */
    fun skipTask(id: String) {
        val t = byId(id) ?: return
        t.dueDate = if (t.recurrence == "daily" && t.dueDate != null)
            Dates.addDays(t.dueDate!!, 1)
        else
            Dates.addDays(Dates.today(), 1)
        Reminders.sync(appCtx, t)
        logActivity("skip", "Skipped \"" + t.title + "\"", t.projectId)
        save()
    }

    /* ---------- v5: countdowns ---------- */

    fun addCountdown(c: CountdownItem) {
        state.countdowns.add(c)
        logActivity("countdown", "Counting down to \"" + c.title + "\"")
        save()
    }

    fun deleteCountdown(id: String): CountdownItem? {
        val c = state.countdowns.find { it.id == id } ?: return null
        state.countdowns.remove(c)
        logActivity("countdown", "Removed countdown \"" + c.title + "\"")
        save()
        return c
    }

    fun restoreCountdown(c: CountdownItem) {
        if (state.countdowns.none { it.id == c.id }) state.countdowns.add(c)
        save()
    }

    /* ---------- v5: checklist templates ---------- */

    fun addChecklistTemplate(name: String, items: List<String>): ChecklistTemplate {
        val t = ChecklistTemplate(uid(), name, items)
        state.checklistTemplates.add(t)
        save()
        return t
    }

    fun deleteChecklistTemplate(id: String) {
        state.checklistTemplates.removeAll { it.id == id }
        save()
    }

    /** Applying a checklist template creates ONE task titled with the
     *  template name, its subtasks = the template items, no due date. */
    fun applyChecklistTemplate(id: String, projectId: String?): TaskItem? {
        val tpl = state.checklistTemplates.find { it.id == id } ?: return null
        val t = TaskItem(id = uid(), title = tpl.name, projectId = projectId, priority = 4)
        tpl.items.forEach { t.subtasks.add(Subtask(uid(), it, false)) }
        state.tasks.add(t)
        logActivity("create", "Added \"" + t.title + "\" from a checklist", projectId)
        save()
        return t
    }

    /* ---------- v5: modular dock ---------- */

    val DOCK_MODULE_IDS =
        listOf("today", "upcoming", "calendar", "habits", "focus", "stats", "matrix", "countdowns", "browse")
    // SHADOW-FOUR §2.7 default set: Today · Calendar · [+] · Browse ·
    // Stats ("add" is the fixed centre, not a module). An earlier build
    // shipped habits/focus here and dropped Stats — corrected at the
    // Senior Designer gate (CHECK1 §9, deviation #8's coupled P3).
    val DOCK_DEFAULT = listOf("today", "calendar", "browse", "stats")

    /** Resolved dock modules: stored list filtered to known ids, or the
     *  default set when empty; "today" is locked (always present);
     *  at most 5 modules. The Quick Add "+" is NOT a module. */
    fun dockModules(): List<String> {
        val raw = state.dockModules.filter { it in DOCK_MODULE_IDS }.distinct()
        var base = if (raw.isEmpty()) DOCK_DEFAULT else raw
        if ("today" !in base) base = listOf("today") + base
        return base.take(5)
    }

    fun setDockModules(mods: List<String>) {
        state.dockModules = mods.filter { it in DOCK_MODULE_IDS }.distinct().take(5).toMutableList()
        save()
    }
}
