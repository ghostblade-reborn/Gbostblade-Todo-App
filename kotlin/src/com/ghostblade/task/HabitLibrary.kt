package com.ghostblade.task

/* Habit library catalogue — code constant per SHADOW-THREE-DESIGN §1.4.
   Icons are names; colours are the per-group defaults. No Android imports. */

data class HabitTemplate(
    val key: String,
    val group: String,
    val name: String,
    val icon: String,
    val tagline: String,
    val popular: Boolean,
    val kind: String = "build",
    val color: String
)

object HabitLibrary {

    val GROUPS = listOf("Health", "Life", "Sports", "Mind")

    const val COLOR_HEALTH = "#34c759"
    const val COLOR_LIFE = "#ff9f0a"
    const val COLOR_SPORTS = "#4a90d9"
    const val COLOR_MIND = "#bf5af2"

    fun groupColor(group: String): String = when (group) {
        "Health" -> COLOR_HEALTH
        "Life" -> COLOR_LIFE
        "Sports" -> COLOR_SPORTS
        "Mind" -> COLOR_MIND
        else -> "#d9a441"
    }

    val TEMPLATES: List<HabitTemplate> = listOf(
        // Health
        HabitTemplate("drink-water", "Health", "Drink Water", "drop", "A glass at a time — energy, skin and focus all start here.", true, color = COLOR_HEALTH),
        HabitTemplate("brush-teeth", "Health", "Brush Teeth", "sparkle", "Two minutes, twice a day, zero regrets at the dentist.", true, color = COLOR_HEALTH),
        HabitTemplate("take-a-shower", "Health", "Take a Shower", "droplets", "Reset your body and your head in five minutes.", false, color = COLOR_HEALTH),
        HabitTemplate("go-to-bed-early", "Health", "Go to Bed Early", "moon", "Tomorrow's discipline is decided tonight.", true, color = COLOR_HEALTH),
        HabitTemplate("get-up-early", "Health", "Get Up Early", "sun", "Win the first hour and the day follows.", false, color = COLOR_HEALTH),
        HabitTemplate("medication-reminder", "Health", "Medication Reminder", "pill", "Never miss a dose — set it once, stay on track.", false, color = COLOR_HEALTH),
        HabitTemplate("take-a-break", "Health", "Take a Break", "pause", "Short pauses keep long days productive.", false, color = COLOR_HEALTH),
        HabitTemplate("eat-fruits", "Health", "Eat Fruits", "apple", "One fruit a day is the easiest health upgrade there is.", false, color = COLOR_HEALTH),
        // Life
        HabitTemplate("study", "Life", "Study", "book", "Small daily sessions beat weekend cramming.", true, color = COLOR_LIFE),
        HabitTemplate("cooking", "Life", "Cooking", "pan", "One home-cooked meal a day — cheaper, healthier, calmer.", false, color = COLOR_LIFE),
        HabitTemplate("clean-house", "Life", "Clean House", "broom", "A clear room makes a clear mind.", true, color = COLOR_LIFE),
        HabitTemplate("make-the-bed", "Life", "Make the Bed", "bed", "The first finished task of the day, done in two minutes.", false, color = COLOR_LIFE),
        HabitTemplate("keep-in-touch-with-family", "Life", "Keep in Touch with Family", "phone", "One call or message keeps the important people close.", false, color = COLOR_LIFE),
        HabitTemplate("read-a-book", "Life", "Read a Book", "bookmark", "Ten pages a day is a book a month.", false, color = COLOR_LIFE),
        // Sports
        HabitTemplate("go-exercising", "Sports", "Go Exercising", "dumbbell", "Move daily; your future self is watching.", true, color = COLOR_SPORTS),
        HabitTemplate("take-a-walk", "Sports", "Take a Walk", "steps", "A short walk clears what sitting fogs up.", false, color = COLOR_SPORTS),
        HabitTemplate("stretch", "Sports", "Stretch", "stretch", "Five loose minutes undo a whole day of sitting.", false, color = COLOR_SPORTS),
        HabitTemplate("swimming", "Sports", "Swimming", "wave", "Full-body, zero-impact, all calm.", false, color = COLOR_SPORTS),
        HabitTemplate("practice-yoga", "Sports", "Practice Yoga", "lotus", "Strength and stillness in the same ten minutes.", false, color = COLOR_SPORTS),
        HabitTemplate("cycling", "Sports", "Cycling", "bike", "Commute, cardio and fresh air in one ride.", false, color = COLOR_SPORTS),
        // Mind
        HabitTemplate("pray", "Mind", "Pray", "hands", "A quiet minute of gratitude before the noise starts.", true, color = COLOR_MIND),
        HabitTemplate("meditation", "Mind", "Meditation", "lotus-mind", "Ten quiet minutes to hear yourself think.", false, color = COLOR_MIND),
        HabitTemplate("journal", "Mind", "Journal", "pen", "Write the day down before it writes you.", true, color = COLOR_MIND),
        HabitTemplate("no-phone-first-hour", "Mind", "No Phone First Hour", "phone-off", "The first hour is yours — don't hand it to a feed.", false, kind = "quit", color = COLOR_MIND)
    )

    // Alias matching the catalogue naming in the design doc.
    val ALL: List<HabitTemplate> = TEMPLATES

    fun byKey(key: String): HabitTemplate? = TEMPLATES.firstOrNull { it.key == key }

    fun byGroup(group: String): List<HabitTemplate> = TEMPLATES.filter { it.group == group }
}
