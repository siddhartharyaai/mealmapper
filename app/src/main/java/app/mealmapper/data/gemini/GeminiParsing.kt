package app.mealmapper.data.gemini

import app.mealmapper.domain.Basis
import app.mealmapper.domain.Nutrients
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.math.abs
import kotlin.math.max

/** Raw reply from generateContent: the model's text plus the web pages Google Search grounded it on. */
data class GeminiReply(val text: String, val groundedSites: List<String>)

/** Nutrition facts as the model reported them, before the app's own checks. */
data class AiNutrition(
    val found: Boolean,
    val productName: String?,
    val brand: String?,
    val variant: String?,
    val basis: Basis,
    val packSize: Double?,
    val servingSize: Double?,
    val per100: Nutrients?,
    /** As printed per serving, when the label or page shows it. Used to cross-check per 100. */
    val perServingKcal: Double?,
    val sourcesAgreeing: Int,
    val matchConfidence: String?,
    val note: String?,
)

/** Pure parsing and checks. No Android, no network: everything here is unit-tested. */
object GeminiParsing {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Extracts text parts and grounded site titles from a generateContent response body. */
    fun reply(body: String): GeminiReply {
        val root = json.parseToJsonElement(body).jsonObject
        val candidate = (root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: throw GeminiException(blockReason(root) ?: "Gemini returned no answer.")
        val parts = ((candidate["content"] as? JsonObject)?.get("parts") as? JsonArray).orEmpty()
        val text = parts.mapNotNull { part ->
            val p = part as? JsonObject ?: return@mapNotNull null
            // Thought parts are the model's reasoning, not its answer.
            if ((p["thought"] as? JsonPrimitive)?.content == "true") null else p.str("text")
        }.joinToString("")
        val chunks = ((candidate["groundingMetadata"] as? JsonObject)?.get("groundingChunks") as? JsonArray).orEmpty()
        val sites = chunks.mapNotNull { chunk ->
            val web = (chunk as? JsonObject)?.get("web") as? JsonObject ?: return@mapNotNull null
            web.str("title") ?: web.str("uri")
        }.distinct()
        return GeminiReply(text, sites)
    }

    private fun blockReason(root: JsonObject): String? =
        (root["promptFeedback"] as? JsonObject)?.str("blockReason")?.let { "Gemini refused the request ($it)." }

    /** Finds the JSON object in the model's text (it may wrap it in ``` fences or add a sentence). */
    fun nutrition(text: String): AiNutrition {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) throw GeminiException("Gemini's answer had no data in it.")
        val o = runCatching { json.parseToJsonElement(text.substring(start, end + 1)).jsonObject }
            .getOrElse { throw GeminiException("Gemini's answer was not readable.") }

        val per100 = (o["per_100"] as? JsonObject)?.let { n ->
            val kcal = n.num("energy_kcal")
            val p = n.num("protein_g")
            val c = n.num("carbs_g")
            val f = n.num("fat_g")
            if (kcal == null || p == null || c == null || f == null) {
                null
            } else {
                Nutrients(
                    energyKcal = kcal, proteinG = p, carbsG = c, fatG = f,
                    saturatedFatG = n.num("saturated_fat_g"),
                    sugarG = n.num("sugar_g"),
                    fiberG = n.num("fiber_g"),
                    sodiumMg = n.num("sodium_mg"),
                )
            }
        }
        return AiNutrition(
            found = (o["found"] as? JsonPrimitive)?.content == "true" && per100 != null,
            productName = o.str("product_name"),
            brand = o.str("brand"),
            variant = o.str("variant"),
            basis = if (o.str("basis")?.lowercase() == "ml") Basis.MILLILITRES else Basis.GRAMS,
            packSize = o.num("pack_size"),
            servingSize = o.num("serving_size"),
            per100 = per100,
            perServingKcal = (o["per_serving"] as? JsonObject)?.num("energy_kcal"),
            sourcesAgreeing = o.num("sources_agreeing")?.toInt() ?: 0,
            matchConfidence = o.str("match_confidence"),
            note = o.str("note"),
        )
    }

    /**
     * The app's own checks on AI-reported numbers. Returns human-readable problems; empty means it passed.
     * These catch misreads (a 7 read as 1), kJ reported as kcal, and per-serving values reported as per 100.
     */
    fun problems(n: AiNutrition): List<String> {
        val v = n.per100 ?: return listOf("No nutrition values.")
        val out = mutableListOf<String>()
        val macros = v.proteinG + v.carbsG + v.fatG
        if (v.energyKcal > MAX_KCAL_PER_100) out += "Calories above ${MAX_KCAL_PER_100.toInt()} per 100 are impossible."
        if (macros > MAX_MACROS_PER_100) out += "Protein + carbs + fat add up to more than 100 g per 100."
        if (v.energyLooksWrong()) out += "Calories do not match protein, carbs and fat."
        if ((v.sugarG ?: 0.0) > v.carbsG + TOLERANCE_G) out += "Sugar is more than total carbohydrate."
        if ((v.saturatedFatG ?: 0.0) > v.fatG + TOLERANCE_G) out += "Saturated fat is more than total fat."
        val serving = n.servingSize
        val perServing = n.perServingKcal
        if (serving != null && serving > 0 && perServing != null) {
            val expected = v.energyKcal * serving / 100.0
            if (max(expected, perServing) > MIN_KCAL_TO_COMPARE &&
                abs(expected - perServing) / max(expected, perServing) > SERVING_TOLERANCE
            ) {
                out += "Per-serving and per-100 values do not agree."
            }
        }
        return out
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.asDouble()

    private fun JsonElement.asDouble(): Double? =
        (this as? JsonPrimitive)?.content?.trim()?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }

    private const val MAX_KCAL_PER_100 = 900.0
    private const val MAX_MACROS_PER_100 = 102.0
    private const val TOLERANCE_G = 0.5
    private const val SERVING_TOLERANCE = 0.08
    private const val MIN_KCAL_TO_COMPARE = 10.0
}

class GeminiException(message: String) : Exception(message)
