package app.mealmapper.data.ai

import app.mealmapper.domain.DbFood
import app.mealmapper.domain.FoodSearch
import app.mealmapper.domain.FoodProduct
import app.mealmapper.domain.ProductSource

/** Kitchen calibration from Settings, for home-food estimates. */
data class Kitchen(val katoriMl: Int = 150, val oilGramsPerPersonDay: Double? = null)

/** Result of a web lookup or label read, after the app's own checks. */
sealed interface LookupOutcome {
    data class Found(val product: FoodProduct, val source: ProductSource, val problems: List<String>) : LookupOutcome
    data class NotFound(val reason: String) : LookupOutcome
}

/**
 * Where nutrition values come from, never from the model's memory when a real source exists.
 * - web: a packaged product's printed nutrition table on real pages (Gemini with Google Search).
 * - webFood: per-100 g values for a generic food or dish from reliable sources (IFCT, INDB, USDA, CoFID).
 * - webRestaurant: a restaurant dish: the restaurant's published values, else comparable dishes online.
 * - resolve: the source ladder that decides, per item, which of these applies.
 */
class NutritionLookup(private val gemini: GeminiClient) {

    suspend fun web(barcode: String?, knownName: String?, note: String = ""): LookupOutcome {
        val name = knownName
        if (name == null && barcode == null) return LookupOutcome.NotFound("No product name or barcode to search for.")
        val clues = buildList {
            knownName?.let { add("Product: $it") }
            barcode?.let { add("Barcode (EAN): $it") }
            if (note.isNotBlank()) add("User note: $note")
        }.joinToString("\n")
        val what = name ?: "barcode $barcode"

        // Step 1: search and report in plain prose (keeps Google's citations). Step 2: extract JSON, text only.
        val research = gemini.webSearch(RESEARCH_PROMPT.replace("{CLUES}", clues))
        if (!research.searched && research.sites.isEmpty()) {
            return LookupOutcome.NotFound(
                "Google Search did not run for $what. In Google AI Studio check that billing is on for this key's project.",
            )
        }
        if (research.text.contains("NOT_FOUND") && research.text.length < 400) {
            return LookupOutcome.NotFound("Searched the web for $what, but no page shows its nutrition table.")
        }
        val sites = research.sites.ifEmpty { AiParsing.sitesInText(research.text) }
        val ai = AiParsing.nutrition(gemini.text(EXTRACT_PROMPT.replace("{REPORT}", research.text)).text)
        if (!ai.found || ai.per100 == null) {
            return LookupOutcome.NotFound(ai.note ?: "Searched the web for $what, but no page shows its nutrition table.")
        }
        if (ai.matchConfidence == "uncertain") {
            return LookupOutcome.NotFound("Found pages for $what, but not for this exact variant.")
        }
        return LookupOutcome.Found(
            product = ai.toProduct(barcode, name),
            source = ProductSource.Web(sites, ai.sourcesAgreeing, cited = research.sites.isNotEmpty()),
            problems = AiParsing.problems(ai),
        )
    }

    /** Per-100 g values for a generic food or dish from reliable web sources (IFCT, INDB, USDA, CoFID…). */
    suspend fun webFood(name: String): LookupOutcome = research(FOOD_RESEARCH_PROMPT.replace("{FOOD}", name), name)

    /**
     * A restaurant dish. The restaurant's own published values when it has them (chains, some Zomato/Swiggy
     * listings); otherwise the same dish from comparable restaurants or nutrition sites: a reasoned estimate.
     */
    suspend fun webRestaurant(dish: String, restaurant: String?): LookupOutcome = research(
        RESTAURANT_RESEARCH_PROMPT.replace("{DISH}", dish).replace("{RESTAURANT}", restaurant ?: "unknown, in Mumbai"),
        dish,
    )

    private suspend fun research(prompt: String, name: String): LookupOutcome {
        val research = gemini.webSearch(prompt)
        if (!research.searched && research.sites.isEmpty()) return LookupOutcome.NotFound("Google Search did not run.")
        if (research.text.contains("NOT_FOUND") && research.text.length < 400) {
            return LookupOutcome.NotFound("No reliable page for $name.")
        }
        val sites = research.sites.ifEmpty { AiParsing.sitesInText(research.text) }
        val ai = AiParsing.nutrition(gemini.text(EXTRACT_PROMPT.replace("{REPORT}", research.text)).text)
        if (!ai.found || ai.per100 == null) return LookupOutcome.NotFound("No values for $name on the pages found.")
        return LookupOutcome.Found(
            ai.toProduct(null, name),
            ProductSource.Web(sites, ai.sourcesAgreeing, cited = research.sites.isNotEmpty()),
            AiParsing.problems(ai),
        )
    }

    /** What [resolve] produced: items with their values, and the databank row used for each matched item. */
    data class Resolved(val items: List<AiParsing.MealItem>, val matched: Map<Int, DbFood>)

    /**
     * The source ladder. Every item goes down it until something factual is found; the AI's own numbers are last.
     *  1. Named products ("branded"): the product's own label values from the web.
     *  2. Restaurant dishes: the restaurant's published values, else comparable dishes online.
     *  3. Everything else: the food databank (INDB cooked recipes, IFCT 2017, CoFID); Gemini picks the same dish
     *     from local candidates and may say "none".
     *  4. Not in the databank: per-100 g values from reliable web sources (IFCT, INDB, USDA, CoFID).
     *  5. Still nothing: the AI's estimate, shown as such.
     */
    suspend fun resolve(estimated: List<AiParsing.MealItem>, foods: List<DbFood>, progress: (String) -> Unit): Resolved {
        var items = estimated
        val products = items.filter { it.lookup != null && !it.published }
        if (products.isNotEmpty()) {
            progress("Looking up ${products.joinToString { it.name }} online…")
            items = items.map { item -> if (item.lookup != null && !item.published) fromWeb(item, web(null, item.lookup), "product") else item }
        }
        val dishes = items.filter { it.kind == "restaurant" && !it.published }
        if (dishes.isNotEmpty()) {
            progress("Finding restaurant values for ${dishes.joinToString { it.name }}…")
            items = items.map { item ->
                if (item.kind == "restaurant" && !item.published) {
                    fromWeb(item, webRestaurant(item.searchName ?: item.name, item.restaurant), "restaurant")
                } else {
                    item
                }
            }
        }

        val open = { i: Int -> !items[i].published && items[i].lookup == null && items[i].kind != "restaurant" }
        if (items.indices.none(open)) return Resolved(items, emptyMap())
        progress("Checking the food databank…")
        val candidates = items.indices.map { i ->
            if (!open(i)) {
                emptyList()
            } else {
                val byName = FoodSearch.candidates(foods, items[i].searchName ?: items[i].name)
                (byName + FoodSearch.candidates(foods, items[i].name)).distinctBy { it.id }.take(10)
            }
        }
        val matched = runCatching {
            pickFromDb(items, candidates).mapNotNull { (i, id) -> foods.firstOrNull { it.id == id }?.let { i to it } }.toMap()
        }.getOrDefault(emptyMap())

        val missing = items.indices.filter { open(it) && it !in matched }.take(MAX_WEB_ITEMS)
        if (missing.isNotEmpty()) {
            progress("Not in the databank: looking up ${missing.joinToString { items[it].name }} online…")
            items = items.mapIndexed { i, item ->
                if (i in missing) fromWeb(item, webFood(item.searchName ?: item.name), "food") else item
            }
        }
        return Resolved(items, matched)
    }

    /** Applies a web result to an item, or marks the item as the AI's estimate when nothing was found. */
    private fun fromWeb(item: AiParsing.MealItem, outcome: LookupOutcome?, what: String): AiParsing.MealItem {
        val found = outcome as? LookupOutcome.Found
            ?: return item.copy(
                lookup = null,
                assumption = when (what) {
                    "product" -> "Not found online: AI estimate, check the pack."
                    "restaurant" -> "No restaurant values found: AI estimate for a restaurant portion."
                    else -> item.assumption
                },
            )
        val p = found.product
        val sites = (found.source as? ProductSource.Web)?.sites.orEmpty().take(2).joinToString()
        val label = when (what) {
            "restaurant" -> "Restaurant values online"
            "product" -> "Label values online"
            else -> "Found online"
        }
        return item.copy(
            per100 = p.per100,
            grams = if (item.grams > 0) item.grams else p.servingSize ?: 100.0,
            servingGrams = p.servingSize ?: item.servingGrams,
            lowKcal = null,
            highKcal = null,
            assumption = label + (if (sites.isNotEmpty()) " ($sites)" else "") +
                (if (found.problems.isNotEmpty()) " · check: ${found.problems.first()}" else ""),
            published = true,
            lookup = null,
        )
    }

    /**
     * For each item, asks the model which databank row is the same dish (from a short candidate list found
     * locally). Returns item index -> row id. Text only, no search: cheap and fast.
     */
    suspend fun pickFromDb(items: List<AiParsing.MealItem>, candidates: List<List<DbFood>>): Map<Int, String> {
        if (candidates.all { it.isEmpty() }) return emptyMap()
        val list = items.mapIndexed { i, item ->
            val options = candidates[i].joinToString("\n") { "   - ${it.id}: ${it.name}" }.ifEmpty { "   (none)" }
            "${i + 1}. ${item.name}" + (item.searchName?.let { " [$it]" } ?: "") + " (${item.grams.toInt()} g)\n$options"
        }.joinToString("\n")
        val reply = gemini.text(PICK_PROMPT.replace("{ITEMS}", list))
        return AiParsing.picks(reply.text, candidates.mapIndexed { i, c -> i to c.map { it.id }.toSet() }.toMap())
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
        const val MAX_WEB_ITEMS = 5
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

        const val RESEARCH_PROMPT = """Find the printed nutrition table of one packaged food or drink sold in India.

Product clues:
{CLUES}

Search the web: the manufacturer's site first, then Indian retailers (BigBasket, Blinkit, Zepto, Swiggy Instamart,
JioMart, Amazon.in, Flipkart), then Open Food Facts. Search by product name; the barcode alone rarely finds pages.

Write a short plain-text report:
- The exact product, variant/flavour and pack size each page is about.
- The nutrition values each page shows, copied exactly with their units and basis (per 100 g, per 100 ml or per serving).
- Serving size and net quantity if shown.
- The web address of each page.
Copy numbers only from pages. Never estimate or use typical values for similar products.
If no page shows this product's nutrition values, reply with only: NOT_FOUND"""

        const val FOOD_RESEARCH_PROMPT = """Find reliable nutrition values per 100 g for this food, as eaten: {FOOD}

Search the web. Prefer, in this order: IFCT 2017 / NIN (India), INDB (Anuvaad, cooked Indian recipes),
USDA FoodData Central, UK CoFID, then established nutrition databases (Nutritionix, HealthifyMe, FatSecret India).
Pick the entry that matches the preparation: cooked vs raw, with oil or ghee for Indian cooked dishes.

Write a short plain-text report: the entry name used, energy (kcal), protein, carbohydrate, sugar, fat, saturated fat,
fibre and sodium per 100 g, exactly as the page shows them, and the web address of each page.
Copy numbers only from pages; never estimate. If no reliable page has this food, reply with only: NOT_FOUND"""

        const val RESTAURANT_RESEARCH_PROMPT = """Find the nutrition of this restaurant dish in India: {DISH}. Restaurant: {RESTAURANT}.

Search the web in this order:
1. The restaurant's own published nutrition (chains publish it; some Zomato or Swiggy listings show kcal per serving).
2. If the restaurant publishes nothing: the same dish from 2-3 comparable Indian restaurants' published values, or a
   nutrition database entry for the restaurant-style dish (Nutritionix, HealthifyMe, FatSecret India). Restaurant
   versions usually carry more oil, butter and cream than home cooking.
Write a short plain-text report: which source you used and why, the serving size in g, kcal, protein, carbohydrate,
sugar, fat, saturated fat and fibre per serving (and per 100 g if shown), and the web address of each page.
Copy numbers only from pages. If nothing reliable is found, reply with only: NOT_FOUND"""

        const val EXTRACT_PROMPT = """Below is a research report about the nutrition of one food, dish or product sold in India.
Turn it into JSON. Use ONLY numbers written in the report. Do not add, estimate or correct anything.

Rules:
- Values per 100 g (or per 100 ml for drinks). If the report has only per-serving values, convert with the serving size and say so in "note".
- Energy in kcal; if only kJ, divide by 4.184. Sodium in mg (salt g x 400 = sodium mg).
- sources_agreeing: how many different pages in the report show values within 10% of each other.
- match_confidence "uncertain" if the pages may be a different variant, flavour or pack than the clues.
- If the report has no nutrition values, set "found": false.

Report:
{REPORT}

Reply with ONLY this JSON object:
$SCHEMA"""


        const val PICK_PROMPT = """Match each food a person ate to a row of an Indian food composition databank.
For each item, choose the candidate that is the SAME dish: same main ingredient AND same way of cooking
(e.g. "Dal tadka" matches a plain toor/arhar or mixed dal, not "moong dal halwa"; "Phulka" matches "Chapati/Roti").
If no candidate is the same dish, use null. Never pick a row just because one word is shared.

{ITEMS}

Reply with ONLY this JSON: {"picks": [{"item": 1, "id": "ASC107"}, {"item": 2, "id": null}]}"""

    }
}
