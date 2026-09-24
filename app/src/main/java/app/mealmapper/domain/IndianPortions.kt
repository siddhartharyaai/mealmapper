package app.mealmapper.domain

import kotlin.math.roundToInt

/** One size choice for a counted food, e.g. "18 cm" roti = 35 g. */
data class PieceSize(val label: String, val grams: Double)

/**
 * How Indians count food: "3 rotis, medium", "2 slices of bread", "1 katori dal". The app turns count x size
 * into grams. Weights are typical cooked weights (not measured by Meal Mapper); the gram field stays editable.
 *
 * Flatbreads scale with area: weight = base weight x (diameter / base diameter)^2.
 * For reference: a steel dinner plate is about 28 cm across, a quarter plate about 18 cm, a phone about 15 cm long.
 */
data class PieceModel(val noun: String, val plural: String, val sizes: List<PieceSize>, val defaultSize: Int) {
    fun grams(count: Double, size: Int): Double = count * sizes[size.coerceIn(sizes.indices)].grams

    fun describe(count: Double, size: Int): String {
        val n = if (count % 1.0 == 0.0) count.toInt().toString() else "%.1f".format(count)
        return "$n ${if (count == 1.0) noun else plural} · ${sizes[size.coerceIn(sizes.indices)].label}"
    }
}

object IndianPortions {
    private fun flat(noun: String, plural: String, baseCm: Int, baseGrams: Double, cms: List<Int>, default: Int) =
        PieceModel(noun, plural, cms.map { PieceSize("$it cm", (baseGrams * (it.toDouble() / baseCm).let { r -> r * r }).roundToInt().toDouble()) }, default)

    private fun sized(noun: String, plural: String, vararg sizes: Pair<String, Double>, default: Int = 1) =
        PieceModel(noun, plural, sizes.map { PieceSize(it.first, it.second) }, default)

    private val MODELS: List<Pair<Regex, PieceModel>> = listOf(
        Regex("phulka|fulka") to flat("phulka", "phulkas", 18, 30.0, listOf(15, 18, 20, 23), 1),
        Regex("roti|chapati|chapatti") to flat("roti", "rotis", 18, 35.0, listOf(15, 18, 20, 23), 1),
        Regex("paratha|parantha") to flat("paratha", "parathas", 18, 70.0, listOf(15, 18, 20, 23), 1),
        Regex("thepla") to flat("thepla", "theplas", 18, 40.0, listOf(15, 18, 20), 1),
        Regex("bhakri|bhakhri|jowar roti|bajra roti|makki") to flat("bhakri", "bhakris", 18, 70.0, listOf(15, 18, 20), 1),
        Regex("puran ?poli") to flat("puran poli", "puran polis", 18, 70.0, listOf(15, 18, 20), 1),
        Regex("poori|puri\\b") to flat("poori", "pooris", 12, 22.0, listOf(10, 12, 15), 1),
        Regex("dosa|uttapam|pesarattu") to flat("dosa", "dosas", 25, 100.0, listOf(20, 25, 30), 1),
        Regex("cheela|chilla") to flat("cheela", "cheelas", 18, 60.0, listOf(15, 18, 20), 1),
        Regex("\\bnaan|kulcha") to sized("naan", "naans", "Half" to 50.0, "Regular" to 90.0, "Large" to 120.0),
        Regex("\\bpav\\b|\\bpao\\b|bun\\b") to sized("pav", "pavs", "Small" to 30.0, "Regular" to 40.0, "Large" to 55.0),
        Regex("bread|toast") to sized(
            "slice", "slices",
            "Indian sandwich loaf (small)" to 22.0, "Indian large / brown loaf" to 28.0, "Western bakery loaf" to 38.0, default = 0,
        ),
        Regex("idli|idly") to sized("idli", "idlis", "Small" to 30.0, "Regular" to 40.0, "Large" to 55.0),
        Regex("vada|wada|vadai") to sized("vada", "vadas", "Small" to 35.0, "Regular" to 50.0, "Large" to 70.0),
        Regex("samosa") to sized("samosa", "samosas", "Cocktail" to 25.0, "Regular" to 60.0, "Large" to 100.0),
        Regex("kachori") to sized("kachori", "kachoris", "Small" to 30.0, "Regular" to 50.0, "Large" to 80.0),
        Regex("dhokla|khaman") to sized("piece", "pieces", "Small" to 20.0, "Regular" to 30.0, "Large" to 45.0),
        Regex("pakora|pakoda|bhajiya|bhaji\\b") to sized("piece", "pieces", "Small" to 12.0, "Regular" to 20.0, "Large" to 35.0),
        Regex("ladoo|laddu|laddoo") to sized("ladoo", "ladoos", "Small" to 25.0, "Regular" to 35.0, "Large" to 50.0),
        Regex("gulab jamun|rasgulla|rasagulla") to sized("piece", "pieces", "Small" to 30.0, "Regular" to 40.0, "Large" to 60.0),
        Regex("jalebi") to sized("piece", "pieces", "Small" to 20.0, "Regular" to 30.0, "Large" to 45.0),
        Regex("barfi|burfi|peda|kaju katli") to sized("piece", "pieces", "Small" to 15.0, "Regular" to 25.0, "Large" to 35.0),
        Regex("modak") to sized("modak", "modaks", "Small" to 30.0, "Regular" to 40.0, "Large" to 55.0),
        Regex("cutlet|tikki|patties|patty") to sized("piece", "pieces", "Small" to 35.0, "Regular" to 50.0, "Large" to 75.0),
        Regex("biscuit|cookie") to sized("biscuit", "biscuits", "Small (Marie, Parle-G)" to 7.0, "Regular" to 12.0, "Large cookie" to 25.0),
        Regex("\\begg|anda|omelette|omelet|omlet") to sized("egg", "eggs", "Small" to 40.0, "Medium" to 50.0, "Large" to 60.0),
        Regex("banana|kela") to sized("banana", "bananas", "Small (elaichi)" to 50.0, "Medium" to 90.0, "Large" to 120.0),
        Regex("sandwich") to sized("sandwich", "sandwiches", "Half" to 60.0, "Regular" to 120.0, "Grilled large" to 180.0),
    )

    /** The count model for a food name, or null when the food is not counted in pieces. */
    fun modelFor(name: String): PieceModel? {
        val n = name.lowercase()
        val model = MODELS.firstOrNull { it.first.containsMatchIn(n) }?.second ?: return null
        // "Egg curry", "banana shake", "eggplant rice": the named item is an ingredient, not the thing counted.
        if (model.noun in setOf("egg", "banana", "biscuit") && NOT_COUNTED.containsMatchIn(n)) return null
        return model
    }

    private val NOT_COUNTED = Regex("eggless|eggplant|egg nog|curry|cake|shake|halwa|rice|bhurji|pudding|custard|kofta|sauce|chips|pakora")
}
