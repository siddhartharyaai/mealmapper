package app.mealmapper.data.off

import app.mealmapper.domain.Basis
import app.mealmapper.domain.FoodProduct
import app.mealmapper.domain.Nutrients
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed interface ParseResult {
    data class Product(val product: FoodProduct) : ParseResult
    /** The product exists but has no usable nutrition table. Common for Indian products. */
    data class NoNutrition(val name: String?) : ParseResult
    data object NotFound : ParseResult
}

/**
 * Turns an Open Food Facts v2 product response into a [FoodProduct].
 * OFF data is crowd-sourced: numbers can come as strings, fields can be missing,
 * and some products only have per-serving values. This parser accepts all of that.
 */
object OpenFoodFactsParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(barcode: String, body: String): ParseResult {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return ParseResult.NotFound
        if (root.num("status")?.toInt() != 1) return ParseResult.NotFound
        val p = root["product"] as? JsonObject ?: return ParseResult.NotFound

        val name = (p.str("product_name_en") ?: p.str("product_name"))?.trim()?.takeIf { it.isNotEmpty() }
        val brand = p.str("brands")?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        val servingSize = p.num("serving_quantity")?.takeIf { it > 0 }
        val nutriments = p["nutriments"] as? JsonObject
            ?: return ParseResult.NoNutrition(name)

        val per100 = per100(nutriments, servingSize) ?: return ParseResult.NoNutrition(name)

        return ParseResult.Product(
            FoodProduct(
                barcode = barcode,
                name = name ?: "Unnamed product",
                brand = brand,
                per100 = per100,
                basis = basis(p),
                servingSize = servingSize,
                packSize = p.num("product_quantity")?.takeIf { it > 0 },
            ),
        )
    }

    private fun per100(n: JsonObject, servingSize: Double?): Nutrients? {
        // Prefer *_100g. If only *_serving exists, convert using the serving size.
        fun value(key: String): Double? =
            n.num("${key}_100g")
                ?: servingSize?.let { size -> n.num("${key}_serving")?.times(100.0 / size) }

        val protein = value("proteins")
        val carbs = value("carbohydrates")
        val fat = value("fat")
        val kcal = value("energy-kcal") ?: value("energy")?.div(KJ_PER_KCAL)

        // A label without energy and macros is not usable. Partial macros are treated as 0.
        if (kcal == null && protein == null && carbs == null && fat == null) return null
        val p = protein ?: 0.0
        val c = carbs ?: 0.0
        val f = fat ?: 0.0

        val sodiumMg = value("sodium")?.times(1000) ?: value("salt")?.div(SALT_PER_SODIUM)?.times(1000)

        return Nutrients(
            energyKcal = kcal ?: (4 * p + 4 * c + 9 * f),
            proteinG = p,
            carbsG = c,
            fatG = f,
            saturatedFatG = value("saturated-fat"),
            sugarG = value("sugars"),
            fiberG = value("fiber"),
            sodiumMg = sodiumMg,
        )
    }

    private fun basis(p: JsonObject): Basis {
        val unit = p.str("product_quantity_unit")?.lowercase()
        if (unit == "ml") return Basis.MILLILITRES
        if (unit == "g") return Basis.GRAMS
        val quantity = p.str("quantity")?.lowercase()?.replace(" ", "") ?: return Basis.GRAMS
        return if (Regex("""\d(ml|l|ltr|litre|liter)$""").containsMatchIn(quantity)) Basis.MILLILITRES else Basis.GRAMS
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.num(key: String): Double? = this[key]?.asDouble()

    private fun JsonElement.asDouble(): Double? {
        val prim = this as? JsonPrimitive ?: return null
        return prim.content.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
    }

    private const val KJ_PER_KCAL = 4.184
    private const val SALT_PER_SODIUM = 2.5
}
