package app.mealmapper.ui.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.mealmapper.AppContainer
import app.mealmapper.data.chat.ChatMessage
import app.mealmapper.data.chat.ChatWorker
import app.mealmapper.data.health.HealthConnectAvailability
import app.mealmapper.data.log.LoggedItem
import app.mealmapper.domain.DayTotals
import app.mealmapper.domain.Draft
import app.mealmapper.domain.DraftItem
import app.mealmapper.domain.MealSlot
import app.mealmapper.domain.Memory
import app.mealmapper.domain.NutritionEntry
import app.mealmapper.domain.SourceKind
import app.mealmapper.domain.Suggestion
import app.mealmapper.domain.eatenAtFor
import app.mealmapper.domain.mealSlotFor
import app.mealmapper.ui.photo.ImageTools
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Ticker(val healthReady: Boolean? = null, val totals: DayTotals? = null, val capKcal: Int? = null, val failed: Boolean = false)

/**
 * The screen side of the chat. Sending only saves the message and starts its background job (ChatWorker); the
 * job writes its progress and the log card into the saved conversation, which this screen shows. Card edits,
 * Log and Undo happen here.
 */
class ChatViewModel(private val app: Context, private val c: AppContainer) : ViewModel() {

    val messages: StateFlow<List<ChatMessage>> = c.chat.messages

    private val _ticker = MutableStateFlow(Ticker())
    val ticker: StateFlow<Ticker> = _ticker.asStateFlow()

    /** One-tap chips: saved meals and usual foods for the meal of the hour. */
    val suggestions: StateFlow<List<Suggestion>> = combine(c.memory.state, c.chat.messages) { mem, _ ->
        Memory.suggestions(mem.foods, mem.meals, mealSlotFor(LocalTime.now()))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val store get() = c.chat

    // ---------- conversation ----------

    /** Saves the message (photos copied into the app, so the job can read them later) and starts its job. */
    fun send(text: String, photos: List<Uri>) {
        val message = text.trim()
        if (message.isEmpty() && photos.isEmpty()) return
        viewModelScope.launch {
            val id = store.newId()
            val files = withContext(Dispatchers.IO) {
                val side = if (photos.size > 2) 1280 else 1600
                photos.mapIndexedNotNull { i, uri ->
                    runCatching {
                        File(store.photoDir, "m$id-$i.jpg").apply { writeBytes(ImageTools.jpeg(app, uri, side)) }.path
                    }.getOrNull()
                }
            }
            store.add(
                ChatMessage.User(
                    id, System.currentTimeMillis(), message, files,
                    status = ChatMessage.Status.QUEUED, progress = "Waiting for the network…",
                ),
            )
            ChatWorker.enqueue(app, id)
        }
    }

    fun onBarcode(code: String) {
        val id = store.newId()
        store.add(ChatMessage.User(id, System.currentTimeMillis(), "Barcode $code", barcode = code, status = ChatMessage.Status.QUEUED, progress = "Waiting for the network…"))
        ChatWorker.enqueue(app, id)
    }

    /** Runs a failed message again (keeping any card it already made). */
    fun retry(messageId: Long) {
        store.updateUser(messageId) { it.copy(status = ChatMessage.Status.QUEUED, error = null, progress = "Trying again…") }
        ChatWorker.enqueue(app, messageId, replace = true)
    }

    fun onSuggestion(s: Suggestion) {
        val mem = c.memory.state.value
        val slot = mealSlotFor(LocalTime.now())
        val now = System.currentTimeMillis()
        s.mealId?.let { c.memory.meal(it) }?.let { meal ->
            val items = meal.parts.mapNotNull { part -> mem.foods.firstOrNull { it.id == part.foodId }?.let { Memory.toDraftItem(it, part.qty, part.unit) } }
            store.add(ChatMessage.User(store.newId(), now, meal.name))
            store.add(ChatMessage.Bot(store.newId(), now, "Your \"${meal.name}\":"))
            val id = store.newId()
            store.add(ChatMessage.Card(id, now, Draft(id, items, LocalDate.now(), MealSlot.valueOf(meal.slot))))
            return
        }
        s.foodId?.let { c.memory.food(it) }?.let { f ->
            store.add(ChatMessage.User(store.newId(), now, f.name))
            val id = store.newId()
            store.add(ChatMessage.Card(id, now, Draft(id, listOf(Memory.toDraftItem(f)), LocalDate.now(), slot)))
        }
    }

    // ---------- card edits ----------

    private fun editCard(cardId: Long, block: (Draft) -> Draft) =
        store.updateCard(cardId) { d -> if (d.state == Draft.State.PENDING) block(d) else d }

    private fun editItem(cardId: Long, index: Int, block: (DraftItem) -> DraftItem) = store.updateItem(cardId, index, block)

    fun stepQty(cardId: Long, index: Int, up: Boolean) = editItem(cardId, index) { it.copy(qty = it.step(up)) }

    /** A typed amount, in the item's current unit. */
    fun setQty(cardId: Long, index: Int, qty: Double) {
        if (qty > 0 && qty < 100_000) editItem(cardId, index) { it.copy(qty = qty) }
    }

    fun setUnit(cardId: Long, index: Int, unit: String) = editItem(cardId, index) { item ->
        // Keep roughly the same amount when switching units: 2 scoops (66 g) -> 66 g.
        val grams = item.grams
        val per = item.units[unit] ?: 1.0
        val qty = if (unit == item.basis.unit) Math.round(grams).toDouble() else maxOf(0.5, Math.round(grams / per * 2) / 2.0)
        item.copy(unit = unit, qty = qty)
    }

    fun toggleItem(cardId: Long, index: Int) = editItem(cardId, index) { it.copy(include = !it.include) }
    fun setDay(cardId: Long, day: LocalDate) = editCard(cardId) { it.copy(day = day) }
    fun setSlot(cardId: Long, slot: MealSlot) = editCard(cardId) { it.copy(slot = slot) }
    fun discard(cardId: Long) = editCard(cardId) { it.copy(state = Draft.State.DISCARDED) }
    fun dropSaveAs(cardId: Long) = editCard(cardId) { it.copy(saveAs = null) }

    fun saveAsMeal(cardId: Long, name: String) {
        val card = store.card(cardId) ?: return
        if (card.draft.state == Draft.State.PENDING) {
            editCard(cardId) { it.copy(saveAs = name) }
            say("When you tap Log it, I will also save it as \"$name\".")
        } else {
            c.memory.saveMeal(name, card.draft.slot, card.draft.items.filter { it.include })
            say("Saved as \"$name\". Next time say \"$name\" or tap it above the chat box.")
        }
    }

    /** Writes each ticked item to Health Connect, keeps a local copy, learns the foods, and saves the meal if asked. */
    fun log(cardId: Long) {
        val card = store.card(cardId) ?: return
        val d = card.draft
        if (!d.canLog) return
        store.updateCard(cardId) { it.copy(state = Draft.State.SAVING) }
        viewModelScope.launch {
            val items = d.items.filter { it.include }
            val base = eatenAtFor(d.slot, d.day, LocalDateTime.now()).atZone(ZoneId.systemDefault()).toInstant()
            val written = mutableListOf<String>()
            try {
                items.forEachIndexed { i, item ->
                    val entry = NutritionEntry(
                        clientId = UUID.randomUUID().toString(),
                        name = "${item.name} · ${item.amountText}",
                        slot = d.slot,
                        eatenAt = base.minusSeconds(i.toLong()),
                        nutrients = item.nutrients,
                    )
                    c.healthConnect.write(entry)
                    written += entry.clientId
                    c.log.add(LoggedItem.of(entry, item.grams, item.basis.unit, item.per100, estimate = item.sourceKind == SourceKind.AI))
                }
                val ids = d.saveAs?.let { name -> c.memory.saveMeal(name, d.slot, items).parts.map { it.foodId } }
                    ?: c.memory.learn(items, d.slot)
                var k = 0
                val learned = d.items.map { if (it.include) it.copy(memoryId = ids[k++]) else it }
                store.updateCard(cardId) {
                    it.copy(items = learned, state = Draft.State.LOGGED, writtenIds = written, savedAs = d.saveAs)
                }
                refreshTicker()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep what was written so Undo can remove it; the rest can be retried.
                val left = items.drop(written.size)
                store.updateCard(cardId) { it.copy(items = left + d.items.filterNot { i -> i.include }, state = Draft.State.PENDING) }
                say("Saved ${written.size} of ${items.size}. Health Connect said: ${e.message ?: e.javaClass.simpleName}", error = true)
                refreshTicker()
            }
        }
    }

    fun undo(cardId: Long) {
        val card = store.card(cardId) ?: return
        viewModelScope.launch {
            card.draft.writtenIds.forEach { id ->
                runCatching { c.healthConnect.delete(id) }
                c.log.remove(id)
            }
            store.updateCard(cardId) { it.copy(state = Draft.State.PENDING, writtenIds = emptyList(), savedAs = null) }
            refreshTicker()
        }
    }

    // ---------- ticker ----------

    fun refreshTicker() {
        _ticker.update { it.copy(capKcal = c.profile.profile.value.dailyCapKcal) }
        viewModelScope.launch {
            val ready = c.healthConnect.availability() == HealthConnectAvailability.AVAILABLE &&
                runCatching { c.healthConnect.hasAllPermissions() }.getOrDefault(false)
            if (!ready) {
                _ticker.update { it.copy(healthReady = false, totals = null) }
                return@launch
            }
            val totals = runCatching { c.healthConnect.todayTotals() }.getOrNull()
            _ticker.update { it.copy(healthReady = true, totals = totals, failed = totals == null) }
        }
    }

    private fun say(text: String, error: Boolean = false) = store.add(ChatMessage.Bot(store.newId(), System.currentTimeMillis(), text, error))

    init {
        refreshTicker()
    }

    companion object {
        fun factory(app: Context, c: AppContainer) = viewModelFactory { initializer { ChatViewModel(app.applicationContext, c) } }
    }
}
