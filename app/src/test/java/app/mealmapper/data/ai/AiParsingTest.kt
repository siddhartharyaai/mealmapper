package app.mealmapper.data.ai

import app.mealmapper.domain.Basis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiParsingTest {

    private val compoundBody = """
        {"model":"groq/compound","choices":[{"message":{"role":"assistant",
          "content":"<think>searching…{not json}</think>```json\n{\"found\": true, \"product_name\": \"Nutri Choice Digestive\"}\n```",
          "executed_tools":[{"type":"search","arguments":"{\"query\":\"nutri choice\"}",
            "search_results":{"results":[
              {"title":"Britannia","url":"https://www.britannia.co.in/nutri-choice","content":"...","score":0.9},
              {"title":"BigBasket","url":"https://www.bigbasket.com/pd/123","content":"..."},
              {"title":"Britannia again","url":"https://britannia.co.in/other","content":"..."}]}}]}}]}
    """

    @Test fun replyStripsThinkingAndCollectsSearchedSites() {
        val r = AiParsing.reply(compoundBody)
        assertFalse(r.text.contains("searching"))
        assertEquals(listOf("britannia.co.in", "bigbasket.com"), r.sites)
        assertEquals("groq/compound", r.model)
        assertTrue(AiParsing.nutrition(r.text).productName == "Nutri Choice Digestive")
    }

    @Test fun replyWithoutToolsHasNoSites() {
        val r = AiParsing.reply("""{"choices":[{"message":{"content":"{}"}}]}""")
        assertTrue(r.sites.isEmpty())
    }

    @Test(expected = AiException::class)
    fun errorBodyThrows() {
        AiParsing.reply("""{"error":{"message":"Invalid API Key","type":"invalid_request_error"}}""")
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
        val body = """{"error":{"message":"Rate limit reached for model qwen/qwen3.8-27b\nPlease try again","type":"tokens","code":"rate_limit_exceeded"}}"""
        assertEquals("Rate limit reached for model qwen/qwen3.8-27b", AiParsing.errorMessage(body))
    }

    @Test fun errorMessageFromGarbage() = assertNull(AiParsing.errorMessage("<html>"))

    @Test fun browserSearchSitesFromToolText() {
        val body = """{"choices":[{"message":{"content":"{}","executed_tools":[{"type":"browser_search",
            "output":"【0†Nutri Choice Digestive†www.bigbasket.com】 Opened https://www.bigbasket.com/pd/40012345/ and https://britannia.co.in/nutri."}]}}]}"""
        assertEquals(listOf("bigbasket.com", "britannia.co.in"), AiParsing.reply(body).sites)
    }

    @Test fun browserSnippetsBeforeAnswer() {
        val content = "【0†Nutri Choice†https://www.bigbasket.com/pd/4001/】 Ingredients {wheat} … " +
            "per 100 g energy 481 kcal (https://britannia.co.in/nutri).\n" +
            "{\"found\": true, \"product_name\": \"Nutri Choice\", \"per_100\": {\"energy_kcal\": 481, " +
            "\"protein_g\": 7.9, \"carbs_g\": 66.4, \"fat_g\": 20.6}, \"note\": \"see https://fake.example.com\"}"
        val body = """{"choices":[{"message":{"content":${kotlinx.serialization.json.JsonPrimitive(content)}}}]}"""
        val r = AiParsing.reply(body)
        assertEquals(listOf("bigbasket.com", "britannia.co.in"), r.sites)
        assertEquals(481.0, AiParsing.nutrition(r.text).per100!!.energyKcal, 0.0)
    }

    @Test fun lastJsonObjectSkipsStrayBraces() =
        assertEquals("""{"a":1}""", AiParsing.lastJsonObject("snippet {not json} then {\"a\":1}"))
}
