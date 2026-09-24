package app.mealmapper.domain

import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MealSlotTest {
    private fun at(h: Int, m: Int = 0) = mealSlotFor(LocalTime.of(h, m))

    @Test fun earlyMorningChaiIsBreakfast() = assertEquals(MealSlot.BREAKFAST, at(5))
    @Test fun breakfastUntilNoon() = assertEquals(MealSlot.BREAKFAST, at(11, 59))
    @Test fun lunchFromNoon() = assertEquals(MealSlot.LUNCH, at(12))
    @Test fun lateMumbaiLunchAtThree() = assertEquals(MealSlot.LUNCH, at(15, 30))
    @Test fun eveningFromFour() = assertEquals(MealSlot.SNACK, at(16))
    @Test fun sixFortyFiveIsStillEvening() = assertEquals(MealSlot.SNACK, at(18, 45))
    @Test fun dinnerFromSeven() = assertEquals(MealSlot.DINNER, at(19))
    @Test fun lateNightIsDinner() = assertEquals(MealSlot.DINNER, at(23, 45))
    @Test fun afterMidnightIsDinner() = assertEquals(MealSlot.DINNER, at(0, 30))

    @Test fun spokenMealWins() {
        assertEquals(MealSlot.LUNCH, mealSlotIn("2 roti and dal for lunch"))
        assertEquals(MealSlot.BREAKFAST, mealSlotIn("nashta mein poha"))
        assertEquals(MealSlot.DINNER, mealSlotIn("raat ka khana, gatte ki sabzi"))
        assertEquals(MealSlot.SNACK, mealSlotIn("shaam ki chai and 2 biscuits"))
        assertEquals(MealSlot.DINNER, mealSlotIn("रात को दाल चावल"))
        assertNull(mealSlotIn("2 roti, 1 katori dal"))
    }

    @Test fun firstNamedMealWins() = assertEquals(MealSlot.LUNCH, mealSlotIn("lunch leftovers from dinner"))

    @Test fun lateLunchIsRecordedAtLunchtime() {
        val night = LocalDateTime.of(2026, 9, 24, 21, 15)
        assertEquals(LocalDateTime.of(2026, 9, 24, 13, 30), eatenAtFor(MealSlot.LUNCH, night))
    }

    @Test fun currentMealIsNow() {
        val t = LocalDateTime.of(2026, 9, 24, 13, 5)
        assertEquals(t, eatenAtFor(MealSlot.LUNCH, t))
    }

    @Test fun futureMealIsNow() {
        val morning = LocalDateTime.of(2026, 9, 24, 9, 0)
        assertEquals(morning, eatenAtFor(MealSlot.DINNER, morning))
    }

    @Test fun yesterdaysDinnerLandsOnYesterday() {
        val morning = LocalDateTime.of(2026, 9, 25, 9, 0)
        assertEquals(
            LocalDateTime.of(2026, 9, 24, 20, 30),
            eatenAtFor(MealSlot.DINNER, java.time.LocalDate.of(2026, 9, 24), morning),
        )
    }

    @Test fun todayStillUsesNow() {
        val t = LocalDateTime.of(2026, 9, 25, 13, 5)
        assertEquals(t, eatenAtFor(MealSlot.LUNCH, t.toLocalDate(), t))
    }
}
