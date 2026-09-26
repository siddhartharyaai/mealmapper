package app.mealmapper.data.memory

import android.content.Context
import app.mealmapper.domain.DraftItem
import app.mealmapper.domain.MealPart
import app.mealmapper.domain.MealSlot
import app.mealmapper.domain.Memory
import app.mealmapper.domain.MemoryFood
import app.mealmapper.domain.SavedMeal
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The user's remembered foods and saved meals, in one JSON file on the phone. */
class MemoryStore(context: Context) {
    private val file = File(context.filesDir, "memory.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    @Serializable
    data class Snapshot(val foods: List<MemoryFood> = emptyList(), val meals: List<SavedMeal> = emptyList())

    /** Learns every logged item; returns the memory id for each, in order. */
    @Synchronized
    fun learn(items: List<DraftItem>, slot: MealSlot): List<String> {
        var foods = _state.value.foods
        val now = System.currentTimeMillis()
        val ids = items.map { item ->
            val (list, id) = Memory.learn(foods, item, slot, now)
            foods = list
            id
        }
        save(_state.value.copy(foods = foods))
        return ids
    }

    /** Saves items as a named meal (learning any that are new). Same name replaces the old meal. */
    @Synchronized
    fun saveMeal(name: String, slot: MealSlot, items: List<DraftItem>): SavedMeal {
        val ids = learn(items, slot)
        val meal = SavedMeal(
            id = "s" + System.currentTimeMillis() % 1_000_000,
            name = name.trim(),
            slot = slot.name,
            parts = items.mapIndexed { i, it -> MealPart(ids[i], it.qty, it.unit) },
        )
        val meals = _state.value.meals.filterNot { it.name.equals(meal.name, ignoreCase = true) } + meal
        save(_state.value.copy(meals = meals))
        return meal
    }

    @Synchronized
    fun deleteFood(id: String) = save(
        _state.value.copy(
            foods = _state.value.foods.filterNot { it.id == id },
            meals = _state.value.meals.map { m -> m.copy(parts = m.parts.filterNot { it.foodId == id }) }.filter { it.parts.isNotEmpty() },
        ),
    )

    @Synchronized
    fun deleteMeal(id: String) = save(_state.value.copy(meals = _state.value.meals.filterNot { it.id == id }))

    fun food(id: String): MemoryFood? = _state.value.foods.firstOrNull { it.id == id }
    fun meal(id: String): SavedMeal? = _state.value.meals.firstOrNull { it.id == id }

    private fun save(s: Snapshot) {
        _state.value = s
        runCatching { file.writeText(json.encodeToString(Snapshot.serializer(), s)) }
    }

    private fun load(): Snapshot = runCatching { json.decodeFromString(Snapshot.serializer(), file.readText()) }.getOrElse { Snapshot() }
}
