package com.ghostblade.task

/** Filter query engine — port of the JS evalFilter.
 *  Syntax: groups split by "|" (OR), tokens split by "&" (AND).
 *  Tokens: today, overdue, upcoming, "no date", recurring, p1–p4,
 *  #Project, @label, or free text matched against the title. */
object Filters {

    fun eval(state: State, t: TaskItem, q: String): Boolean =
        q.split("|").any { group -> group.split("&").all { tok -> matchToken(state, t, tok) } }

    fun matchToken(state: State, t: TaskItem, rawTok: String): Boolean {
        val tok = rawTok.trim().lowercase()
        if (tok.isEmpty()) return true
        return when {
            tok == "today" -> t.dueDate == Dates.today()
            tok == "overdue" -> Dates.isOverdue(t)
            tok == "upcoming" -> t.dueDate != null && t.dueDate!! > Dates.today()
            tok == "no date" -> t.dueDate == null
            tok == "recurring" -> t.recurrence != null
            tok == "starred" -> t.starred
            Regex("""^p[1-4]$""").matches(tok) -> t.priority == tok[1].digitToInt()
            tok.startsWith("#") -> {
                val p = state.projects.find { it.id == t.projectId }
                p != null && p.name.lowercase() == tok.substring(1)
            }
            tok.startsWith("@") -> t.labelIds.any { id ->
                val l = state.labels.find { it.id == id }
                l != null && l.name.lowercase() == tok.substring(1)
            }
            else -> t.title.lowercase().contains(tok)
        }
    }
}
