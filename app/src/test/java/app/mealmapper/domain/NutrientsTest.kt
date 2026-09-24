package app.mealmapper.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NutrientsTest {
    private val biscuit = Nutrients(454.0, 6.9, 76.3, 13.6, sugarG = 25.5)

    @Test fun scalingKeepsNullsNull() {
        val half = biscuit.scaled(0.5)
        assertEquals(227.0, half.energyKcal, 0.001)
        assertEquals(12.75, half.sugarG!!, 0.001)
        assertNull(half.fiberG)
    }

    @Test fun consistentLabelPasses() = assertFalse(biscuit.energyLooksWrong())

    @Test fun kcalTypedAsKjIsCaught() = assertTrue(biscuit.copy(energyKcal = 1900.0).energyLooksWrong())

    @Test fun tinyAmountsAreNotChecked() = assertFalse(Nutrients(2.0, 0.0, 0.0, 0.0).energyLooksWrong())

    @Test fun plusKeepsNullOnlyWhenBothNull() {
        val sum = biscuit + Nutrients(100.0, 1.0, 2.0, 3.0, fiberG = 4.0)
        assertEquals(554.0, sum.energyKcal, 0.001)
        assertEquals(25.5, sum.sugarG!!, 0.001)
        assertEquals(4.0, sum.fiberG!!, 0.001)
        assertNull(sum.sodiumMg)
    }
}
