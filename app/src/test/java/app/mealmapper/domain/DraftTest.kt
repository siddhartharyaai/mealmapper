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
}
