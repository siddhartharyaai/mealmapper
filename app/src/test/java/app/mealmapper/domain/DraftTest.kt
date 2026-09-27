package app.mealmapper.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftTest {
    private val roti = DraftItem(
        name = "Phulka",
        said = null,
        qty = 2.0,
        unit = "phulka",
        units = Units.forItem("Phulka", Basis.GRAMS, "phulka", 30.0, katoriMl = 150),
        per100 = Nutrients(300.0, 10.0, 60.0, 2.0, fiberG = 8.0),
        basis = Basis.GRAMS,
        sourceKind = SourceKind.DATABANK,
        sourceDetail = "INDB: Chapati/Roti",
        memoryId = null,
    )

    @Test fun gramsAndNutrientsFollowTheUnit() {
        assertEquals(60.0, roti.grams, 0.0)
        assertEquals(180.0, roti.nutrients.energyKcal, 0.01)
        assertEquals("2 phulka · 60 g", roti.amountText)
    }

    @Test fun stepsAreHalvesBelowTwoThenWholes() {
        assertEquals(3.0, roti.step(up = true), 0.0)
        assertEquals(1.5, roti.step(up = false), 0.0)
        assertEquals(1.0, roti.copy(qty = 0.5).step(up = true), 0.0)
        assertEquals(0.5, roti.copy(qty = 0.5).step(up = false), 0.0)
        assertEquals(70.0, roti.copy(unit = "g", qty = 60.0).step(up = true), 0.0)
    }

    @Test fun unitsIncludeBaseAndKatoriForDishes() {
        val dal = Units.forItem("Toor dal tadka", Basis.GRAMS, "katori", 150.0, katoriMl = 180, dish = true)
        assertEquals(150.0, dal["katori"]!!, 0.0) // the model's katori, as the user described it
        assertEquals(1.0, dal["g"]!!, 0.0)
        val bread = Units.forItem("Bread, white", Basis.GRAMS, "slice", 25.0, katoriMl = 150)
        assertTrue("slice" in bread)
    }

    @Test fun totalSkipsRemovedItems() {
        val d = Draft(1, listOf(roti, roti.copy(include = false)), LocalDate.of(2026, 9, 26), MealSlot.LUNCH)
        assertEquals(180.0, d.total.energyKcal, 0.01)
        assertEquals(4.8, d.total.fiberG!!, 0.01)
    }

    @Test fun fillKeepsTheUsersAmountAndTakesTheValues() {
        val loading = roti.copy(qty = 3.0, loading = true, sourceKind = SourceKind.AI, lookup = LookupSpec("food", "Phulka", null, null, null))
        val found = roti.copy(per100 = Nutrients(290.0, 11.0, 58.0, 1.5), units = mapOf("serving" to 40.0, "g" to 1.0))
        val filled = loading.fill(found)
        assertEquals(3.0, filled.qty, 0.0)
        assertEquals("phulka", filled.unit)
        assertEquals(290.0, filled.per100.energyKcal, 0.0)
        assertEquals(SourceKind.DATABANK, filled.sourceKind)
        assertEquals(30.0, filled.units["phulka"]!!, 0.0) // the current unit keeps its weight
        assertEquals(40.0, filled.units["serving"]!!, 0.0)
        assertTrue(!filled.loading && filled.lookup == null)
    }

    @Test fun cannotLogWhileAnItemIsLoading() {
        val d = Draft(1, listOf(roti, roti.copy(loading = true)), LocalDate.of(2026, 9, 27), MealSlot.LUNCH)
        assertTrue(d.loading && !d.canLog)
        // Unticking the loading item lets the rest be logged.
        assertTrue(d.copy(items = listOf(roti, roti.copy(loading = true, include = false))).canLog)
        val gaveUp = roti.copy(loading = true).gaveUp("AI estimate: the lookup did not finish")
        assertEquals(SourceKind.AI, gaveUp.sourceKind)
        assertTrue(!gaveUp.loading)
    }

    @Test fun draftRoundTripsThroughJson() {
        val json = kotlinx.serialization.json.Json
        val d = Draft(7, listOf(roti.copy(loading = true, lookup = LookupSpec("branded", "x", "x nutrition", null, "n"))),
            LocalDate.of(2026, 9, 26), MealSlot.PRE_BREAKFAST, saveAs = "Pre-breakfast")
        val back = json.decodeFromString(Draft.serializer(), json.encodeToString(Draft.serializer(), d))
        assertEquals(d, back)
    }
}
