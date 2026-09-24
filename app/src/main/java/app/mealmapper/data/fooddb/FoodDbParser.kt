package app.mealmapper.data.fooddb

import app.mealmapper.domain.Basis
import app.mealmapper.domain.DbFood
import app.mealmapper.domain.Nutrients
import app.mealmapper.domain.PortionOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/** Reads assets/foods.json, written by tools/fooddb/build.py. Pure: unit-tested without Android. */
object FoodDbParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): List<DbFood> {
        val foods = json.parseToJsonElement(text).jsonObject["foods"] as? JsonArray ?: return emptyList()
        return foods.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            val name = o.str("n") ?: return@mapNotNull null
            DbFood(
                id = id,
                name = name,
                aliases = o.strings("a"),
                source = o.str("s").orEmpty(),
                flags = o.strings("x"),
                basis = if (o.str("b") == "ml") Basis.MILLILITRES else Basis.GRAMS,
                per100 = Nutrients(
                    energyKcal = o.num("k") ?: return@mapNotNull null,
                    proteinG = o.num("p") ?: 0.0,
                    carbsG = o.num("c") ?: 0.0,
                    fatG = o.num("f") ?: 0.0,
                    saturatedFatG = o.num("sf"),
                    sugarG = o.num("su"),
                    fiberG = o.num("fi"),
                    sodiumMg = o.num("na"),
                ),
                units = (o["u"] as? JsonArray).orEmpty().mapNotNull { u ->
                    val pair = u as? JsonArray ?: return@mapNotNull null
                    val label = (pair.getOrNull(0) as? JsonPrimitive)?.content ?: return@mapNotNull null
                    val grams = (pair.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                    PortionOption(label, grams)
                },
                katori = (o["kt"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        }
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.num(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.strings(key: String) =
        (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
}
