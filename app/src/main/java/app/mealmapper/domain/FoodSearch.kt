package app.mealmapper.domain

/** One food from the offline databank (tools/fooddb): values per 100 g, or per 100 ml for drinks. */
data class DbFood(
    val id: String,
    val name: String,
    val aliases: List<String>,
    /** INDB (cooked Indian recipes), IFCT (raw foods, NIN 2017), CoFID (UK), ABV (alcohol by strength). */
    val source: String,
    val flags: List<String>,
    val basis: Basis,
    val per100: Nutrients,
    /** Typical piece or container weights, e.g. "1 roti" = 40 g. */
    val units: List<PortionOption>,
    /** Served in a katori (dal, sabzi, rice…). */
    val katori: Boolean,
) {
    val sourceLabel: String
        get() = when (source) {
            "INDB" -> "Indian Nutrient Databank (cooked recipe)"
            "IFCT" -> "IFCT 2017, NIN"
            "CoFID" -> "UK food tables (CoFID)"
            "ABV" -> "Calculated from alcohol strength"
            else -> source
        }

    /** Short notes on how far to trust the row, shown in amber on Review. */
    val warnings: List<String>
        get() = flags.mapNotNull {
            when (it) {
                "fry-corrected" -> null // a correction, not a doubt; shown in the source line
                "energy-mismatch" -> "The source's calories do not match its protein, carbs and fat."
                "check-basis" -> "This recipe's values may be for the dry ingredients; the cooked dish is likely lower."
                "implausible" -> "The source values look out of range."
                "fry-basis-unclear" -> "Frying oil in the source recipe may be counted in full."
                else -> null
            }
        }

    fun toProduct(katoriMl: Int): FoodProduct = FoodProduct(
        barcode = "",
        name = name.substringBefore(" (").trim(),
        brand = null,
        per100 = per100,
        basis = basis,
        servingSize = null,
        packSize = null,
        // Counted foods (roti, bread, idli) get count x size on Review instead of fixed piece chips.
        portions = units.filterNot { IndianPortions.modelFor(name) != null && !Regex("cup|glass|peg|bottle|can").containsMatchIn(it.label) } +
            if (katori) listOf(0.5, 1.0, 1.5, 2.0).map { k ->
                PortionOption("${mapOf(0.5 to "½", 1.0 to "1", 1.5 to "1½", 2.0 to "2")[k]} katori · ${(katoriMl * k).toInt()} g", katoriMl * k)
            } else emptyList(),
    )
}

/**
 * Search that understands how people in Mumbai type food: Hinglish spellings (daal, sabji, dahi),
 * Hindi and English names for the same thing, and word prefixes ("pan" finds paneer).
 */
object FoodSearch {
    private val SYNONYMS: List<Set<String>> = listOf(
        setOf("dal", "daal", "dhal", "lentil", "lentils"), setOf("sabzi", "sabji", "subji", "subzi", "bhaji", "vegetable"),
        setOf("chawal", "rice", "bhaat", "bhat"), setOf("dahi", "curd", "yogurt", "yoghurt"),
        setOf("chaas", "buttermilk", "mattha", "chhach"), setOf("roti", "chapati", "chapatti", "phulka", "fulka"),
        setOf("paratha", "parantha", "parotta"), setOf("aloo", "alu", "potato", "batata"), setOf("gobi", "gobhi", "cauliflower", "phoolgobhi"),
        setOf("palak", "spinach"), setOf("bhindi", "okra", "ladyfinger", "ladies"), setOf("baingan", "brinjal", "eggplant", "vangi", "vangi"),
        setOf("matar", "mutter", "peas", "pea"), setOf("paneer", "cottage"), setOf("anda", "ande", "egg", "eggs"), setOf("chai", "tea"),
        setOf("doodh", "milk"), setOf("chana", "channa", "chole", "chhole", "chickpea", "chickpeas", "garbanzo"),
        setOf("rajma", "rajmah", "kidney"), setOf("methi", "fenugreek"), setOf("moong", "mung", "green gram"),
        setOf("masoor", "red lentil"), setOf("toor", "tuvar", "tur", "arhar", "red gram", "pigeon"), setOf("urad", "black gram", "udad"),
        setOf("besan", "gram flour"), setOf("maida", "refined flour"), setOf("atta", "wheat flour"),
        setOf("suji", "sooji", "rava", "rawa", "semolina"), setOf("poha", "pohe", "flattened", "chivda", "chirwa"),
        setOf("murmura", "kurmura", "puffed rice"), setOf("kela", "banana"), setOf("aam", "mango"), setOf("seb", "apple"),
        setOf("santra", "orange"), setOf("nimbu", "lemon"), setOf("pyaz", "pyaaz", "kanda", "onion"), setOf("tamatar", "tomato"),
        setOf("lauki", "ghiya", "dudhi", "bottle gourd"), setOf("karela", "bitter gourd"), setOf("kaddu", "pumpkin"),
        setOf("gajar", "carrot"), setOf("mooli", "radish"), setOf("jeera", "zeera", "cumin"), setOf("makhana", "fox nut"),
        setOf("khichdi", "khichri", "khitchdi"), setOf("puri", "poori"), setOf("daru", "alcohol", "drink", "peg"),
        setOf("whisky", "whiskey", "rum", "vodka", "gin", "spirits"), setOf("beer", "lager", "kingfisher"), setOf("wine"),
        setOf("sheera", "halwa"), setOf("omelette", "omelet", "omlet"), setOf("biryani", "biriyani"), setOf("pulao", "pilaf", "pulav"),
        setOf("kadhi", "kadi"), setOf("pakora", "pakoda", "bhajiya", "bhajji"), setOf("ladoo", "laddu", "laddoo"),
        setOf("dosa", "dosai"), setOf("idli", "idly"), setOf("vada", "wada", "vadai"), setOf("upma", "uppittu"),
    )

    private val lookup: Map<String, Set<String>> = buildMap {
        SYNONYMS.forEach { group -> group.forEach { put(it, group) } }
    }

    fun normalize(s: String): String = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun expand(token: String): Set<String> = lookup[token] ?: setOf(token)

    /** Best matches first. Every query word (or one of its synonyms) must start a word in the name or aliases. */
    fun search(foods: List<DbFood>, query: String, limit: Int = 40): List<DbFood> {
        val tokens = normalize(query).split(' ').filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()
        return foods.mapNotNull { food -> score(food, tokens)?.let { food to it } }
            .sortedWith(compareByDescending<Pair<DbFood, Double>> { it.second }.thenBy { it.first.name.length })
            .take(limit)
            .map { it.first }
    }

    /** Null when some query word is missing. Higher is better. */
    fun score(food: DbFood, tokens: List<String>): Double? {
        val nameText = " " + normalize(food.name) + " "
        val aliasText = " " + normalize(food.aliases.joinToString(" ")) + " "
        var score = 0.0
        for (t in tokens) {
            val options = expand(t)
            score += when {
                options.any { nameText.contains(" $it ") } -> 3.0
                options.any { aliasText.contains(" $it ") } -> 2.5
                options.any { nameText.contains(" $it") } -> 2.0
                options.any { aliasText.contains(" $it") } -> 1.5
                else -> return null
            }
        }
        // The name starting with the query is a strong signal ("dal" -> "Dal makhani" before "Moong dal kheer").
        if (options0(tokens).any { nameText.startsWith(" $it") }) score += 1.0
        // People log what they eat: cooked recipes before raw ingredients; flagged rows last.
        score += when (food.source) {
            "INDB" -> 0.6
            "ABV" -> 0.5
            "CoFID" -> 0.3
            else -> 0.0
        }
        if (food.name.endsWith("(raw)")) score -= 0.8
        if (food.warnings.isNotEmpty()) score -= 0.5
        return score
    }

    private fun options0(tokens: List<String>) = expand(tokens.first())

    /**
     * Possible databank rows for a dish the AI named: rows matching every word first, then rows matching single
     * words. The AI then picks the one that is the same dish, or none (word overlap alone is not a match:
     * "dal tadka" must not become "moong dal with tadka").
     */
    fun candidates(foods: List<DbFood>, dish: String, n: Int = 8): List<DbFood> {
        val tokens = normalize(dish).split(' ').filter { it.length > 1 && it !in STOP }
        if (tokens.isEmpty()) return emptyList()
        val all = search(foods, tokens.joinToString(" "), limit = n)
        val single = tokens.flatMap { search(foods, it, limit = 4) }
        return (all + single).distinctBy { it.id }.take(n)
    }

    private val STOP = setOf("with", "and", "ki", "ka", "ke", "home", "style", "plain", "cooked", "indian", "the", "of", "a")
}
