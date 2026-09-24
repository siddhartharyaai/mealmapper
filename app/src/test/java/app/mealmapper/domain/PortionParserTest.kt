package app.mealmapper.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PortionParserTest {
    private val per100 = Nutrients(450.0, 7.0, 70.0, 15.0)
    private val biscuits = FoodProduct("890", "Glucose biscuits", "Parle", per100, Basis.GRAMS, 25.0, 250.0)
    private val milk = FoodProduct("890", "Toned milk", "Amul", per100, Basis.MILLILITRES, null, 500.0)
    private val noSizes = FoodProduct("890", "Loose namkeen", null, per100, Basis.GRAMS, null, null)

    private fun amount(note: String, p: FoodProduct = biscuits) = parsePortion(note, p)?.amount

    @Test fun grams() = assertEquals(125.0, amount("125 g")!!, 0.0)
    @Test fun gramsNoSpace() = assertEquals(40.0, amount("ate 40gm with chai")!!, 0.0)
    @Test fun kilograms() = assertEquals(200.0, amount("0.2 kg")!!, 0.0)
    @Test fun millilitres() = assertEquals(200.0, amount("200 ml", milk)!!, 0.0)
    @Test fun litres() = assertEquals(1000.0, amount("1 l", milk)!!, 0.0)
    @Test fun halfPack() = assertEquals(125.0, amount("half pack")!!, 0.0)
    @Test fun halfThePacket() = assertEquals(125.0, amount("half the packet")!!, 0.0)
    @Test fun slashFraction() = assertEquals(125.0, amount("1/2 pack")!!, 0.0)
    @Test fun wholePack() = assertEquals(250.0, amount("whole pack")!!, 0.0)
    @Test fun twoServings() = assertEquals(50.0, amount("2 servings")!!, 0.0)
    @Test fun oneServingWord() = assertEquals(25.0, amount("one serving")!!, 0.0)
    @Test fun glassOfMilk() = assertEquals(250.0, amount("1 glass", milk)!!, 0.0)
    @Test fun twoCupsOfMilk() = assertEquals(300.0, amount("2 cups", milk)!!, 0.0)
    @Test fun cupIgnoredForSolids() = assertNull(amount("1 cup"))
    @Test fun explicitWeightBeatsPack() = assertEquals(60.0, amount("half pack, about 60 g")!!, 0.0)
    @Test fun piecesAreNotGuessed() = assertNull(amount("2 biscuits"))
    @Test fun packWithoutPackSize() = assertNull(amount("half pack", noSizes))
    @Test fun emptyNote() = assertNull(amount(""))
    @Test fun explanationMentionsSource() =
        assertEquals("125 g (half pack)", parsePortion("half pack", biscuits)!!.explanation)
}
