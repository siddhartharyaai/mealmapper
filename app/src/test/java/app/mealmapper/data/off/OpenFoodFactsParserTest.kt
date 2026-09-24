package app.mealmapper.data.off

import app.mealmapper.domain.Basis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenFoodFactsParserTest {

    private fun product(body: String) =
        (OpenFoodFactsParser.parse("8901234567890", body) as ParseResult.Product).product

    @Test fun biscuitPer100g() {
        val p = product(
            """
            {"status":1,"product":{
              "product_name":"Glucose Biscuits","brands":"Parle, Parle Products",
              "quantity":"250 g","product_quantity":"250","product_quantity_unit":"g",
              "serving_quantity":"25",
              "nutriments":{"energy-kcal_100g":454,"proteins_100g":6.9,"carbohydrates_100g":76.3,
                "fat_100g":13.6,"saturated-fat_100g":6.4,"sugars_100g":25.5,"sodium_100g":0.286}}}
            """,
        )
        assertEquals("Glucose Biscuits", p.name)
        assertEquals("Parle", p.brand)
        assertEquals(Basis.GRAMS, p.basis)
        assertEquals(25.0, p.servingSize!!, 0.0)
        assertEquals(250.0, p.packSize!!, 0.0)
        assertEquals(454.0, p.per100.energyKcal, 0.001)
        assertEquals(286.0, p.per100.sodiumMg!!, 0.001)
        assertNull(p.per100.fiberG)
    }

    @Test fun englishNameWinsAndMilkIsMillilitres() {
        val p = product(
            """
            {"status":1,"product":{"product_name":"अमूल ताज़ा","product_name_en":"Amul Taaza Toned Milk",
              "quantity":"500 ml",
              "nutriments":{"energy-kcal_100g":"58","proteins_100g":"3,1","carbohydrates_100g":4.7,"fat_100g":3}}}
            """,
        )
        assertEquals("Amul Taaza Toned Milk", p.name)
        assertEquals(Basis.MILLILITRES, p.basis)
        assertEquals(3.1, p.per100.proteinG, 0.001)
    }

    @Test fun kilojoulesOnlyAreConverted() {
        val p = product(
            """{"status":1,"product":{"product_name":"X","nutriments":{"energy_100g":1000,"proteins_100g":1,"carbohydrates_100g":1,"fat_100g":1}}}""",
        )
        assertEquals(239.0, p.per100.energyKcal, 0.1)
    }

    @Test fun saltConvertedToSodiumWhenSodiumMissing() {
        val p = product(
            """{"status":1,"product":{"product_name":"Namkeen","nutriments":{"energy-kcal_100g":550,"proteins_100g":10,"carbohydrates_100g":50,"fat_100g":34,"salt_100g":2.5}}}""",
        )
        assertEquals(1000.0, p.per100.sodiumMg!!, 0.001)
    }

    @Test fun perServingOnlyIsScaledTo100g() {
        val p = product(
            """{"status":1,"product":{"product_name":"Bar","serving_quantity":40,
              "nutriments":{"energy-kcal_serving":180,"proteins_serving":8,"carbohydrates_serving":20,"fat_serving":7}}}""",
        )
        assertEquals(450.0, p.per100.energyKcal, 0.001)
        assertEquals(20.0, p.per100.proteinG, 0.001)
    }

    @Test fun missingEnergyIsComputedFromMacros() {
        val p = product(
            """{"status":1,"product":{"product_name":"Dal","nutriments":{"proteins_100g":24,"carbohydrates_100g":60,"fat_100g":1.5}}}""",
        )
        assertEquals(4 * 24 + 4 * 60 + 9 * 1.5, p.per100.energyKcal, 0.001)
    }

    @Test fun productWithoutNutritionAsksForLabel() {
        val r = OpenFoodFactsParser.parse("890", """{"status":1,"product":{"product_name":"Haldiram Bhujia","nutriments":{}}}""")
        assertEquals(ParseResult.NoNutrition("Haldiram Bhujia"), r)
    }

    @Test fun unknownBarcode() {
        val r = OpenFoodFactsParser.parse("890", """{"code":"890","status":0,"status_verbose":"product not found"}""")
        assertEquals(ParseResult.NotFound, r)
    }

    @Test fun garbageBodyIsNotFound() {
        assertTrue(OpenFoodFactsParser.parse("890", "<html>busy</html>") is ParseResult.NotFound)
    }

    @Test fun negativeAndBrokenNumbersAreIgnored() {
        val p = product(
            """{"status":1,"product":{"product_name":"X","nutriments":{"energy-kcal_100g":100,"proteins_100g":"n/a","carbohydrates_100g":-5,"fat_100g":2}}}""",
        )
        assertEquals(0.0, p.per100.proteinG, 0.0)
        assertEquals(0.0, p.per100.carbsG, 0.0)
    }
}
