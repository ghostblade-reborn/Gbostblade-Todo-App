package com.ghostblade.task

import java.util.Calendar

/** Date helpers on "yyyy-MM-dd" strings (lexicographic order = date order). */
object Dates {
    private fun pad(n: Int) = n.toString().padStart(2, '0')

    fun dstr(c: Calendar): String =
        c.get(Calendar.YEAR).toString() + "-" + pad(c.get(Calendar.MONTH) + 1) + "-" + pad(c.get(Calendar.DAY_OF_MONTH))

    fun today(): String = dstr(Calendar.getInstance())

    fun parse(s: String): Calendar {
        val p = s.split("-").map { it.toInt() }
        val c = Calendar.getInstance()
        c.set(p[0], p[1] - 1, p[2], 0, 0, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c
    }

    fun addDays(s: String, n: Int): String {
        val c = parse(s); c.add(Calendar.DAY_OF_MONTH, n); return dstr(c)
    }

    /** JS-style day of week: 0 = Sunday … 6 = Saturday. */
    fun jsDayOfWeek(c: Calendar): Int = c.get(Calendar.DAY_OF_WEEK) - 1

    fun fmtDay(s: String): String {
        return try {
            val days = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
            val mon = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
            val c = parse(s)
            days[jsDayOfWeek(c)] + " " + c.get(Calendar.DAY_OF_MONTH) + " " + mon[c.get(Calendar.MONTH)]
        } catch (e: Exception) { s }
    }

    fun isOverdue(t: TaskItem): Boolean = t.dueDate != null && t.dueDate!! < today()

    fun nextWeekday(wd: Int): String {
        val now = Calendar.getInstance()
        var add = (wd - jsDayOfWeek(now) + 7) % 7
        if (add == 0) add = 7
        now.add(Calendar.DAY_OF_MONTH, add)
        return dstr(now)
    }

    /** Advance a recurring task's due date, mirroring the JS recurrence rules. */
    fun advanceDate(task: TaskItem): String {
        val d = task.dueDate ?: today()
        return when {
            task.recurrence == "daily" -> addDays(d, 1)
            task.recurrence == "weekly" -> addDays(d, 7)
            task.recurrence == "monthly" -> {
                val c = parse(d); c.add(Calendar.MONTH, 1); dstr(c)
            }
            task.recurrence?.startsWith("weekly:") == true -> {
                val wd = task.recurrence!!.substringAfter(':').toIntOrNull() ?: return d
                var nd = addDays(d, 1)
                while (jsDayOfWeek(parse(nd)) != wd) nd = addDays(nd, 1)
                nd
            }
            else -> d
        }
    }
}

fun uid(): String =
    "t" + System.currentTimeMillis().toString(36) + (Math.random() * 1e9).toLong().toString(36)
