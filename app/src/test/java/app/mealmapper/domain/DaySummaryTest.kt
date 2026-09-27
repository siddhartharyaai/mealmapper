package app.mealmapper.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class DaySummaryTest {
    private val n = Nutrients(500.0, 20.0, 60.0, 15.0, fiberG = 6.0, sugarG = 9.0)

    @Test fun nothingLogged() {
        assertEquals("Nothing logged today", DaySummary.text(emptyList(), 1800).first)
    }

    @Test fun totalsCapAndMissingMeals() {
        val (title, text) = DaySummary.text(listOf(MealSlot.BREAKFAST to n, MealSlot.LUNCH to n), 1800)
        assertEquals("Today: 1000 of 1800 kcal (800 left)", title)
        assertEquals("P 40 g · C 120 g · F 30 g · Fibre 12 g · Sugar 18 g\nNot logged yet: dinner.", text)
        assertEquals("Today: 2000 of 1800 kcal (200 over)", DaySummary.text(List(4) { MealSlot.DINNER to n }, 1800).first)
        assertEquals("Today: 500 kcal", DaySummary.text(listOf(MealSlot.SNACK to n), null).first)
    }
}
