package app.mealmapper.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class PortionTest {
    private val per100 = Nutrients(58.0, 3.1, 4.7, 3.0)
    private val milk = FoodProduct("8901262150019", "Toned milk", "Amul", per100, Basis.MILLILITRES, null, 500.0)
    private val biscuit = FoodProduct("8901719101038", "Glucose biscuits", "Parle", per100, Basis.GRAMS, 25.0, 250.0)

    @Test fun milkGetsCupAndGlass() {
        val labels = portionOptions(milk).map { it.label }
        assertEquals(listOf("100 ml", "1 cup · 150 ml", "1 glass · 250 ml", "Whole pack · 500 ml"), labels)
    }

    @Test fun biscuitServingFirst() {
        assertEquals("1 serving · 25 g", portionOptions(biscuit).first().label)
        assertEquals(25.0, defaultPortion(biscuit), 0.0)
    }

    @Test fun defaultWithoutServingIs100() = assertEquals(100.0, defaultPortion(milk), 0.0)

    @Test fun duplicateAmountsCollapse() {
        val p = biscuit.copy(servingSize = 100.0, packSize = 100.0)
        assertEquals(1, portionOptions(p).size)
    }

    @Test fun nutrientsForGlass() = assertEquals(145.0, milk.nutrientsFor(250.0).energyKcal, 0.001)
}
