package app.mealmapper.data.fooddb

import app.mealmapper.domain.FoodSearch
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs against the real assets/foods.json, so a bad databank build fails the unit tests. */
class FoodDbTest {
    private val foods by lazy {
        val file = listOf("src/main/assets/foods.json", "app/src/main/assets/foods.json", "foods.json").map(::File).first { it.exists() }
        FoodDbParser.parse(file.readText())
    }

    @Test fun databankLoadsAndIsEggetarian() {
        assertTrue(foods.size > 1000)
        val meat = Regex("chicken(?! mushroom)|mutton|fish|prawn|keema|beef|pork", RegexOption.IGNORE_CASE)
        assertTrue(foods.filter { meat.containsMatchIn(it.name) }.map { it.name }.toString(), foods.none { meat.containsMatchIn(it.name) })
    }

    @Test fun pooriNoLongerCountsThePanOfOil() {
        val poori = foods.first { it.name == "Poori" }
        assertTrue("poori ${poori.per100.energyKcal}", poori.per100.energyKcal in 300.0..420.0)
        assertTrue("fry-corrected" in poori.flags)
    }

    @Test fun oilsHaveEnergy() {
        val ghee = foods.first { it.name == "Ghee" }
        assertEquals(900.0, ghee.per100.energyKcal, 1.0)
    }

    @Test fun everyRowIsWithinPhysicalLimits() {
        foods.forEach {
            assertTrue(it.name, it.per100.energyKcal in 0.0..905.0)
            assertTrue(it.name, it.per100.fatG <= 101)
        }
    }

    @Test fun hinglishSearchFindsCookedDishFirst() {
        val daal = FoodSearch.search(foods, "daal makhani")
        assertEquals("Dal makhani", daal.first().name)
        val bhindi = FoodSearch.search(foods, "bhindi sabji")
        assertTrue(bhindi.first().name, bhindi.first().name.contains("okra", ignoreCase = true) || bhindi.first().name.contains("bhindi", ignoreCase = true))
        val roti = FoodSearch.search(foods, "roti").first()
        assertTrue(roti.name, roti.name.contains("roti", ignoreCase = true))
        assertTrue(roti.units.isNotEmpty())
    }

    @Test fun rawGrainsRankBelowCookedRice() {
        val rice = FoodSearch.search(foods, "rice")
        assertFalse(rice.first().name, rice.first().name.endsWith("(raw)"))
    }

    @Test fun alcoholByStrength() {
        val beer = FoodSearch.search(foods, "beer").first()
        assertEquals(44.0, beer.per100.energyKcal, 1.0)
        val peg = FoodSearch.search(foods, "whisky").first()
        assertNotNull(peg.units.firstOrNull { it.amount == 60.0 })
    }

    @Test fun candidatesIncludeSingleWordMatches() {
        val c = FoodSearch.candidates(foods, "Dal tadka")
        assertTrue(c.isNotEmpty())
        assertTrue(c.size <= 8)
    }
}
