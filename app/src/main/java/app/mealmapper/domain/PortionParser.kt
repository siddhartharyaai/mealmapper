package app.mealmapper.domain

/** An amount understood from the user's "What and how much" note, with a short explanation for Review. */
data class ParsedPortion(val amount: Double, val explanation: String)

/**
 * Turns a free-text note into an amount for a packaged product, where the meaning is unambiguous:
 * "125 g", "0.2 kg", "200 ml", "1 l", "half pack", "1/2 packet", "2 servings", "1 glass", "2 cups".
 * Returns null when it cannot tell (e.g. "2 biscuits": the piece weight is unknown). The note is kept
 * on the entry either way, so nothing the user typed is lost.
 */
fun parsePortion(note: String, product: FoodProduct): ParsedPortion? {
    val text = note.lowercase().replace(',', '.')

    // 1. Explicit weight or volume always wins.
    MASS.find(text)?.let { m ->
        val n = number(m.groupValues[1]) ?: return@let
        val grams = if (m.groupValues[2].startsWith("k")) n * 1000 else n
        return ParsedPortion(grams, "${grams.clean()} g")
    }
    VOLUME.find(text)?.let { m ->
        val n = number(m.groupValues[1]) ?: return@let
        val ml = if (m.groupValues[2].startsWith("l")) n * 1000 else n
        return ParsedPortion(ml, "${ml.clean()} ml")
    }

    // 2. Fractions or counts of the pack.
    PACK.find(text)?.let { m ->
        val pack = product.packSize ?: return@let
        val count = number(m.groupValues[1]) ?: 1.0
        val amount = pack * count
        return ParsedPortion(amount, "${amount.clean()} ${product.basis.unit} (${m.value.trim()})")
    }

    // 3. Servings, as printed on the label.
    SERVING.find(text)?.let { m ->
        val serving = product.servingSize ?: return@let
        val count = number(m.groupValues[1]) ?: 1.0
        val amount = serving * count
        return ParsedPortion(amount, "${amount.clean()} ${product.basis.unit} (${m.value.trim()})")
    }

    // 4. Household drink measures, only for drinks sold by volume.
    if (product.basis == Basis.MILLILITRES) {
        DRINK.find(text)?.let { m ->
            val count = number(m.groupValues[1]) ?: 1.0
            val each = if (m.groupValues[2].startsWith("glass")) GLASS_ML else CUP_ML
            val amount = each * count
            return ParsedPortion(amount, "${amount.clean()} ml (${m.value.trim()})")
        }
    }
    return null
}

private const val NUM = """(\d+(?:\.\d+)?|\d+/\d+|half|quarter|one|two|three|four|a|an)"""
private const val CUP_ML = 150.0
private const val GLASS_ML = 250.0

private val MASS = Regex("""(\d+(?:\.\d+)?|\d+/\d+)\s*(kg|kgs|g|gm|gms|gram|grams)\b""")
private val VOLUME = Regex("""(\d+(?:\.\d+)?|\d+/\d+)\s*(ml|millilitres?|milliliters?|l|litres?|liters?|ltr)\b""")
private val PACK = Regex("""(?:\b$NUM\s+)?(?:(?:of\s+)?(?:a|an|the)\s+)?(?:full\s+|whole\s+)?(?:pack|packet|pkt|packs|packets)\b""")
private val SERVING = Regex("""(?:\b$NUM\s+)?(?:serving|servings|serve|serves)\b""")
private val DRINK = Regex("""(?:\b$NUM\s+)?(cups?|glass(?:es)?)\b""")

private fun number(token: String): Double? = when (token.trim()) {
    "" -> null
    "half" -> 0.5
    "quarter" -> 0.25
    "one", "a", "an" -> 1.0
    "two" -> 2.0
    "three" -> 3.0
    "four" -> 4.0
    else -> if ('/' in token) {
        val (a, b) = token.split('/')
        val den = b.toDoubleOrNull()?.takeIf { it != 0.0 }
        den?.let { a.toDouble() / it }
    } else {
        token.toDoubleOrNull()
    }
}?.takeIf { it > 0 }

private fun Double.clean(): String = if (this % 1.0 == 0.0) toInt().toString() else "%.1f".format(this)
