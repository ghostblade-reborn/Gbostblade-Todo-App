package com.ghostblade.task

/* Display sheet engine — pure logic behind the filter / sort / group
   controls. Dates are "yyyy-MM-dd" strings, so plain string comparison
   is date comparison. All functions are non-mutating. */

enum class GroupMode { NONE, DATE, PROJECT, PRIORITY }
enum class SortMode { MANUAL, DATE, PRIORITY, TITLE, SMART }

data class DisplayFilter(
    val dateFrom: String? = null,
    val dateTo: String? = null,
    val priorities: Set<Int> = emptySet(),
    val labelIds: Set<String> = emptySet(),
    val assignee: String? = null
)

data class TaskGroup(val key: String, val title: String, val tasks: List<TaskItem>)

object DisplayEngine {

    /** A task with no due date can never be inside a date range, so it is
     *  excluded as soon as either bound is set. */
    fun applyFilter(tasks: List<TaskItem>, filter: DisplayFilter): List<TaskItem> =
        tasks.filter { t ->
            val dateOk = if (filter.dateFrom == null && filter.dateTo == null) {
                true
            } else {
                val d = t.dueDate ?: return@filter false
                (filter.dateFrom == null || d >= filter.dateFrom) &&
                    (filter.dateTo == null || d <= filter.dateTo)
            }
            val prioOk = filter.priorities.isEmpty() || t.priority in filter.priorities
            val labelOk = filter.labelIds.isEmpty() || t.labelIds.any { it in filter.labelIds }
            val assigneeOk = filter.assignee == null || t.assignee == filter.assignee
            dateOk && prioOk && labelOk && assigneeOk
        }

    // sortedWith is stable in Kotlin, so equal keys keep input order.
    fun sortTasks(tasks: List<TaskItem>, mode: SortMode): List<TaskItem> = when (mode) {
        SortMode.MANUAL -> tasks.toList()
        SortMode.DATE -> tasks.sortedWith(
            compareBy<TaskItem> { it.dueDate == null }
                .thenBy { it.dueDate }
                .thenBy { it.priority }
                .thenBy { it.createdAt }
        )
        SortMode.PRIORITY -> tasks.sortedWith(
            compareBy<TaskItem> { it.priority }
                .thenBy { it.dueDate == null }
                .thenBy { it.dueDate }
                .thenBy { it.createdAt }
        )
        SortMode.TITLE -> tasks.sortedWith(
            compareBy<TaskItem> { it.title.lowercase() }
                .thenBy { it.createdAt }
        )
        // SMART (Todoist-style): (a) overdue first, oldest dueDate first;
        // (b) then tasks due today; (c) then future-dated tasks, dueDate
        // ascending; (d) no-date tasks last. Within the same dueDate (or
        // both no-date): tasks with a dueTime come before those without,
        // earlier dueTime first; then priority ascending (1 highest);
        // then createdAt ascending.
        SortMode.SMART -> {
            val today = Dates.today()
            tasks.sortedWith(
                compareBy<TaskItem> {
                    when {
                        it.dueDate == null -> 3
                        it.dueDate!! < today -> 0
                        it.dueDate == today -> 1
                        else -> 2
                    }
                }
                    .thenBy { it.dueDate }
                    .thenBy { it.dueTime == null }
                    .thenBy { it.dueTime }
                    .thenBy { it.priority }
                    .thenBy { it.createdAt }
            )
        }
    }

    fun groupTasks(state: State, tasks: List<TaskItem>, mode: GroupMode): List<TaskGroup> = when (mode) {
        GroupMode.NONE -> listOf(TaskGroup("all", "", tasks.toList()))

        GroupMode.DATE -> {
            val today = Dates.today()
            val tomorrow = Dates.addDays(today, 1)
            val buckets = listOf(
                TaskGroup("overdue", "Overdue", tasks.filter { it.dueDate != null && it.dueDate!! < today }),
                TaskGroup("today", "Today", tasks.filter { it.dueDate == today }),
                TaskGroup("tomorrow", "Tomorrow", tasks.filter { it.dueDate == tomorrow }),
                TaskGroup("upcoming", "Upcoming", tasks.filter { it.dueDate != null && it.dueDate!! > tomorrow }),
                TaskGroup("nodate", "No date", tasks.filter { it.dueDate == null })
            )
            buckets.filter { it.tasks.isNotEmpty() }
        }

        GroupMode.PROJECT -> {
            val knownIds = state.projects.map { it.id }.toSet()
            // Orphaned tasks (project deleted / unknown id) fall back to Inbox.
            val out = ArrayList<TaskGroup>()
            val inbox = tasks.filter { it.projectId == null || it.projectId !in knownIds }
            if (inbox.isNotEmpty()) out.add(TaskGroup("inbox", "Inbox", inbox))
            for (p in state.projects) {
                val mine = tasks.filter { it.projectId == p.id }
                if (mine.isNotEmpty()) out.add(TaskGroup(p.id, p.name, mine))
            }
            out
        }

        GroupMode.PRIORITY -> (1..4).mapNotNull { p ->
            val mine = tasks.filter { it.priority == p }
            if (mine.isEmpty()) null else TaskGroup("p$p", "Priority $p", mine)
        }
    }
}
