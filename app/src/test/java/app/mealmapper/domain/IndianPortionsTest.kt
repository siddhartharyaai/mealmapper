package app.mealmapper.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IndianPortionsTest {
    @Test fun rotiScalesWithArea() {
        val roti = IndianPortions.modelFor("Chapati/Roti")!!
        assertEquals("18 cm", roti.sizes[roti.defaultSize].label)
        assertEquals(35.0, roti.sizes[1].grams, 0.1)
        assertEquals(24.0, roti.sizes[0].grams, 0.1) // 15 cm: 35 x (15/18)^2
        assertEquals(105.0, roti.grams(3.0, 1), 0.1)
    }

    @Test fun phulkaIsNotRoti() = assertEquals("phulka", IndianPortions.modelFor("Phulka")!!.noun)

    @Test fun indianBreadDefaultsToSmallLoaf() {
        val bread = IndianPortions.modelFor("Bread, white, average")!!
        assertEquals(22.0, bread.sizes[bread.defaultSize].grams, 0.1)
        assertEquals("2 slices · Indian sandwich loaf (small)", bread.describe(2.0, 0))
    }

    @Test fun eggplantIsNotAnEgg() = assertNull(IndianPortions.modelFor("Eggplant/Brinjal rice (Vangi bhat)"))

    @Test fun dalIsNotCounted() = assertNull(IndianPortions.modelFor("Dal makhani"))
}
