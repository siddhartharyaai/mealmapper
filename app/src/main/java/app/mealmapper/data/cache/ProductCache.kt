package app.mealmapper.data.cache

import android.content.Context
import app.mealmapper.domain.Basis
import app.mealmapper.domain.FoodProduct
import app.mealmapper.domain.Nutrients
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Products the user has confirmed and saved, keyed by barcode. The next scan of the same pack is instant,
 * free and gives the same numbers. Only confirmed values are stored, never raw lookups.
 */
class ProductCache(context: Context) {
    private val file = File(context.filesDir, "products.json")
    private val json = Json { ignoreUnknownKeys = true }
    private var entries: MutableMap<String, CachedProduct> = load()

    @Synchronized
    fun get(barcode: String): FoodProduct? = entries[barcode]?.toProduct()

    @Synchronized
    fun put(product: FoodProduct) {
        if (product.barcode.isBlank()) return
        entries[product.barcode] = CachedProduct.from(product)
        runCatching { file.writeText(json.encodeToString(SERIALIZER, entries.toMap())) }
    }

    private fun load(): MutableMap<String, CachedProduct> = runCatching {
        json.decodeFromString(SERIALIZER, file.readText()).toMutableMap()
    }.getOrElse { mutableMapOf() }
}

private val SERIALIZER = MapSerializer(String.serializer(), CachedProduct.serializer())

@Serializable
private data class CachedProduct(
    val barcode: String,
    val name: String,
    val brand: String? = null,
    val basis: String = "g",
    val servingSize: Double? = null,
    val packSize: Double? = null,
    val kcal: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    val saturatedFat: Double? = null,
    val sugar: Double? = null,
    val fiber: Double? = null,
    val sodiumMg: Double? = null,
) {
    fun toProduct() = FoodProduct(
        barcode, name, brand,
        Nutrients(kcal, protein, carbs, fat, saturatedFat, sugar, fiber, sodiumMg),
        if (basis == "ml") Basis.MILLILITRES else Basis.GRAMS, servingSize, packSize,
    )

    companion object {
        fun from(p: FoodProduct) = with(p.per100) {
            CachedProduct(
                p.barcode, p.name, p.brand, p.basis.unit, p.servingSize, p.packSize,
                energyKcal, proteinG, carbsG, fatG, saturatedFatG, sugarG, fiberG, sodiumMg,
            )
        }
    }
}
