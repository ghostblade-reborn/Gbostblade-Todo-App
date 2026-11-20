package com.ghostblade.task

/* Karma — gamification points and levels.
 *
 * Rule: completing a task awards points by the task's priority —
 * P1 = 50, P2 = 30, P3 = 20, P4 = 10. Points are only ever awarded,
 * never deducted, so a user's karma total never goes down. Unknown /
 * out-of-range priorities award the P4 value.
 *
 * Levels (cumulative points floor): Beginner 0, Novice 500,
 * Intermediate 2000, Proficient 5000, Expert 10000, Master 20000,
 * Grandmaster 50000. */
object Karma {

    fun pointsFor(priority: Int): Int = when (priority) {
        1 -> 50
        2 -> 30
        3 -> 20
        else -> 10 // P4, and the clamp for any unknown priority
    }

    data class LevelInfo(
        val name: String,
        val floor: Int,
        val nextName: String?,
        val nextFloor: Int?,
        val pointsToNext: Int,
        val progress: Float
    )

    // Ordered lowest → highest; levelFor picks the last floor reached.
    private val levels = listOf(
        "Beginner" to 0,
        "Novice" to 500,
        "Intermediate" to 2000,
        "Proficient" to 5000,
        "Expert" to 10000,
        "Master" to 20000,
        "Grandmaster" to 50000
    )

    fun levelFor(points: Int): LevelInfo {
        val pts = maxOf(points, 0)
        val idx = levels.indexOfLast { pts >= it.second }
        val (name, floor) = levels[idx]
        val next = levels.getOrNull(idx + 1)
        return if (next == null) {
            LevelInfo(name, floor, null, null, 0, 1f)
        } else {
            LevelInfo(
                name = name,
                floor = floor,
                nextName = next.first,
                nextFloor = next.second,
                pointsToNext = next.second - pts,
                progress = (pts - floor).toFloat() / (next.second - floor).toFloat()
            )
        }
    }

    /** Completions per day for the 7 days ending [today], newest first (today at index 0). */
    fun trend(completions: List<CompletionItem>, today: String): List<Int> =
        (0..6).map { back ->
            val day = Dates.addDays(today, -back)
            completions.count { it.date == day }
        }
}
