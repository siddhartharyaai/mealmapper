package app.mealmapper.data.ai

import app.mealmapper.domain.FoodProduct
import app.mealmapper.domain.ProductSource

/** Result of a web lookup or label read, after the app's own checks. */
sealed interface LookupOutcome {
    data class Found(val product: FoodProduct, val source: ProductSource, val problems: List<String>) : LookupOutcome
    data class NotFound(val reason: String) : LookupOutcome
}

/**
 * AI jobs, all limited to reading, never estimating:
 * - web: find the product's printed nutrition table on real web pages (groq/compound web search).
 * - identify: read brand, variant and pack size from a photo of the pack front (vision model).
 * - label: copy the nutrition table from a photo of the pack (vision model).
 */
class NutritionLookup(private val groq: GroqClient) {

    suspend fun web(barcode: String?, knownName: String?, note: String, frontPhoto: ByteArray?): LookupOutcome {
        // The search system reads no images, so a pack-front photo is first turned into an exact product name.
        val seen = frontPhoto?.let { identify(it) }
        val name = seen ?: knownName
        if (name == null && barcode == null) {
            return LookupOutcome.NotFound("Could not read the product name from the photo. Photograph the label instead.")
        }
        val clues = buildList {
            seen?.let { add("Product as printed on the pack front: $it") }
            if (seen == null) knownName?.let { add("Product name from Open Food Facts: $it") }
            barcode?.let { add("Barcode (EAN): $it") }
            if (note.isNotBlank()) add("User note: $note")
        }.joinToString("\n")
        val reply = groq.webSearch(WEB_PROMPT.replace("{CLUES}", clues))

        // Hard rule: numbers only when the search actually returned pages. Otherwise it could be the model's memory.
        if (reply.sites.isEmpty()) {
            return LookupOutcome.NotFound("The web search returned no pages for this product, so no numbers are shown.")
        }
        val ai = AiParsing.nutrition(reply.text)
        if (!ai.found || ai.per100 == null) {
            return LookupOutcome.NotFound(ai.note ?: "No page showed this product's nutrition table.")
        }
        if (ai.matchConfidence == "uncertain") {
            return LookupOutcome.NotFound("Found pages, but not for this exact variant. Photograph the label instead.")
        }
        return LookupOutcome.Found(
            product = ai.toProduct(barcode, name),
            source = ProductSource.Web(reply.sites, ai.sourcesAgreeing),
            problems = AiParsing.problems(ai),
        )
    }

    /** "Britannia Nutri Choice Digestive, 125 g" from a pack-front photo, or null if unreadable. */
    private suspend fun identify(photo: ByteArray): String? {
        val text = groq.vision(IDENTIFY_PROMPT, listOf(photo)).text.lines().firstOrNull { it.isNotBlank() }?.trim()
        return text?.takeUnless { it.equals("UNKNOWN", ignoreCase = true) || it.length < 3 }?.take(120)
    }

    suspend fun label(labelPhoto: ByteArray, barcode: String?, knownName: String?, note: String): LookupOutcome {
        val prompt = LABEL_PROMPT
            .replace("{NAME}", knownName ?: "unknown")
            .replace("{NOTE}", note.ifBlank { "none" })
        val ai = AiParsing.nutrition(groq.vision(prompt, listOf(labelPhoto)).text)
        if (!ai.found || ai.per100 == null) {
            return LookupOutcome.NotFound(ai.note ?: "No nutrition table found in the photo. Take it closer, flat, in good light.")
        }
        return LookupOutcome.Found(ai.toProduct(barcode, knownName), ProductSource.LabelPhoto, AiParsing.problems(ai))
    }

    private fun AiNutrition.toProduct(barcode: String?, knownName: String?) = FoodProduct(
        barcode = barcode.orEmpty(),
        name = listOfNotNull(productName, variant?.takeUnless { v -> productName?.contains(v, true) == true })
            .joinToString(" ")
            .ifBlank { knownName ?: "Packaged food" },
        brand = brand,
        per100 = per100!!,
        basis = basis,
        servingSize = servingSize,
        packSize = packSize,
    )

    private companion object {
        const val SCHEMA = """{
  "found": true or false,
  "product_name": string, "brand": string, "variant": string (flavour/type exactly as on the pack),
  "basis": "g" or "ml",
  "pack_size": number (net quantity in g or ml) or null,
  "serving_size": number (g or ml per serving as printed) or null,
  "per_100": {"energy_kcal": n, "protein_g": n, "carbs_g": n, "sugar_g": n or null, "fat_g": n,
              "saturated_fat_g": n or null, "fiber_g": n or null, "sodium_mg": n or null},
  "per_serving": {"energy_kcal": n} or null,
  "sources_agreeing": number of independent pages whose values match within 10%,
  "match_confidence": "exact" | "likely" | "uncertain",
  "note": short string (what you found, or why not found)
}"""

        const val WEB_PROMPT = """You find the printed nutrition table of one packaged food product sold in India.

Product clues:
{CLUES}

Rules:
1. Use web search: the manufacturer's site first, then Indian retailers (BigBasket, Blinkit, Zepto, Swiggy Instamart, JioMart, Amazon.in, Flipkart), then Open Food Facts.
2. Match the EXACT variant, flavour and pack size. Brands like Britannia Nutri Choice, Parle, ITC, Haldiram's have many variants with different values. If you cannot confirm the variant, set match_confidence "uncertain".
3. Copy numbers exactly as a page shows them. Never estimate, never use typical values for similar products. If no page shows the table, set "found": false.
4. Values per 100 g (or per 100 ml for drinks). If a page shows only per serving, convert using the serving size and say so in "note".
5. Energy in kcal. If only kJ is shown, divide by 4.184. Sodium in mg (salt g x 400 = sodium mg).
6. Count how many independent pages agree within 10% in "sources_agreeing".

Reply with ONLY this JSON object, no other text:
$SCHEMA"""

        const val IDENTIFY_PROMPT = """The photo shows the front of a packaged food or drink sold in India.
Reply with ONE line: brand, product name, variant/flavour and net quantity exactly as printed,
for example: Britannia Nutri Choice Digestive, 125 g
If you cannot read it, reply: UNKNOWN"""

        const val LABEL_PROMPT = """The photo shows the nutrition information panel of a packaged food sold in India (FSSAI format, English and/or Hindi).
Product (if known): {NAME}. User note: {NOTE}.

Copy the table exactly. Do not estimate or correct anything.
- Use the "per 100 g" (or "per 100 ml") column for per_100. If there is only a per-serving column, convert using the serving size and say so in "note".
- Copy the per-serving energy into per_serving when printed, and the serving size.
- "Total Sugars" is sugar_g (not "Added Sugars"). Energy in kcal; if only kJ, divide by 4.184. Sodium in mg.
- If the photo does not show a readable nutrition table, set "found": false and say why in "note".

Reply with ONLY this JSON object:
$SCHEMA"""
    }
}
