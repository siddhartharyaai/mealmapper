package app.mealmapper.data.ai

import app.mealmapper.domain.MealSlot
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatParsingTest {
    private val today = LocalDate.of(2026, 9, 26)

    @Test fun parsesItemsWithNaturalUnits() {
        val text = """```json
        {"action":"log","reply":"Got it","meal":"pre_breakfast","day":"yesterday","replaces_draft":false,
         "items":[{"name":"True Basics Whey Protein","said":"tru basic way","memory_id":"m3","quantity":2,"unit":"Scoop",
           "grams":66,"kind":"branded","search_name":"True Basics Whey nutrition facts","kcal":260,"protein_g":48,"carbs_g":6,"fat_g":4}]}
        ```"""
        val p = ChatParsing.plan(text, today)
        assertEquals("log", p.action)
        assertEquals(MealSlot.PRE_BREAKFAST, p.slot)
        assertEquals(today.minusDays(1), p.day)
        val i = p.items.single()
        assertEquals("m3", i.memoryId)
        assertEquals("scoop", i.unit)
        assertEquals(2.0, i.quantity, 0.0)
        assertEquals(393.9, i.estimate!!.energyKcal, 0.1) // 260 kcal for 66 g -> per 100 g
        assertEquals("tru basic way", i.said)
    }

    @Test fun brandedBecomesLookupAndLabelBecomesPublished() {
        val text = """{"items":[
          {"name":"Qbit Green","quantity":1,"unit":"scoop","grams":5,"kind":"branded","search_name":"Qbit Green nutrition facts","kcal":18},
          {"name":"Marie biscuit","quantity":4,"unit":"biscuit","grams":28,"kind":"label","label_per_100":{"energy_kcal":440,"protein_g":7,"carbs_g":75,"fat_g":12}}]}"""
        val p = ChatParsing.plan(text, today)
        val a = ChatParsing.toMealItem(p.items[0])
        assertEquals("Qbit Green nutrition facts", a.lookup)
        val b = ChatParsing.toMealItem(p.items[1])
        assertTrue(b.published)
        assertEquals(440.0, b.per100.energyKcal, 0.0)
        assertNull(b.lookup)
    }

    @Test fun plainTextIsAnAnswer() {
        val p = ChatParsing.plan("You have 900 kcal left today.", today)
        assertEquals("answer", p.action)
        assertTrue(p.items.isEmpty())
    }

    @Test fun saveMealAndFutureDayIgnored() {
        val p = ChatParsing.plan("""{"action":"save_meal","save_meal":{"name":"My usual breakfast"},"day":"2026-10-02","items":[]}""", today)
        assertEquals("save_meal", p.action)
        assertEquals("My usual breakfast", p.saveMealName)
        assertNull(p.day)
    }

    @Test fun itemsWithoutGramsAreDropped() {
        val p = ChatParsing.plan("""{"items":[{"name":"Mystery","quantity":1,"unit":"piece"}]}""", today)
        assertTrue(p.items.isEmpty())
    }
}
