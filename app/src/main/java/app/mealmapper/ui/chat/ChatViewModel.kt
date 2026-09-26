package app.mealmapper.ui.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.mealmapper.AppContainer
import app.mealmapper.data.ai.AiException
import app.mealmapper.data.ai.ChatBrain
import app.mealmapper.data.ai.ChatParsing
import app.mealmapper.data.ai.ChatPlan
import app.mealmapper.data.ai.LookupOutcome
import app.mealmapper.data.health.HealthConnectAvailability
import app.mealmapper.data.log.LoggedItem
import app.mealmapper.data.off.LookupResult
import app.mealmapper.data.off.ParseResult
import app.mealmapper.domain.Basis
import app.mealmapper.domain.DayTotals
import app.mealmapper.domain.Draft
import app.mealmapper.domain.DraftItem
import app.mealmapper.domain.FoodProduct
import app.mealmapper.domain.MealSlot
import app.mealmapper.domain.Memory
import app.mealmapper.domain.NutritionEntry
import app.mealmapper.domain.SourceKind
import app.mealmapper.domain.Suggestion
import app.mealmapper.domain.Units
import app.mealmapper.domain.eatenAtFor
import app.mealmapper.domain.mealSlotFor
import app.mealmapper.domain.mealSlotIn
import app.mealmapper.ui.photo.ImageTools
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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

sealed interface ChatMessage {
    val id: Long

    data class User(override val id: Long, val text: String, val photos: List<Uri>) : ChatMessage
    data class Bot(override val id: Long, val text: String, val error: Boolean = false) : ChatMessage
    data class Card(override val id: Long, val draft: Draft) : ChatMessage
    data class Working(override val id: Long, val text: String) : ChatMessage
}

data class Ticker(val healthReady: Boolean? = null, val totals: DayTotals? = null, val capKcal: Int? = null, val failed: Boolean = false)

/**
 * The whole app in one conversation: the user says what they ate, the app answers with a log card (items, natural
 * units, values and where each value came from), the user taps Log. Repeats come from memory, not new searches.
 */
class ChatViewModel(private val app: Context, private val c: AppContainer) : ViewModel() {
    private val brain = ChatBrain(c.gemini)

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _ticker = MutableStateFlow(Ticker())
    val ticker: StateFlow<Ticker> = _ticker.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** One-tap chips: saved meals and usual foods for the meal of the hour. */
    val suggestions: StateFlow<List<Suggestion>> = combine(c.memory.state, _messages) { mem, _ ->
        Memory.suggestions(mem.foods, mem.meals, mealSlotFor(LocalTime.now()))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var nextId = 1L
    private fun id() = nextId++

    // ---------- conversation ----------

    fun send(text: String, photos: List<Uri>) {
        val message = text.trim()
        if ((message.isEmpty() && photos.isEmpty()) || _busy.value) return
        add(ChatMessage.User(id(), message, photos))
        work("Reading what you ate…") { working ->
            val profile = c.profile.profile.value
            val mem = c.memory.state.value
            val open = pendingCard()
            val images = withContext(Dispatchers.IO) {
                val side = if (photos.size > 2) 1280 else 1600
                photos.map { ImageTools.jpeg(app, it, side) }
            }
            val now = LocalDateTime.now()
            val plan = brain.understand(
                message,
                images,
                ChatBrain.Context(
                    now = now,
                    defaultSlot = mealSlotFor(now.toLocalTime()),
                    katoriMl = profile.katori,
                    oilGramsPerPersonDay = profile.oilGramsPerPersonDay,
                    memory = Memory.promptLines(mem.foods, mem.meals),
                    recent = recentLogs(),
                    draft = open?.draft?.items,
                ),
            )
            handle(plan, message, open, working)
        }
    }

    private suspend fun handle(plan: ChatPlan, message: String, open: ChatMessage.Card?, working: Long) {
        when (plan.action) {
            "answer" -> say(plan.reply.ifBlank { "I could not find any food in that. Tell me what you ate, like \"2 roti and dal for lunch\"." })
            "save_meal" -> {
                val name = plan.saveMealName ?: "My usual ${(open?.draft?.slot ?: mealSlotFor(LocalTime.now())).label.lowercase()}"
                val target = open ?: lastLoggedCard()
                if (target == null) {
                    say("Log a meal first, then say \"save this as $name\".")
                } else {
                    c.memory.saveMeal(name, target.draft.slot, target.draft.items.filter { it.include })
                    say("Saved as \"$name\". Next time just say \"$name\", or tap it above the chat box.")
                }
            }
            else -> {
                if (plan.items.isEmpty()) {
                    say(plan.reply.ifBlank { "I could not find any food in that. Tell me what you ate, like \"2 roti and dal for lunch\"." })
                    return
                }
                val items = buildItems(plan) { status -> setWorking(working, status) }
                val replace = plan.replacesDraft && open != null
                val today = LocalDate.now()
                val draft = Draft(
                    id = if (replace) open!!.draft.id else id(),
                    items = items,
                    day = plan.day ?: open?.draft?.day?.takeIf { replace } ?: today,
                    slot = plan.slot ?: open?.draft?.slot?.takeIf { replace } ?: mealSlotIn(message) ?: mealSlotFor(LocalTime.now()),
                    question = plan.question.takeIf { plan.action == "ask" },
                )
                if (plan.reply.isNotBlank()) say(plan.reply)
                if (replace) {
                    _messages.update { list -> list.map { if (it.id == open!!.id) ChatMessage.Card(it.id, draft) else it } }
                } else {
                    add(ChatMessage.Card(id(), draft))
                }
            }
        }
    }

    /** Memory first (no lookup), then the source ladder for everything else, keeping the user's order. */
    private suspend fun buildItems(plan: ChatPlan, progress: (String) -> Unit): List<DraftItem> {
        val katori = c.profile.profile.value.katori
        val out = arrayOfNulls<DraftItem>(plan.items.size)
        val toResolve = mutableListOf<Int>()
        plan.items.forEachIndexed { i, p ->
            val mem = p.memoryId?.let(c.memory::food)
            if (mem != null) {
                val gramsPerUnit = p.grams / p.quantity
                val withUnit = if (p.unit !in mem.units && p.unit != mem.basis) mem.copy(units = mem.units + (p.unit to gramsPerUnit)) else mem
                out[i] = Memory.toDraftItem(withUnit, p.quantity, p.unit, p.said)
            } else {
                toResolve += i
            }
        }
        if (toResolve.isNotEmpty()) {
            val lookups = toResolve.map { ChatParsing.toMealItem(plan.items[it]) }
            val resolved = c.nutritionLookup.resolve(lookups, c.foodDb.all(), progress)
            toResolve.forEachIndexed { k, i ->
                val p = plan.items[i]
                val item = resolved.items[k]
                val db = resolved.matched[k]
                val basis = if (p.unit == "ml") Basis.MILLILITRES else Basis.GRAMS
                val (per100, kind, detail) = when {
                    db != null -> Triple(db.per100, SourceKind.DATABANK, "${db.source}: ${db.name.substringBefore(" (")}")
                    p.labelPer100 != null -> Triple(p.labelPer100, SourceKind.LABEL, "your photo")
                    item.published -> Triple(item.per100, SourceKind.WEB, item.assumption)
                    else -> Triple(item.per100, SourceKind.AI, item.assumption ?: p.note)
                }
                val unit = if (p.unit == "g" || p.unit == "ml") basis.unit else p.unit
                out[i] = DraftItem(
                    name = p.name,
                    said = p.said,
                    qty = if (unit == basis.unit) p.grams else p.quantity,
                    unit = unit,
                    units = Units.forItem(
                        p.name, basis, unit, p.grams / p.quantity, katori, item.servingGrams,
                        dish = p.kind == "dish" || p.kind == "restaurant",
                    ),
                    per100 = per100,
                    basis = basis,
                    sourceKind = kind,
                    sourceDetail = detail,
                    memoryId = null,
                )
            }
        }
        return out.filterNotNull()
    }

    fun onSuggestion(s: Suggestion) {
        val mem = c.memory.state.value
        val slot = mealSlotFor(LocalTime.now())
        s.mealId?.let { c.memory.meal(it) }?.let { meal ->
            val items = meal.parts.mapNotNull { part -> mem.foods.firstOrNull { it.id == part.foodId }?.let { Memory.toDraftItem(it, part.qty, part.unit) } }
            add(ChatMessage.User(id(), meal.name, emptyList()))
            say("Your \"${meal.name}\":")
            add(ChatMessage.Card(id(), Draft(id(), items, LocalDate.now(), MealSlot.valueOf(meal.slot))))
            return
        }
        s.foodId?.let { c.memory.food(it) }?.let { f ->
            add(ChatMessage.User(id(), f.name, emptyList()))
            add(ChatMessage.Card(id(), Draft(id(), listOf(Memory.toDraftItem(f)), LocalDate.now(), slot)))
        }
    }

    /** A scanned barcode: saved product, then Open Food Facts, then the web. */
    fun onBarcode(code: String) {
        add(ChatMessage.User(id(), "Barcode $code", emptyList()))
        work("Looking up the barcode…") { working ->
            val product: FoodProduct? = c.productCache.get(code)
                ?: when (val r = c.openFoodFacts.lookup(code)) {
                    is LookupResult.Found -> when (val p = r.result) {
                        is ParseResult.Product -> p.product
                        is ParseResult.NoNutrition -> webProduct(code, p.name, working)
                        ParseResult.NotFound -> webProduct(code, null, working)
                    }
                    is LookupResult.Failed -> null
                }
            if (product == null) {
                say("I could not find this product's values. Take a photo of its nutrition label and send it: I will read it.")
                return@work
            }
            val units = linkedMapOf<String, Double>()
            product.servingSize?.let { units["serving"] = it }
            product.packSize?.let { units["pack"] = it }
            units[product.basis.unit] = 1.0
            val unit = if (product.servingSize != null) "serving" else product.basis.unit
            val item = DraftItem(
                name = listOfNotNull(product.brand?.takeUnless { product.name.contains(it, true) }, product.name).joinToString(" "),
                said = null,
                qty = if (unit == "serving") 1.0 else 100.0,
                unit = unit,
                units = units,
                per100 = product.per100,
                basis = product.basis,
                sourceKind = SourceKind.LABEL,
                sourceDetail = "barcode $code",
                memoryId = null,
            )
            c.productCache.put(product)
            say("Found it. Change the amount if you had more or less.")
            add(ChatMessage.Card(id(), Draft(id(), listOf(item), LocalDate.now(), mealSlotFor(LocalTime.now()))))
        }
    }

    private suspend fun webProduct(code: String, name: String?, working: Long): FoodProduct? {
        setWorking(working, "Not in Open Food Facts: searching the web…")
        return (c.nutritionLookup.web(code, name) as? LookupOutcome.Found)?.product
    }

    // ---------- card edits ----------

    private fun editCard(cardId: Long, block: (Draft) -> Draft) = _messages.update { list ->
        list.map { m -> if (m is ChatMessage.Card && m.id == cardId && m.draft.state == Draft.State.PENDING) m.copy(draft = block(m.draft)) else m }
    }

    private fun editItem(cardId: Long, index: Int, block: (DraftItem) -> DraftItem) =
        editCard(cardId) { d -> d.copy(items = d.items.mapIndexed { i, it -> if (i == index) block(it) else it }) }

    fun stepQty(cardId: Long, index: Int, up: Boolean) = editItem(cardId, index) { it.copy(qty = it.step(up)) }

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

    fun saveAsMeal(cardId: Long, name: String) {
        val card = card(cardId) ?: return
        c.memory.saveMeal(name, card.draft.slot, card.draft.items.filter { it.include })
        say("Saved as \"$name\". Next time say \"$name\" or tap it above the chat box.")
    }

    /** Writes each ticked item to Health Connect, keeps a local copy, and learns the foods for next time. */
    fun log(cardId: Long) {
        val card = card(cardId) ?: return
        val d = card.draft
        if (!d.canLog) return
        _messages.update { list -> list.map { if (it.id == cardId) card.copy(draft = d.copy(state = Draft.State.SAVING)) else it } }
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
                val ids = c.memory.learn(items, d.slot)
                var k = 0
                val learned = d.items.map { if (it.include) it.copy(memoryId = ids[k++]) else it }
                replaceCard(cardId, d.copy(items = learned, state = Draft.State.LOGGED, writtenIds = written))
                refreshTicker()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep what was written so Undo can remove it; the rest can be retried.
                val left = items.drop(written.size)
                replaceCard(cardId, d.copy(items = left + d.items.filterNot { it.include }, state = Draft.State.PENDING))
                say("Saved ${written.size} of ${items.size}. Health Connect said: ${e.message ?: e.javaClass.simpleName}", error = true)
                refreshTicker()
            }
        }
    }

    fun undo(cardId: Long) {
        val card = card(cardId) ?: return
        viewModelScope.launch {
            card.draft.writtenIds.forEach { id ->
                runCatching { c.healthConnect.delete(id) }
                c.log.remove(id)
            }
            replaceCard(cardId, card.draft.copy(state = Draft.State.PENDING, writtenIds = emptyList()))
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

    // ---------- helpers ----------

    private fun add(m: ChatMessage) = _messages.update { it + m }
    private fun say(text: String, error: Boolean = false) = add(ChatMessage.Bot(id(), text, error))
    private fun setWorking(id: Long, text: String) = _messages.update { list -> list.map { if (it.id == id) ChatMessage.Working(id, text) else it } }
    private fun card(id: Long) = _messages.value.firstOrNull { it.id == id } as? ChatMessage.Card
    private fun replaceCard(id: Long, d: Draft) = _messages.update { list -> list.map { if (it.id == id) ChatMessage.Card(id, d) else it } }
    private fun pendingCard() = _messages.value.lastOrNull { it is ChatMessage.Card && it.draft.state == Draft.State.PENDING } as? ChatMessage.Card
    private fun lastLoggedCard() = _messages.value.lastOrNull { it is ChatMessage.Card && it.draft.state == Draft.State.LOGGED } as? ChatMessage.Card

    /** Runs [block] with a "working" bubble that it can update, and turns errors into a plain message. */
    private fun work(start: String, block: suspend (working: Long) -> Unit) {
        _busy.value = true
        val w = id()
        add(ChatMessage.Working(w, start))
        viewModelScope.launch {
            try {
                block(w)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                say(e.message ?: "The AI step failed. Try again.", error = true)
            } catch (e: Exception) {
                say("Something went wrong: ${e.message ?: e.javaClass.simpleName}. Try again.", error = true)
            } finally {
                _messages.update { list -> list.filterNot { it.id == w } }
                _busy.value = false
            }
        }
    }

    /** The last two days of logs, for the model: patterns and "same as yesterday". */
    private fun recentLogs(): String {
        val zone = ZoneId.systemDefault()
        val since = LocalDate.now().minusDays(2)
        val fmt = DateTimeFormatter.ofPattern("EEE")
        return c.log.items.value
            .filter { !it.time.atZone(zone).toLocalDate().isBefore(since) }
            .groupBy { it.time.atZone(zone).toLocalDate() to it.mealSlot }
            .entries.sortedBy { it.key.first }
            .joinToString("\n") { (key, list) ->
                "${key.first.format(fmt)} ${key.second.label.lowercase()}: " + list.joinToString { it.name }
            }
    }

    init {
        refreshTicker()
    }

    companion object {
        fun factory(app: Context, c: AppContainer) = viewModelFactory { initializer { ChatViewModel(app.applicationContext, c) } }
    }
}
