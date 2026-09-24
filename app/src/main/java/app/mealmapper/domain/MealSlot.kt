package app.mealmapper.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The four meals Health Connect (and so Google Health) knows. "Evening" food (chai and a snack at 5) is SNACK.
 * [typical] is the time used when a meal is logged late, so Google Health shows lunch at lunchtime.
 */
enum class MealSlot(val label: String, val typical: LocalTime) {
    BREAKFAST("Breakfast", LocalTime.of(8, 30)),
    LUNCH("Lunch", LocalTime.of(13, 30)),
    SNACK("Snack", LocalTime.of(17, 30)),
    DINNER("Dinner", LocalTime.of(20, 30)),
}

private val BREAKFAST_START = LocalTime.of(5, 0)
private val LUNCH_START = LocalTime.of(12, 0)
private val SNACK_START = LocalTime.of(16, 0)
private val DINNER_START = LocalTime.of(19, 0)

/**
 * Default meal for a local time: breakfast 5-12, lunch 12-4, evening snack 4-7, dinner 7 onwards
 * (after midnight is still the late dinner). Only a default: the user taps another meal to change it.
 */
fun mealSlotFor(time: LocalTime): MealSlot = when {
    time < BREAKFAST_START -> MealSlot.DINNER
    time < LUNCH_START -> MealSlot.BREAKFAST
    time < SNACK_START -> MealSlot.LUNCH
    time < DINNER_START -> MealSlot.SNACK
    else -> MealSlot.DINNER
}

private val SPOKEN: List<Pair<Regex, MealSlot>> = listOf(
    Regex("breakfast|nashta|naashta|nasta|नाश्ता|subah") to MealSlot.BREAKFAST,
    Regex("lunch|dopahar|दोपहर") to MealSlot.LUNCH,
    Regex("dinner|supper|raat|रात") to MealSlot.DINNER,
    Regex("snack|evening|shaam|sham\\b|शाम|tea ?time|chai ?time") to MealSlot.SNACK,
)

/** A meal named in what the user typed or said ("for lunch", "raat ka khana"), or null. The first one named wins. */
fun mealSlotIn(text: String): MealSlot? {
    val t = text.lowercase()
    return SPOKEN.mapNotNull { (re, slot) -> re.find(t)?.let { it.range.first to slot } }.minByOrNull { it.first }?.second
}

/**
 * When to record a meal. Logging the current meal: now. Logging an earlier meal of today (lunch at 9 pm):
 * that meal's usual time today, so Google Health's timeline stays in order. A meal "later" than now: now.
 */
fun eatenAtFor(slot: MealSlot, now: LocalDateTime): LocalDateTime {
    if (slot == mealSlotFor(now.toLocalTime())) return now
    val usual = LocalDateTime.of(now.toLocalDate(), slot.typical)
    return if (usual.isBefore(now)) usual else now
}

/**
 * When to record a meal logged for [day]: today follows [eatenAtFor]; an earlier day (forgot to log yesterday)
 * uses that meal's usual time on that date, so Google Health adds it to the right day's totals.
 */
fun eatenAtFor(slot: MealSlot, day: LocalDate, now: LocalDateTime): LocalDateTime =
    if (!day.isBefore(now.toLocalDate())) eatenAtFor(slot, now) else LocalDateTime.of(day, slot.typical)

/** How far back a meal can be logged. Matches History (30 days). */
const val MAX_DAYS_BACK = 30L

/** The usual time of [slot] on [day], used when a History edit moves an entry to another meal. */
fun usualTime(slot: MealSlot, day: LocalDate): LocalDateTime = LocalDateTime.of(day, slot.typical)
