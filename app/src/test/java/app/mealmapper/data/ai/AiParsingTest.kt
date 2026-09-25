package app.mealmapper.data.ai

import app.mealmapper.domain.Basis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiParsingTest {

    // Shape from ai.google.dev/gemini-api/docs/google-search (Interactions API, 24 Sep 2026).
    private val groundedBody = """
        {"steps":[
          {"type":"thought","summary":[{"type":"text","text":"I need to search for it."}]},
          {"type":"google_search_call","arguments":{"queries":["nutri choice digestive nutrition"]}},
          {"type":"google_search_result","call_id":"search_001","result":[{"search_suggestions":"<div/>"}]},
          {"type":"model_output","content":[{"type":"text",
            "text":"```json\n{\"found\": true, \"product_name\": \"Nutri Choice Digestive\"}\n```",
            "annotations":[
              {"type":"url_citation","url":"https://www.britannia.co.in/nutri-choice","title":"britannia.co.in","start_index":0,"end_index":10},
              {"type":"url_citation","url":"https://www.bigbasket.com/pd/1","title":"bigbasket.com","start_index":11,"end_index":20},
              {"type":"url_citation","url":"https://britannia.co.in/x","title":"britannia.co.in","start_index":21,"end_index":30}]}]}]}
    """

    @Test fun interactionTextAndCitedSites() {
        val r = AiParsing.interaction(groundedBody)
        assertFalse(r.text.contains("search for it"))
        assertEquals(listOf("britannia.co.in", "bigbasket.com"), r.sites)
        assertEquals("Nutri Choice Digestive", AiParsing.nutrition(r.text).productName)
    }

    @Test fun interactionWithoutSearchHasNoSites() {
        val r = AiParsing.interaction("""{"steps":[{"type":"model_output","content":[{"type":"text","text":"{}"}]}]}""")
        assertTrue(r.sites.isEmpty())
    }

    @Test fun citationWithoutTitleUsesHost() {
        val body = """{"steps":[{"type":"model_output","content":[{"type":"text","text":"x",
            "annotations":[{"type":"url_citation","url":"https://www.amazon.in/dp/1"}]}]}]}"""
        assertEquals(listOf("amazon.in"), AiParsing.interaction(body).sites)
    }

    @Test(expected = AiException::class)
    fun errorBodyThrows() {
        AiParsing.interaction("""{"error":{"code":429,"message":"Quota exceeded","status":"RESOURCE_EXHAUSTED"}}""")
    }

    private val digestive = """
        Here you go:
        {"found": true, "product_name": "Nutri Choice Digestive", "brand": "Britannia", "variant": "Digestive",
         "basis": "g", "pack_size": "125", "serving_size": 25,
         "per_100": {"energy_kcal": 481, "protein_g": 7.9, "carbs_g": 66.4, "sugar_g": 16.8, "fat_g": 20.6,
                     "saturated_fat_g": 9.9, "fiber_g": 6.1, "sodium_mg": 420},
         "per_serving": {"energy_kcal": 120},
         "sources_agreeing": 2, "match_confidence": "exact"}
    """

    @Test fun nutritionFromWrappedText() {
        val n = AiParsing.nutrition(digestive)
        assertTrue(n.found)
        assertEquals("Britannia", n.brand)
        assertEquals(125.0, n.packSize!!, 0.0)
        assertEquals(481.0, n.per100!!.energyKcal, 0.0)
        assertEquals(2, n.sourcesAgreeing)
        assertTrue(AiParsing.problems(n).isEmpty())
    }

    @Test fun millilitreBasis() {
        val n = AiParsing.nutrition("""{"found":true,"basis":"ml","per_100":{"energy_kcal":58,"protein_g":3.1,"carbs_g":4.7,"fat_g":3}}""")
        assertEquals(Basis.MILLILITRES, n.basis)
    }

    @Test fun notFoundHasNoNumbers() {
        val n = AiParsing.nutrition("""{"found": false, "note": "no page shows the table"}""")
        assertFalse(n.found)
        assertNull(n.per100)
    }

    @Test fun foundWithoutValuesIsNotFound() {
        assertFalse(AiParsing.nutrition("""{"found": true, "per_100": {"energy_kcal": 400}}""").found)
    }

    @Test fun kilojoulesAsKcalIsCaught() {
        val n = AiParsing.nutrition(digestive.replace("\"energy_kcal\": 481", "\"energy_kcal\": 2012"))
        assertTrue(AiParsing.problems(n).any { it.contains("impossible") })
    }

    @Test fun perServingMismatchIsCaught() {
        val n = AiParsing.nutrition(digestive.replace("\"energy_kcal\": 120", "\"energy_kcal\": 481"))
        assertTrue(AiParsing.problems(n).any { it.contains("Per-serving") })
    }

    @Test fun sugarAboveCarbsIsCaught() {
        val n = AiParsing.nutrition(digestive.replace("\"sugar_g\": 16.8", "\"sugar_g\": 76.8"))
        assertTrue(AiParsing.problems(n).any { it.contains("Sugar") })
    }

    @Test(expected = AiException::class)
    fun noJsonThrows() {
        AiParsing.nutrition("Sorry, I could not find it.")
    }

    @Test fun errorMessageFromBody() {
        val body = """{"error":{"message":"Quota exceeded for model gemini-3.8-flash\nPlease try again","type":"tokens","code":"rate_limit_exceeded"}}"""
        assertEquals("Quota exceeded for model gemini-3.8-flash", AiParsing.errorMessage(body))
    }

    @Test fun errorMessageFromGarbage() = assertNull(AiParsing.errorMessage("<html>"))

    @Test fun lastJsonObjectSkipsStrayBraces() =
        assertEquals("""{"a":1}""", AiParsing.lastJsonObject("snippet {not json} then {\"a\":1}"))

    @Test fun searchRanWithoutCitations() {
        val body = """{"steps":[{"type":"google_search_call","arguments":{"queries":["x"]}},
            {"type":"model_output","content":[{"type":"text","text":"{}"}]}]}"""
        val r = AiParsing.interaction(body)
        assertTrue(r.searched)
        assertTrue(r.sites.isEmpty())
    }

    @Test fun mealItemsScaleToPer100() {
        val text = """{"items":[
            {"name":"Dal tadka","grams":180,"kcal":198,"protein_g":10.8,"carbs_g":25.2,"fat_g":5.4,"kcal_low":160,"kcal_high":240,"assumption":"1 tsp ghee tadka"},
            {"name":"Phulka","grams":60,"kcal":170,"protein_g":5.4,"carbs_g":33,"fat_g":1.8},
            {"name":"no grams","kcal":10}]}"""
        val items = AiParsing.meal(text)
        assertEquals(2, items.size)
        assertEquals(110.0, items[0].per100.energyKcal, 0.01)
        assertEquals(198.0, items[0].per100.scaled(1.8).energyKcal, 0.01)
        assertEquals("1 tsp ghee tadka", items[0].assumption)
        assertNull(items[1].lowKcal)
    }

    @Test fun sitesInTextFromUrlsThenBareDomains() {
        assertEquals(
            listOf("bigbasket.com", "snackible.com"),
            AiParsing.sitesInText("See https://www.bigbasket.com/pd/123/ and (https://snackible.com/products/x)."),
        )
        assertEquals(listOf("blinkit.com"), AiParsing.sitesInText("Blinkit.com lists 420 kcal per 100 g."))
        assertTrue(AiParsing.sitesInText("no pages here").isEmpty())
    }

    @Test fun picksIgnoreIdsThatWereNotOffered() {
        val text = """{"picks":[{"item":1,"id":"ASC107"},{"item":2,"id":null},{"item":3,"id":"MADEUP"}]}"""
        val picks = AiParsing.picks(text, mapOf(0 to setOf("ASC107"), 1 to setOf("X"), 2 to setOf("Y")))
        assertEquals(mapOf(0 to "ASC107"), picks)
    }

    @Test fun brandedItemCarriesLookup() {
        val text = """{"items":[
            {"name":"Qbit Green","grams":5,"kcal":18,"protein_g":1,"carbs_g":3,"fat_g":0.2,"lookup":"Qbit Green superfood powder nutrition facts"},
            {"name":"Soaked walnut","grams":2,"kcal":13,"protein_g":0.3,"carbs_g":0.3,"fat_g":1.3,"lookup":null}]}"""
        val items = AiParsing.meal(text)
        assertEquals("Qbit Green superfood powder nutrition facts", items[0].lookup)
        assertNull(items[1].lookup)
    }

    @Test fun routingFields() {
        val text = """{"items":[
            {"name":"Qbit Green","grams":5,"kcal":18,"protein_g":1,"carbs_g":3,"fat_g":0.2,"kind":"branded","search_name":"Qbit Green powder nutrition facts"},
            {"name":"Dal tadka","grams":150,"kcal":170,"protein_g":8,"carbs_g":20,"fat_g":6,"kind":"dish","search_name":"Toor dal tadka (arhar dal)"}]}"""
        val items = AiParsing.meal(text)
        assertEquals("branded", items[0].kind)
        assertEquals("Qbit Green powder nutrition facts", items[0].lookup) // branded -> web lookup even without "lookup"
        assertEquals("Toor dal tadka (arhar dal)", items[1].searchName)
        assertNull(items[1].lookup)
    }
}
