package app.mealmapper.data.gemini

import app.mealmapper.domain.Basis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiParsingTest {

    private val groundedBody = """
        {"candidates":[{"content":{"parts":[
            {"text":"thinking…","thought":true},
            {"text":"```json\n{\"found\": true, \"product_name\": \"Nutri Choice Digestive\"}\n```"}
          ]},
          "groundingMetadata":{"groundingChunks":[
            {"web":{"uri":"https://vertexaisearch.cloud.google.com/x","title":"britannia.co.in"}},
            {"web":{"uri":"https://vertexaisearch.cloud.google.com/y","title":"bigbasket.com"}},
            {"web":{"uri":"https://vertexaisearch.cloud.google.com/z","title":"britannia.co.in"}}
          ]}}]}
    """

    @Test fun replySkipsThoughtsAndCollectsSites() {
        val r = GeminiParsing.reply(groundedBody)
        assertFalse(r.text.contains("thinking"))
        assertEquals(listOf("britannia.co.in", "bigbasket.com"), r.groundedSites)
    }

    @Test fun replyWithoutGroundingHasNoSites() {
        val r = GeminiParsing.reply("""{"candidates":[{"content":{"parts":[{"text":"{}"}]}}]}""")
        assertTrue(r.groundedSites.isEmpty())
    }

    @Test(expected = GeminiException::class)
    fun blockedPromptThrows() {
        GeminiParsing.reply("""{"promptFeedback":{"blockReason":"SAFETY"}}""")
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
        val n = GeminiParsing.nutrition(digestive)
        assertTrue(n.found)
        assertEquals("Britannia", n.brand)
        assertEquals(125.0, n.packSize!!, 0.0)
        assertEquals(481.0, n.per100!!.energyKcal, 0.0)
        assertEquals(2, n.sourcesAgreeing)
        assertTrue(GeminiParsing.problems(n).isEmpty())
    }

    @Test fun millilitreBasis() {
        val n = GeminiParsing.nutrition("""{"found":true,"basis":"ml","per_100":{"energy_kcal":58,"protein_g":3.1,"carbs_g":4.7,"fat_g":3}}""")
        assertEquals(Basis.MILLILITRES, n.basis)
    }

    @Test fun notFoundHasNoNumbers() {
        val n = GeminiParsing.nutrition("""{"found": false, "note": "no page shows the table"}""")
        assertFalse(n.found)
        assertNull(n.per100)
    }

    @Test fun foundWithoutValuesIsNotFound() {
        assertFalse(GeminiParsing.nutrition("""{"found": true, "per_100": {"energy_kcal": 400}}""").found)
    }

    @Test fun kilojoulesAsKcalIsCaught() {
        val n = GeminiParsing.nutrition(digestive.replace("\"energy_kcal\": 481", "\"energy_kcal\": 2012"))
        assertTrue(GeminiParsing.problems(n).any { it.contains("impossible") })
    }

    @Test fun perServingMismatchIsCaught() {
        val n = GeminiParsing.nutrition(digestive.replace("\"energy_kcal\": 120", "\"energy_kcal\": 481"))
        assertTrue(GeminiParsing.problems(n).any { it.contains("Per-serving") })
    }

    @Test fun sugarAboveCarbsIsCaught() {
        val n = GeminiParsing.nutrition(digestive.replace("\"sugar_g\": 16.8", "\"sugar_g\": 76.8"))
        assertTrue(GeminiParsing.problems(n).any { it.contains("Sugar") })
    }

    @Test(expected = GeminiException::class)
    fun noJsonThrows() {
        GeminiParsing.nutrition("Sorry, I could not find it.")
    }
}
