package com.ghostblade.task

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** GhostBlade analytics database (SQLite, on-device).
 *
 *  Tables:
 *   completions — every task completion (mirrors the JSON store so sync
 *                 files stay portable; this DB powers fast range queries)
 *   day_tasks   — one row per task per due day with its outcome:
 *                 'done' | 'missed' | 'pending'. Missed rows are inferred
 *                 by the daily rollover from tasks that came due and were
 *                 never completed (including each elapsed day of a
 *                 recurring task). Titles are snapshotted so history
 *                 survives task deletion.
 *   meta        — bookkeeping (migration + rollover watermarks)
 */
object Db {
    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, "ghostblade.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE completions(id TEXT PRIMARY KEY, task_id TEXT, title TEXT, project_id TEXT, date TEXT, at INTEGER)")
            db.execSQL("CREATE INDEX idx_comp_date ON completions(date)")
            db.execSQL("CREATE TABLE day_tasks(date TEXT, task_id TEXT, title TEXT, project_id TEXT, outcome TEXT, PRIMARY KEY(date, task_id))")
            db.execSQL("CREATE INDEX idx_day_date ON day_tasks(date)")
            db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)")
        }
        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}
    }

    private lateinit var helper: Helper
    private val db: SQLiteDatabase get() = helper.writableDatabase

    fun init(ctx: Context) {
        helper = Helper(ctx.applicationContext)
    }

    private fun meta(key: String): String? {
        val c = db.query("meta", arrayOf("value"), "key=?", arrayOf(key), null, null, null)
        val v = if (c.moveToFirst()) c.getString(0) else null
        c.close()
        return v
    }

    private fun setMeta(key: String, value: String) {
        val cv = ContentValues()
        cv.put("key", key); cv.put("value", value)
        db.insertWithOnConflict("meta", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun clearMeta(key: String) {
        db.delete("meta", "key=?", arrayOf(key))
    }

    /* ---------- migration & sync with the JSON store ---------- */

    fun migrateIfNeeded(state: State) {
        if (meta("migrated") == "1") return
        db.beginTransaction()
        try {
            state.completions.forEach { insertCompletionRow(it) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        setMeta("migrated", "1")
    }

    fun rebuildFromState(state: State) {
        db.beginTransaction()
        try {
            db.delete("completions", null, null)
            db.delete("day_tasks", null, null)
            state.completions.forEach { insertCompletionRow(it) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        // The day_tasks table was just wiped, so the rollover watermark
        // must go too — otherwise the next rollover would skip missed days
        // that predate the watermark and were never re-derived.
        clearMeta("last_rollover")
        rollover(state)
    }

    private fun insertCompletionRow(c: CompletionItem) {
        val cv = ContentValues()
        cv.put("id", c.id); cv.put("task_id", c.taskId); cv.put("title", c.title)
        cv.put("project_id", c.projectId); cv.put("date", c.date); cv.put("at", c.at)
        db.insertWithOnConflict("completions", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        val dv = ContentValues()
        dv.put("date", c.date); dv.put("task_id", c.taskId); dv.put("title", c.title)
        dv.put("project_id", c.projectId); dv.put("outcome", "done")
        db.insertWithOnConflict("day_tasks", null, dv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun logCompletion(c: CompletionItem) = insertCompletionRow(c)

    /* ---------- daily rollover: record missed + today's pending ---------- */

    fun rollover(state: State) {
        val today = Dates.today()
        val yesterday = Dates.addDays(today, -1)
        // Watermark: days up to this date were already rolled over by a
        // previous run, so missed-day stepping can start there instead of
        // re-walking from a task's original due date on every launch
        // (a daily task a year overdue used to cost up to 400 steps +
        // 400 queries per launch, forever). Null on first run → full
        // derivation, exactly as before.
        val watermark = meta("last_rollover")
        db.beginTransaction()
        try {
            // clear stale pending rows (recomputed below)
            db.delete("day_tasks", "outcome='pending'", null)
            for (t in state.tasks) {
                val due = t.dueDate ?: continue
                if (due < today) {
                    // every day this task came due and wasn't completed = missed
                    val start = if (watermark != null && watermark > due) watermark else due
                    for (d in expectedDueDates(t, start, yesterday)) {
                        if (!hasCompletionOn(t.id, d)) {
                            val cv = ContentValues()
                            cv.put("date", d); cv.put("task_id", t.id); cv.put("title", t.title)
                            cv.put("project_id", t.projectId); cv.put("outcome", "missed")
                            db.insertWithOnConflict("day_tasks", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
                        }
                    }
                } else if (due == today) {
                    val cv = ContentValues()
                    cv.put("date", today); cv.put("task_id", t.id); cv.put("title", t.title)
                    cv.put("project_id", t.projectId); cv.put("outcome", "pending")
                    db.insertWithOnConflict("day_tasks", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        setMeta("last_rollover", today)
    }

    /** All dates a task was due in [from, to]: one for plain tasks,
     *  stepped by recurrence for recurring ones. */
    private fun expectedDueDates(t: TaskItem, from: String, to: String): List<String> {
        if (t.recurrence == null) return if (from <= to) listOf(from) else emptyList()
        val out = mutableListOf<String>()
        val ghost = TaskItem(id = t.id, title = t.title, dueDate = from, recurrence = t.recurrence)
        var d = from
        var guard = 0
        while (d <= to && guard < 400) {
            out.add(d)
            ghost.dueDate = d
            val next = Dates.advanceDate(ghost)
            // An unrecognised recurrence advances in place — stop instead
            // of burning all 400 guard iterations on the same date.
            if (next == d) break
            d = next
            guard++
        }
        return out
    }

    private fun hasCompletionOn(taskId: String, date: String): Boolean {
        val c = db.query("completions", arrayOf("id"), "task_id=? AND date=?", arrayOf(taskId, date), null, null, null)
        val found = c.count > 0
        c.close()
        return found
    }

    /* ---------- queries for the Performance screen ---------- */

    data class DayOutcome(val date: String, val taskId: String, val title: String, val projectId: String?, val outcome: String)

    fun completionsInRange(from: String, to: String): List<CompletionItem> {
        val out = mutableListOf<CompletionItem>()
        val c = db.query("completions", null, "date>=? AND date<=?", arrayOf(from, to), null, null, "date DESC, at DESC")
        while (c.moveToNext()) {
            out.add(
                CompletionItem(
                    c.getString(c.getColumnIndexOrThrow("id")),
                    c.getString(c.getColumnIndexOrThrow("task_id")),
                    c.getString(c.getColumnIndexOrThrow("title")),
                    c.getString(c.getColumnIndexOrThrow("project_id")),
                    c.getString(c.getColumnIndexOrThrow("date")),
                    c.getLong(c.getColumnIndexOrThrow("at"))
                )
            )
        }
        c.close()
        return out
    }

    fun outcomesInRange(from: String, to: String, outcome: String): List<DayOutcome> {
        val out = mutableListOf<DayOutcome>()
        val c = db.query("day_tasks", null, "date>=? AND date<=? AND outcome=?",
            arrayOf(from, to, outcome), null, null, "date DESC, title ASC")
        while (c.moveToNext()) {
            out.add(
                DayOutcome(
                    c.getString(c.getColumnIndexOrThrow("date")),
                    c.getString(c.getColumnIndexOrThrow("task_id")),
                    c.getString(c.getColumnIndexOrThrow("title")),
                    c.getString(c.getColumnIndexOrThrow("project_id")),
                    outcome
                )
            )
        }
        c.close()
        return out
    }

    fun doneCountByDay(from: String, to: String): Map<String, Int> {
        val out = mutableMapOf<String, Int>()
        val c = db.rawQuery(
            "SELECT date, COUNT(*) FROM completions WHERE date>=? AND date<=? GROUP BY date",
            arrayOf(from, to)
        )
        while (c.moveToNext()) out[c.getString(0)] = c.getInt(1)
        c.close()
        return out
    }

    fun activeDays(from: String, to: String): Int {
        val c = db.rawQuery(
            "SELECT COUNT(DISTINCT date) FROM completions WHERE date>=? AND date<=?",
            arrayOf(from, to)
        )
        val v = if (c.moveToFirst()) c.getInt(0) else 0
        c.close()
        return v
    }

    /** Every distinct completion date, ascending — streak/trend fallback
     *  when the in-memory completion list is unavailable. */
    fun allCompletionDates(): List<String> {
        val out = mutableListOf<String>()
        val c = db.rawQuery("SELECT DISTINCT date FROM completions ORDER BY date ASC", null)
        while (c.moveToNext()) out.add(c.getString(0))
        c.close()
        return out
    }

    /** Longest run of consecutive days with at least one completion, all time. */
    fun bestStreak(): Int {
        val c = db.rawQuery("SELECT DISTINCT date FROM completions ORDER BY date ASC", null)
        var best = 0
        var run = 0
        var prev: String? = null
        while (c.moveToNext()) {
            val d = c.getString(0)
            run = if (prev != null && Dates.addDays(prev, 1) == d) run + 1 else 1
            if (run > best) best = run
            prev = d
        }
        c.close()
        return best
    }

    fun doneByProject(from: String, to: String): List<Pair<String?, Int>> {
        val out = mutableListOf<Pair<String?, Int>>()
        val c = db.rawQuery(
            "SELECT project_id, COUNT(*) FROM completions WHERE date>=? AND date<=? GROUP BY project_id ORDER BY COUNT(*) DESC",
            arrayOf(from, to)
        )
        while (c.moveToNext()) out.add(c.getString(0) to c.getInt(1))
        c.close()
        return out
    }
}
