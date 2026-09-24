package app.mealmapper.data.ai

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

/** A model's answer plus the web sites its search tool actually visited (empty when no search ran). */
data class AiReply(
    val text: String,
    val sites: List<String>,
    val model: String = "",
    /** True when Google Search actually ran (a google_search_call step), even if nothing was cited. */
    val searched: Boolean = false,
)

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
object AiParsing {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Parses a Gemini Interactions response: text from "model_output" steps, sources from url_citation
     * annotations (the pages Google Search actually used). Thought steps are ignored.
     */
    fun interaction(body: String): AiReply {
        val root = json.parseToJsonElement(body).jsonObject
        val steps = (root["steps"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val blocks = steps.filter { it.str("type") == "model_output" }
            .flatMap { (it["content"] as? JsonArray).orEmpty() }
            .mapNotNull { it as? JsonObject }
            .filter { it.str("type") == null || it.str("type") == "text" }
        val text = blocks.mapNotNull { it.str("text") }.joinToString("\n")
            .ifBlank { root.str("output_text").orEmpty() }
        if (text.isBlank()) throw AiException(errorMessage(body) ?: "Gemini returned no answer.")
        val citations = blocks.flatMap { (it["annotations"] as? JsonArray).orEmpty() }
            .mapNotNull { it as? JsonObject }
            .filter { it.str("type") == "url_citation" }
        val sites = citations.mapNotNull { c -> c.str("title")?.removePrefix("www.") ?: c.str("url")?.let(::site) }
            .distinct()
        val searched = steps.any { it.str("type") == "google_search_call" }
        return AiReply(stripThinking(text), sites, root.str("model").orEmpty(), searched)
    }

    /** Items the AI sees on a plate. Estimates, never presented as measured values. */
    data class MealItem(
        val name: String,
        val grams: Double,
        val per100: Nutrients,
        val lowKcal: Double?,
        val highKcal: Double?,
        val assumption: String?,
    )

    /**
     * Parses the meal JSON. The model reports totals for its estimated portion; we convert to per-100 g
     * so the user's gram edits rescale every nutrient consistently. Items without grams or energy are dropped.
     */
    fun meal(text: String): List<MealItem> {
        val raw = lastJsonObject(text) ?: throw AiException("The AI's answer had no readable data in it.")
        val items = (json.parseToJsonElement(raw).jsonObject["items"] as? JsonArray).orEmpty()
        return items.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val name = o.str("name") ?: return@mapNotNull null
            val grams = o.num("grams")?.takeIf { it > 0 } ?: return@mapNotNull null
            val kcal = o.num("kcal") ?: return@mapNotNull null
            val f = 100.0 / grams
            MealItem(
                name = name,
                grams = grams,
                per100 = Nutrients(
                    energyKcal = kcal * f,
                    proteinG = (o.num("protein_g") ?: 0.0) * f,
                    carbsG = (o.num("carbs_g") ?: 0.0) * f,
                    fatG = (o.num("fat_g") ?: 0.0) * f,
                    saturatedFatG = o.num("saturated_fat_g")?.times(f),
                    sugarG = o.num("sugar_g")?.times(f),
                    fiberG = o.num("fiber_g")?.times(f),
                    sodiumMg = o.num("sodium_mg")?.times(f),
                ),
                lowKcal = o.num("kcal_low"),
                highKcal = o.num("kcal_high"),
                assumption = o.str("assumption"),
            )
        }
    }

    /** Web sites named in the answer text (used only when Google attached no citations). */
    fun sitesInText(text: String): List<String> =
        Regex("""https?://[^\s)\]>"']+""").findAll(text).mapNotNull { site(it.value) }.distinct().take(5).toList()
            .ifEmpty {
                Regex("""\b(?:www\.)?([a-z0-9-]+\.(?:com|in|co\.in|org|net))\b""", RegexOption.IGNORE_CASE)
                    .findAll(text).map { it.groupValues[1].lowercase() }.distinct().take(5).toList()
            }

    /** Reasoning models may put their thinking in <think> tags before the answer. */
    fun stripThinking(text: String): String = text.replace(Regex("(?s)<think>.*?</think>"), "").trim()

    private fun site(url: String): String? = runCatching {
        java.net.URI(url).host?.removePrefix("www.")
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * The last complete JSON object in the text. Web snippets before the answer can contain stray braces,
     * so we try each "{" from the end until one parses as an object through the final "}".
     */
    fun lastJsonObject(text: String): String? {
        val end = text.lastIndexOf('}')
        if (end < 0) return null
        var start = text.lastIndexOf('{', end)
        while (start >= 0) {
            val candidate = text.substring(start, end + 1)
            if (runCatching { json.parseToJsonElement(candidate).jsonObject }.isSuccess) return candidate
            start = text.lastIndexOf('{', start - 1)
        }
        return null
    }

    /** The "message" field of an API error body, if any. */
    fun errorMessage(body: String): String? = runCatching {
        ((json.parseToJsonElement(body).jsonObject["error"] as? JsonObject)?.get("message") as? JsonPrimitive)?.content
    }.getOrNull()?.lines()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    /** Finds the JSON object in the model's text (it may wrap it in ``` fences or add a sentence). */
    fun nutrition(text: String): AiNutrition {
        val raw = lastJsonObject(text) ?: throw AiException("The AI's answer had no readable data in it.")
        val o = json.parseToJsonElement(raw).jsonObject

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

class AiException(message: String) : Exception(message)
