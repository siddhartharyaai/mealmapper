package app.mealmapper.data.log

import android.content.Context
import app.mealmapper.domain.MealSlot
import app.mealmapper.domain.NutritionEntry
import app.mealmapper.domain.Nutrients
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * What Meal Mapper itself logged, kept on the phone so History can edit amounts (it needs the per-100 values,
 * which Health Connect does not store) and Home can offer Recent and Favourites. Health Connect stays the
 * record Google Health reads; every change here is written there too, by the same client id.
 */
class LogStore(context: Context) {
    private val file = File(context.filesDir, "log.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<LoggedItem>> = _items.asStateFlow()

    @Synchronized
    fun add(item: LoggedItem) = save(listOf(item) + _items.value.filter { it.clientId != item.clientId })

    @Synchronized
    fun replace(item: LoggedItem) = save(_items.value.map { if (it.clientId == item.clientId) item else it })

    @Synchronized
    fun remove(clientId: String) = save(_items.value.filter { it.clientId != clientId })

    /** Starring is per food name, so every later log of "Masala chai" is a favourite too. */
    @Synchronized
    fun toggleFavourite(name: String) {
        val fav = _items.value.none { it.name == name && it.favourite }
        save(_items.value.map { if (it.name == name) it.copy(favourite = fav) else it })
    }

    private fun save(list: List<LoggedItem>) {
        // Keep 90 days; favourites are kept longer (their newest entry) so one tap still works.
        val cutoff = Instant.now().minus(90, ChronoUnit.DAYS).epochSecond
        val sorted = list.sortedByDescending { it.eatenAt }
        val newestFavourites = sorted.filter { it.favourite }.distinctBy { it.name }.map { it.clientId }.toSet()
        val kept = sorted.filter { it.eatenAt >= cutoff || it.clientId in newestFavourites }
        _items.value = kept
        runCatching { file.writeText(json.encodeToString(SERIALIZER, kept)) }
    }

    private fun load(): List<LoggedItem> = runCatching {
        json.decodeFromString(SERIALIZER, file.readText())
    }.getOrElse { emptyList() }
}

private val SERIALIZER = ListSerializer(LoggedItem.serializer())

/** One logged food: the amount eaten and the per-100 values, so the amount can be changed later. */
@Serializable
data class LoggedItem(
    val clientId: String,
    val name: String,
    val slot: String,
    /** Epoch seconds. */
    val eatenAt: Long,
    val amount: Double,
    val unit: String,
    val kcal: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    val saturatedFat: Double? = null,
    val sugar: Double? = null,
    val fiber: Double? = null,
    val sodiumMg: Double? = null,
    val estimate: Boolean = false,
    val favourite: Boolean = false,
) {
    val per100: Nutrients get() = Nutrients(kcal, protein, carbs, fat, saturatedFat, sugar, fiber, sodiumMg)
    val mealSlot: MealSlot get() = runCatching { MealSlot.valueOf(slot) }.getOrDefault(MealSlot.SNACK)
    val nutrients: Nutrients get() = per100.scaled(amount / 100.0)
    val time: Instant get() = Instant.ofEpochSecond(eatenAt)

    fun toEntry(): NutritionEntry = NutritionEntry(clientId, name, mealSlot, time, nutrients)

    companion object {
        fun of(entry: NutritionEntry, amount: Double, unit: String, per100: Nutrients, estimate: Boolean) = with(per100) {
            LoggedItem(
                entry.clientId, entry.name, entry.slot.name, entry.eatenAt.epochSecond, amount, unit,
                energyKcal, proteinG, carbsG, fatG, saturatedFatG, sugarG, fiberG, sodiumMg, estimate,
            )
        }
    }
}
