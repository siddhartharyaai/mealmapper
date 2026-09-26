package app.mealmapper.data.ai

import app.mealmapper.domain.MealSlot
import app.mealmapper.domain.Nutrients
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/** One food the user mentioned, as the chat model understood it (corrected name, natural unit, grams). */
data class PlanItem(
    val name: String,
    /** What the user actually said or typed, e.g. "tru basic whey"; kept as an alias for next time. */
    val said: String?,
    /** Id of a food from the user's memory ("m12"), when the model recognised it. */
    val memoryId: String?,
    val quantity: Double,
    val unit: String,
    /** Grams (or ml) for [quantity] [unit]. */
    val grams: Double,
    /** "branded", "dish", "food", "restaurant" or "label". */
    val kind: String?,
    val searchName: String?,
    val restaurant: String?,
    /** The model's own estimate for [grams]: last resort only. */
    val estimate: Nutrients?,
    /** Values per 100 g copied from a nutrition label in a photo. */
    val labelPer100: Nutrients?,
    val note: String?,
)

/** What the chat model wants to do with a message. */
data class ChatPlan(
    /** "log" (items to log), "ask" (items proposed with a question), "answer" (just a reply), "save_meal". */
    val action: String,
    val reply: String,
    val question: String?,
    val day: LocalDate?,
    val slot: MealSlot?,
    /** True when the message corrects the open draft, so the draft is replaced instead of a new one added. */
    val replacesDraft: Boolean,
    /** "Save this as my usual breakfast": the name to save the draft or last log under. */
    val saveMealName: String?,
    val items: List<PlanItem>,
)

/** Pure parsing of the chat model's JSON. Unit-tested. */
object ChatParsing {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun plan(text: String, today: LocalDate): ChatPlan {
        val raw = AiParsing.lastJsonObject(text) ?: return ChatPlan("answer", AiParsing.stripThinking(text).take(600), null, null, null, false, null, emptyList())
        val o = json.parseToJsonElement(raw).jsonObject
        val items = (o["items"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::item) }
        return ChatPlan(
            action = o.str("action")?.lowercase() ?: if (items.isEmpty()) "answer" else "log",
            reply = o.str("reply").orEmpty(),
            question = o.str("question"),
            day = day(o.str("day"), today),
            slot = slot(o.str("meal")),
            replacesDraft = (o["replaces_draft"] as? JsonPrimitive)?.booleanOrNull ?: false,
            saveMealName = (o["save_meal"] as? JsonObject)?.str("name") ?: o.str("save_meal"),
            items = items,
        )
    }

    private fun item(o: JsonObject): PlanItem? {
        val name = o.str("name") ?: return null
        val quantity = o.num("quantity")?.takeIf { it > 0 } ?: 1.0
        val grams = o.num("grams")?.takeIf { it > 0 } ?: return null
        val estimate = o.num("kcal")?.let { kcal ->
            val f = 100.0 / grams
            Nutrients(
                energyKcal = kcal * f,
                proteinG = (o.num("protein_g") ?: 0.0) * f,
                carbsG = (o.num("carbs_g") ?: 0.0) * f,
                fatG = (o.num("fat_g") ?: 0.0) * f,
                saturatedFatG = o.num("saturated_fat_g")?.times(f),
                sugarG = o.num("sugar_g")?.times(f),
                fiberG = o.num("fiber_g")?.times(f),
                sodiumMg = o.num("sodium_mg")?.times(f),
            )
        }
        val label = (o["label_per_100"] as? JsonObject)?.let { l ->
            val k = l.num("energy_kcal") ?: return@let null
            Nutrients(
                energyKcal = k,
                proteinG = l.num("protein_g") ?: 0.0,
                carbsG = l.num("carbs_g") ?: 0.0,
                fatG = l.num("fat_g") ?: 0.0,
                saturatedFatG = l.num("saturated_fat_g"),
                sugarG = l.num("sugar_g"),
                fiberG = l.num("fiber_g"),
                sodiumMg = l.num("sodium_mg"),
            )
        }
        return PlanItem(
            name = name,
            said = o.str("said"),
            memoryId = o.str("memory_id"),
            quantity = quantity,
            unit = o.str("unit")?.lowercase() ?: "g",
            grams = grams,
            kind = o.str("kind")?.lowercase(),
            searchName = o.str("search_name"),
            restaurant = o.str("restaurant"),
            estimate = estimate,
            labelPer100 = label,
            note = o.str("note"),
        )
    }

    fun day(value: String?, today: LocalDate): LocalDate? = when (value?.lowercase()) {
        null, "", "today" -> null
        "yesterday" -> today.minusDays(1)
        else -> runCatching { LocalDate.parse(value) }.getOrNull()?.takeIf { !it.isAfter(today) }
    }

    fun slot(value: String?): MealSlot? = when (value?.lowercase()?.replace('-', '_')?.replace(' ', '_')) {
        "pre_breakfast", "prebreakfast", "early" -> MealSlot.PRE_BREAKFAST
        "breakfast" -> MealSlot.BREAKFAST
        "lunch" -> MealSlot.LUNCH
        "snack", "evening", "evening_snack" -> MealSlot.SNACK
        "dinner" -> MealSlot.DINNER
        else -> null
    }

    /** The plan item as a lookup item for [NutritionLookup.resolve]. */
    fun toMealItem(p: PlanItem): AiParsing.MealItem = AiParsing.MealItem(
        name = p.name,
        grams = p.grams,
        per100 = p.labelPer100 ?: p.estimate ?: Nutrients(0.0, 0.0, 0.0, 0.0),
        lowKcal = null,
        highKcal = null,
        assumption = p.note,
        published = p.labelPer100 != null,
        lookup = if (p.kind == "branded" && p.labelPer100 == null) p.searchName ?: p.name else null,
        kind = p.kind,
        searchName = p.searchName,
        restaurant = p.restaurant,
    )

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }
}
