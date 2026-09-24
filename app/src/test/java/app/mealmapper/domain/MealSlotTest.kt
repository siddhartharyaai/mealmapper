package app.mealmapper.domain

import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

class MealSlotTest {
    private fun at(h: Int, m: Int = 0) = mealSlotFor(LocalTime.of(h, m))

    @Test fun earlyMorningChaiIsBreakfast() = assertEquals(MealSlot.BREAKFAST, at(5))
    @Test fun lateBreakfastBeforeEleven() = assertEquals(MealSlot.BREAKFAST, at(10, 59))
    @Test fun lunchStartsAtEleven() = assertEquals(MealSlot.LUNCH, at(11))
    @Test fun lateMumbaiLunchAtThree() = assertEquals(MealSlot.LUNCH, at(15, 30))
    @Test fun eveningChaiIsSnack() = assertEquals(MealSlot.SNACK, at(17))
    @Test fun eightFifteenIsStillSnack() = assertEquals(MealSlot.SNACK, at(20, 15))
    @Test fun dinnerFromEightThirty() = assertEquals(MealSlot.DINNER, at(20, 30))
    @Test fun lateNightIsDinner() = assertEquals(MealSlot.DINNER, at(23, 45))
    @Test fun afterMidnightIsDinner() = assertEquals(MealSlot.DINNER, at(0, 30))
    @Test fun beforeFiveIsDinner() = assertEquals(MealSlot.DINNER, at(4, 59))
}
