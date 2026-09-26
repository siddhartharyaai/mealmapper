package app.mealmapper.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryTest {
    private val whey = DraftItem(
        name = "True Basics Whey Protein",
        said = "tru basic way protein",
        qty = 1.0,
        unit = "scoop",
        units = mapOf("scoop" to 33.0, "g" to 1.0),
        per100 = Nutrients(394.0, 73.0, 9.0, 6.0),
        basis = Basis.GRAMS,
        sourceKind = SourceKind.WEB,
        sourceDetail = "truebasics.com",
        memoryId = null,
    )

    @Test fun learnsNewFoodWithMisspellingAsAlias() {
        val (foods, id) = Memory.learn(emptyList(), whey, MealSlot.PRE_BREAKFAST, 1000)
        val f = foods.single()
        assertEquals(id, f.id)
        assertEquals(listOf("tru basic way protein"), f.aliases)
        assertEquals("scoop", f.usualUnit)
        assertEquals(33.0, f.units["scoop"]!!, 0.0)
        assertEquals("Online: truebasics.com", f.source)
    }

    @Test fun repeatUpdatesSameFoodAndCountsTheMeal() {
        val (once, id) = Memory.learn(emptyList(), whey, MealSlot.PRE_BREAKFAST, 1000)
        val again = Memory.toDraftItem(once.single(), qty = 2.0, unit = "scoop", said = "whey")
        val (twice, id2) = Memory.learn(once, again, MealSlot.PRE_BREAKFAST, 2000)
        assertEquals(id, id2)
        val f = twice.single()
        assertEquals(2, f.uses)
        assertEquals(2, f.slotUses["PRE_BREAKFAST"])
        assertEquals(2.0, f.usualQty, 0.0)
        assertTrue("whey" in f.aliases)
        assertEquals("Online: truebasics.com", f.source) // a memory re-log keeps the original source
    }

    @Test fun suggestionsNeedTwoUsesAtThatMeal() {
        val (once, _) = Memory.learn(emptyList(), whey, MealSlot.PRE_BREAKFAST, 1000)
        assertTrue(Memory.suggestions(once, emptyList(), MealSlot.PRE_BREAKFAST).isEmpty())
        val (twice, _) = Memory.learn(once, whey, MealSlot.PRE_BREAKFAST, 2000)
        assertEquals("True Basics Whey Protein · 1 scoop", Memory.suggestions(twice, emptyList(), MealSlot.PRE_BREAKFAST).single().label)
        assertTrue(Memory.suggestions(twice, emptyList(), MealSlot.DINNER).isEmpty())
    }

    @Test fun savedMealsComeFirstForTheirMeal() {
        val meal = SavedMeal("s1", "My usual breakfast", "BREAKFAST", listOf(MealPart("m1", 1.0, "piece")))
        val s = Memory.suggestions(emptyList(), listOf(meal), MealSlot.BREAKFAST)
        assertEquals("My usual breakfast", s.first().label)
    }

    @Test fun promptListsIdsUnitsAndAliases() {
        val (foods, id) = Memory.learn(emptyList(), whey, MealSlot.PRE_BREAKFAST, 1000)
        val text = Memory.promptLines(foods, emptyList())
        assertTrue(text, text.contains("$id | True Basics Whey Protein | also said: tru basic way protein | units: scoop=33g"))
    }
}
