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
 * AI jobs. Packaged food is read, never estimated; meals are estimates and always shown as such.
 * - web: find the product's printed nutrition table on real web pages (Gemini with Google Search).
 * - meal: estimate a plate of food from a photo and/or the eater's words.
 * - identify: read brand, variant and pack size from a photo of the pack front (vision model).
 * - label: copy the nutrition table from a photo of the pack (vision model).
 */
class NutritionLookup(private val gemini: GeminiClient) {

    suspend fun web(barcode: String?, knownName: String?, note: String, frontPhoto: ByteArray?): LookupOutcome {
        // The search tool reads no images, so a pack-front photo is first turned into an exact product name.
        val seen = frontPhoto?.let { identify(it) }
        val name = seen ?: knownName
        if (frontPhoto != null && seen == null && knownName == null) {
            return LookupOutcome.NotFound("Could not read the brand and product name from the photo. Take it closer, or photograph the label.")
        }
        if (name == null && barcode == null) {
            return LookupOutcome.NotFound("No product name or barcode to search for. Photograph the pack front or the label.")
        }
        val clues = buildList {
            seen?.let { add("Product as printed on the pack front: $it") }
            if (seen == null) knownName?.let { add("Product name from Open Food Facts: $it") }
            barcode?.let { add("Barcode (EAN): $it") }
            if (note.isNotBlank()) add("User note: $note")
        }.joinToString("\n")
        val what = name ?: "barcode $barcode"

        // Step 1: search and report in plain prose. A JSON-only answer tends to come back without citations,
        // which is why 0.6.0 said "no pages" even when the search had worked.
        val research = gemini.webSearch(RESEARCH_PROMPT.replace("{CLUES}", clues))
        if (!research.searched && research.sites.isEmpty()) {
            return LookupOutcome.NotFound(
                "Google Search did not run for $what. In Google AI Studio check that billing is on for this key's project.",
            )
        }
        if (research.text.contains("NOT_FOUND") && research.text.length < 400) {
            return LookupOutcome.NotFound("Searched the web for $what, but no page shows its nutrition table.")
        }
        // Cited pages first; if Google did not attach citations, the pages the answer names.
        val sites = research.sites.ifEmpty { AiParsing.sitesInText(research.text) }

        // Step 2: turn the report into the app's JSON. Text only, no search: it can only copy what step 1 found.
        val ai = AiParsing.nutrition(gemini.text(EXTRACT_PROMPT.replace("{REPORT}", research.text)).text)
        if (!ai.found || ai.per100 == null) {
            return LookupOutcome.NotFound(ai.note ?: "Searched the web for $what, but no page shows its nutrition table.")
        }
        if (ai.matchConfidence == "uncertain") {
            return LookupOutcome.NotFound("Found pages for $what, but not for this exact variant. Photograph the label instead.")
        }
        return LookupOutcome.Found(
            product = ai.toProduct(barcode, name),
            source = ProductSource.Web(sites, ai.sourcesAgreeing, cited = research.sites.isNotEmpty()),
            problems = AiParsing.problems(ai),
        )
    }

    /** Estimates a plate of food from a photo and/or the user's words. Always labelled as an estimate. */
    suspend fun meal(
        photos: List<ByteArray>,
        note: String,
        restaurant: Boolean,
        restaurantName: String?,
        kitchen: Kitchen = Kitchen(),
    ): List<AiParsing.MealItem> {
        val place = when {
            restaurantName != null -> "$RESTAURANT Restaurant: $restaurantName."
            restaurant -> RESTAURANT
            else -> HOME + kitchen.oilGramsPerPersonDay?.let {
                " This household uses about ${Math.round(it)} g of oil and ghee per person per day in total, across all meals."
            }.orEmpty()
        }
        val prompt = MEAL_PROMPT.replace("{PLACE}", place).replace("{NOTE}", note.ifBlank { "none" })
            .replace("katori 150 ml", "katori ${kitchen.katoriMl} ml")
            .replace("1 katori = 150 g", "1 katori = ${kitchen.katoriMl} g")
        val items = AiParsing.meal(gemini.vision(prompt, photos).text)
        if (items.isEmpty()) throw AiException("No food recognised. Try a clearer photo, or type what you ate.")
        return if (restaurantName != null) published(restaurantName, items) else items
    }

    /**
     * Branded or unusual products in a meal (Qbit Green, a protein powder) are looked up on the web like a
     * barcode miss: Google Search for the product's own label values. Found: the label values replace the
     * guess (grams stay as the eater said). Not found: the guess stays, clearly marked. Home food is untouched.
     */
    suspend fun lookUpProducts(items: List<AiParsing.MealItem>): List<AiParsing.MealItem> = items.map { item ->
        val query = item.lookup ?: return@map item
        val outcome = runCatching { web(barcode = null, knownName = query, note = "", frontPhoto = null) }.getOrNull()
        if (outcome is LookupOutcome.Found) {
            val p = outcome.product
            val serving = p.servingSize
            val sites = (outcome.source as? ProductSource.Web)?.sites.orEmpty().take(2).joinToString()
            item.copy(
                per100 = p.per100,
                grams = if (item.grams > 0) item.grams else serving ?: 100.0,
                lowKcal = null,
                highKcal = null,
                assumption = "Found online: ${p.name}" + (if (sites.isNotEmpty()) " ($sites)" else "") +
                    (serving?.let { " · 1 serving = ${Math.round(it)} ${p.basis.unit}" } ?: ""),
                published = true,
                lookup = null,
            )
        } else {
            item.copy(assumption = "Not found online, so this is the AI's guess. Check the pack. " + item.assumption.orEmpty())
        }
    }

    /** Per-100 g values for a generic food or dish from reliable web sources (IFCT, INDB, USDA, CoFID…). */
    suspend fun webFood(name: String): LookupOutcome {
        val research = gemini.webSearch(FOOD_RESEARCH_PROMPT.replace("{FOOD}", name))
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
     *  2. Everything else: the food databank (INDB cooked recipes, IFCT 2017, CoFID); Gemini picks the same dish
     *     from local candidates and may say "none".
     *  3. Not in the databank: per-100 g values from reliable web sources (IFCT, INDB, USDA, CoFID).
     *  4. Still nothing: the AI's estimate, shown as such.
     * Restaurant food skips 2-3: home recipes and generic values would understate restaurant oil and portions.
     */
    suspend fun resolve(
        estimated: List<AiParsing.MealItem>,
        foods: List<DbFood>,
        restaurant: Boolean,
        progress: (String) -> Unit,
    ): Resolved {
        var items = estimated
        val products = items.filter { it.lookup != null && !it.published }
        if (products.isNotEmpty()) {
            progress("Searching the web for ${products.joinToString { it.name }}…")
            items = lookUpProducts(items)
        }
        if (restaurant) return Resolved(items, emptyMap())

        // Step 2: databank, for everything that is not a named product and has no label values yet.
        val open = { i: Int -> !items[i].published && items[i].lookup == null }
        progress("Matching to the food databank…")
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

        // Step 3: the web, for what the databank does not have. At most 5 items, to keep a meal under a minute.
        val missing = items.indices.filter { open(it) && it !in matched }.take(MAX_WEB_ITEMS)
        if (missing.isNotEmpty()) {
            progress("Not in the databank: searching the web for ${missing.joinToString { items[it].name }}…")
            items = items.mapIndexed { i, item ->
                if (i !in missing) return@mapIndexed item
                val found = runCatching { webFood(item.searchName ?: item.name) }.getOrNull() as? LookupOutcome.Found
                    ?: return@mapIndexed item
                val sites = (found.source as? ProductSource.Web)?.sites.orEmpty().take(2).joinToString()
                item.copy(
                    per100 = found.product.per100,
                    lowKcal = null,
                    highKcal = null,
                    assumption = "Found online: ${item.searchName ?: item.name}" + (if (sites.isNotEmpty()) " ($sites)" else "") +
                        if (found.problems.isNotEmpty()) " · check: ${found.problems.first()}" else "",
                    published = true,
                )
            }
        }
        return Resolved(items, matched)
    }

    /**
     * For each home-food item, asks the model which databank row is the same dish (from a short candidate list
     * found locally). Returns item index -> row id. Text only, no search: cheap and fast.
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

    /**
     * Chains (McDonald's India, Domino's, Subway, Starbucks…) publish nutrition per item. Where Google Search
     * finds a published value for a dish, it replaces the estimate. A failed search keeps the estimates.
     */
    private suspend fun published(restaurant: String, items: List<AiParsing.MealItem>): List<AiParsing.MealItem> {
        val dishes = items.joinToString("\n") { "- ${it.name} (about ${it.grams.toInt()} g)" }
        val research = runCatching {
            gemini.webSearch(PUBLISHED_PROMPT.replace("{RESTAURANT}", restaurant).replace("{DISHES}", dishes))
        }.getOrNull() ?: return items
        if (research.text.contains("NOT_FOUND") && research.text.length < 400) return items
        val sites = research.sites.ifEmpty { AiParsing.sitesInText(research.text) }
        if (sites.isEmpty()) return items
        val found = runCatching {
            AiParsing.meal(gemini.text(PUBLISHED_EXTRACT.replace("{REPORT}", research.text).replace("{DISHES}", dishes)).text)
        }.getOrElse { return items }
        return items.map { item ->
            val match = found.firstOrNull { it.name.equals(item.name, ignoreCase = true) } ?: return@map item
            match.copy(
                name = item.name,
                lowKcal = null,
                highKcal = null,
                assumption = "Published by $restaurant (${sites.first()}): ${match.grams.toInt()} g serving",
                published = true,
            )
        }
    }

    /** Photo of a menu: the 3 best dishes for the calories left today, eggetarian. Estimates, with reasons. */
    suspend fun menu(photos: List<ByteArray>, note: String, kcalLeft: Int?, restaurantName: String?): List<AiParsing.MealItem> {
        val prompt = MENU_PROMPT
            .replace("{LEFT}", kcalLeft?.let { "$it kcal left today" } ?: "no daily cap set; prefer high protein and moderate calories")
            .replace("{RESTAURANT}", restaurantName ?: "unknown")
            .replace("{NOTE}", note.ifBlank { "none" })
        val picks = AiParsing.meal(gemini.vision(prompt, photos).text)
        if (picks.isEmpty()) throw AiException("Could not read dishes from this photo. Take the menu closer, in good light.")
        return picks
    }

    /** "Britannia Nutri Choice Digestive, 125 g" from a pack-front photo, or null if unreadable. */
    private suspend fun identify(photo: ByteArray): String? {
        val text = gemini.vision(IDENTIFY_PROMPT, listOf(photo)).text.lines().firstOrNull { it.isNotBlank() }?.trim()
        return text?.takeUnless { it.equals("UNKNOWN", ignoreCase = true) || it.length < 3 }?.take(120)
    }

    suspend fun label(labelPhoto: ByteArray, barcode: String?, knownName: String?, note: String): LookupOutcome {
        val prompt = LABEL_PROMPT
            .replace("{NAME}", knownName ?: "unknown")
            .replace("{NOTE}", note.ifBlank { "none" })
        val ai = AiParsing.nutrition(gemini.vision(prompt, listOf(labelPhoto)).text)
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

        const val EXTRACT_PROMPT = """Below is a research report about one packaged food product sold in India.
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

        const val HOME = "Home-cooked food from a Mumbai household kitchen, made by a home cook. Moderate oil; phulka/roti usually without ghee unless visible."
        const val RESTAURANT = "Restaurant or takeaway food in Mumbai. Restaurants use more oil, butter, cream and larger portions than home cooking."

        const val PICK_PROMPT = """Match each food a person ate to a row of an Indian food composition databank.
For each item, choose the candidate that is the SAME dish: same main ingredient AND same way of cooking
(e.g. "Dal tadka" matches a plain toor/arhar or mixed dal, not "moong dal halwa"; "Phulka" matches "Chapati/Roti").
If no candidate is the same dish, use null. Never pick a row just because one word is shared.

{ITEMS}

Reply with ONLY this JSON: {"picks": [{"item": 1, "id": "ASC107"}, {"item": 2, "id": null}]}"""

        const val PUBLISHED_PROMPT = """Find the nutrition values that the restaurant "{RESTAURANT}" (India) publishes for these dishes:
{DISHES}

Search the web: the restaurant's own India website or app nutrition pages first (chains such as McDonald's India,
Domino's India, KFC India, Subway India, Starbucks India, Burger King India publish them), then reliable pages that copy them.
Write a short plain-text report: for each dish found, the exact item name, serving size in g, kcal, protein, carbs and fat,
and the web address. Copy numbers only from pages; never estimate.
If the restaurant publishes nothing for any of these dishes, reply with only: NOT_FOUND"""

        const val PUBLISHED_EXTRACT = """Below is a research report about published restaurant nutrition values.
Dishes we need:
{DISHES}

Use ONLY numbers written in the report. For each dish above that the report gives values for, output one item,
with "name" exactly as written in the dish list above, "grams" = the published serving size in g, and the published
values for that serving. Leave out dishes the report has no values for.

Report:
{REPORT}

Reply with ONLY this JSON:
{"items": [{"name": "...", "grams": n, "kcal": n, "protein_g": n, "carbs_g": n, "fat_g": n}]}"""

        const val MENU_PROMPT = """The photos show a restaurant menu in India (one or more pages). Restaurant: {RESTAURANT}.
The eater: adult in Mumbai, EGGETARIAN (vegetarian plus eggs; no meat, chicken, fish or seafood). {LEFT}.
Eater's note: {NOTE}

Pick the 3 best dishes ON THIS MENU for the eater right now:
- Only eggetarian dishes that are printed on the menu.
- Fit within the calories left (a normal restaurant portion), favour protein and less oil, butter, cream and deep frying.
- Estimate each dish's restaurant portion with typical Indian restaurant values (IFCT/NIN-based), including cooking fat.
- kcal must agree with macros (protein x4 + carbs x4 + fat x9, within 10%). Give a realistic kcal_low and kcal_high.
- "assumption": one short line on why it is a good pick and what to ask for (e.g. "paneer tikka: 25 g protein; ask for less butter").

Reply with ONLY this JSON, best pick first:
{"items": [{"name": "dish as printed", "grams": n, "kcal": n, "protein_g": n, "carbs_g": n, "fat_g": n,
  "fiber_g": n, "kcal_low": n, "kcal_high": n, "assumption": "..."}]}
If the photo is not a readable menu, reply {"items": []}"""

        const val MEAL_PROMPT = """Estimate the nutrition of the food in this meal. The eater is an adult in Mumbai, India, eggetarian (no meat or fish).
Where it is from: {PLACE}
What the eater says (this is the truth; it overrides what you see): {NOTE}

Rules:
1. List each separate food: e.g. dal, rice, phulka, sabzi, raita, salad, pickle, sweet, drink.
   There may be several photos of ONE meal (e.g. one per dish). List each food once, even if it appears in two photos.
   If there is no photo, use only the eater's words.
2. Portions: use the eater's counts and sizes exactly. Otherwise estimate from the photo using Indian references:
   katori 150 ml, steel plate 28 cm, phulka 30-35 g, chapati 40 g, paratha 70-90 g, cooked rice 1 katori = 150 g,
   dal 1 katori = 150 g, chai cup 150 ml, glass 250 ml.
3. Values from standard Indian food composition data (IFCT 2017 / NIN) for the cooked dish, including its cooking oil or ghee.
   Visible extra ghee, butter or oil on top is a separate item.
4. kcal must agree with macros: protein x4 + carbs x4 + fat x9, within 10%.
5. kcal_low and kcal_high: a realistic range for this item given what cannot be seen (oil, hidden portions).
6. assumption: one short line with what you assumed (e.g. "1 tsp oil in tadka", "2 phulkas under the dal").
7. Do not invent items you cannot see or that the eater did not mention.
8. ROUTING. The app looks every item up in a food databank (INDB cooked Indian recipes, IFCT 2017) or on the web; your
   own numbers are only the last fallback. So for each item also give:
   - "kind": "branded" for any named product (supplement, protein or greens powder, health drink, packaged or
     restaurant-chain item, e.g. "Qbit Green", "Yakult", "Horlicks", "Amul Kool"); "dish" for a cooked or mixed
     preparation (dal tadka, bhindi sabzi, poha, sandwich); "food" for a single plain food (walnut, banana, milk,
     boiled egg, phulka, curd).
   - "search_name": the plain, specific name to look up. Dish or food: the standard English name with the Indian name
     in brackets and the cooking state, e.g. "Toor dal tadka (arhar dal)", "Walnut, raw", "Phulka (whole wheat roti)",
     "Okra stir-fry (bhindi sabzi)". Branded: brand + product + "nutrition facts".
   - Keep a branded product's exact name as "name"; never replace it with a generic food (not "green vegetable juice").
   - For a powder mixed in water, "grams" is the powder only (scoops or sachets as the eater says; water has 0 kcal).

Reply with ONLY this JSON:
{"items": [{"name": "Dal tadka", "grams": 150, "kcal": n, "protein_g": n, "carbs_g": n, "fat_g": n,
  "saturated_fat_g": n, "sugar_g": n, "fiber_g": n, "sodium_mg": n, "kcal_low": n, "kcal_high": n, "assumption": "...",
  "kind": "dish", "search_name": "Toor dal tadka (arhar dal)"}]}
Use grams for every item, including drinks (1 ml = 1 g). If you see no food, reply {"items": []}"""

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
