package app.mealmapper.data.chat

import android.content.Context
import app.mealmapper.AppContainer
import app.mealmapper.data.ai.AiException
import app.mealmapper.data.ai.AiParsing
import app.mealmapper.data.ai.AiTransientException
import app.mealmapper.data.ai.ChatBrain
import app.mealmapper.data.ai.ChatParsing
import app.mealmapper.data.ai.ChatPlan
import app.mealmapper.data.ai.LookupOutcome
import app.mealmapper.data.ai.PlanItem
import app.mealmapper.data.off.LookupResult
import app.mealmapper.data.off.ParseResult
import app.mealmapper.domain.Basis
import app.mealmapper.domain.Draft
import app.mealmapper.domain.DraftItem
import app.mealmapper.domain.FoodProduct
import app.mealmapper.domain.LookupSpec
import app.mealmapper.domain.Memory
import app.mealmapper.domain.Nutrients
import app.mealmapper.domain.SourceKind
import app.mealmapper.domain.Units
import app.mealmapper.domain.mealSlotFor
import app.mealmapper.domain.mealSlotIn
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException

/**
 * Turns one user message into a log card. Runs inside [ChatWorker], so it keeps going when the user leaves the
 * app, and every step is saved in [ChatStore] as it happens:
 *  1. Understand the message (Gemini): items, units, meal, day. The card appears at once; items already in the
 *     user's memory are final, the rest show "Looking up…" with the AI's estimate.
 *  2. Look up the rest (databank, web) in parallel; each row fills in when its value arrives.
 * If Android stops the job halfway, the next run skips step 1 (the card exists) and finishes only the rows
 * still loading.
 */
class ChatEngine(private val app: Context, private val c: AppContainer) {
    private val store get() = c.chat
    private val brain by lazy { ChatBrain(c.gemini) }

    /** Returns true when the message is finished (done or failed), false to have WorkManager try again. */
    suspend fun run(messageId: Long, lastAttempt: Boolean): Boolean {
        val msg = store.user(messageId) ?: return true
        if (!msg.active) return true
        store.updateUser(messageId) { it.copy(status = ChatMessage.Status.WORKING, progress = it.progress ?: "Reading what you ate…", error = null) }
        return try {
            val cardId = msg.cardId
            when {
                cardId != null -> fill(messageId, cardId)
                msg.barcode != null -> barcode(msg)
                else -> understand(msg)
            }
            store.updateUser(messageId) { it.copy(status = ChatMessage.Status.DONE, progress = null) }
            notifyReady(messageId)
            true
        } catch (e: CancellationException) {
            // Android stopped the job (time limit, app killed); WorkManager runs it again.
            store.updateUser(messageId) { it.copy(status = ChatMessage.Status.QUEUED, progress = "Paused by Android. It continues by itself…") }
            throw e
        } catch (e: AiTransientException) {
            if (!lastAttempt) {
                store.updateUser(messageId) { it.copy(status = ChatMessage.Status.QUEUED, progress = "${e.message} Trying again…") }
                false
            } else {
                fail(messageId, e.message ?: "Network problem.")
            }
        } catch (e: AiException) {
            fail(messageId, e.message ?: "The AI step failed.")
        } catch (e: Exception) {
            fail(messageId, "Something went wrong: ${e.message ?: e.javaClass.simpleName}.")
        }
    }

    private fun fail(messageId: Long, reason: String): Boolean {
        val msg = store.user(messageId)
        // A card that exists keeps its AI estimates, marked as such, so nothing already found is lost.
        msg?.cardId?.let { id -> store.updateCard(id) { d -> d.copy(items = d.items.map { it.gaveUp("AI estimate: the lookup did not finish") }) } }
        store.updateUser(messageId) { it.copy(status = ChatMessage.Status.FAILED, progress = null, error = reason) }
        if (msg?.cardId == null) Notifier.meal(app, messageId, "Could not read your meal", "$reason Open Meal Mapper and tap Retry.")
        else notifyReady(messageId)
        return true
    }

    private fun notifyReady(messageId: Long) {
        val card = store.user(messageId)?.cardId?.let(store::card)?.draft ?: return
        val n = card.items.count { it.include }
        Notifier.meal(
            app, messageId,
            "${card.slot.label} ready to log",
            "$n item${if (n == 1) "" else "s"}, ${card.total.energyKcal.roundToInt()} kcal. Tap to check and log.",
        )
    }

    // ---------- step 1: understand ----------

    private suspend fun understand(msg: ChatMessage.User) {
        val profile = c.profile.profile.value
        val mem = c.memory.state.value
        val open = ChatHistory.openCard(store.messages.value, msg.id)
        val images = msg.photos.mapNotNull { runCatching { File(it).readBytes() }.getOrNull() }
        val now = LocalDateTime.now()
        val plan = brain.understand(
            msg.text,
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
        handle(plan, msg, open)
    }

    private suspend fun handle(plan: ChatPlan, msg: ChatMessage.User, open: ChatMessage.Card?) {
        val noFood = "I could not find any food in that. Tell me what you ate, like \"2 roti and dal for lunch\"."
        if (plan.action == "answer" || (plan.items.isEmpty() && plan.action != "save_meal")) {
            say(plan.reply.ifBlank { noFood })
            return
        }
        if (plan.items.isEmpty()) {
            // "Save this as my usual breakfast", about the open card or the last logged one.
            val target = open ?: ChatHistory.lastLoggedCard(store.messages.value)
            val name = plan.saveMealName ?: "My usual ${(target?.draft?.slot ?: mealSlotFor(LocalTime.now())).label.lowercase()}"
            if (target == null) {
                say("Log a meal first, then say \"save this as $name\".")
            } else if (target.draft.state == Draft.State.PENDING) {
                store.updateCard(target.id) { it.copy(saveAs = name) }
                say("When you tap Log it, I will also save it as \"$name\".")
            } else {
                c.memory.saveMeal(name, target.draft.slot, target.draft.items.filter { it.include })
                say("Saved as \"$name\". Next time just say \"$name\", or tap it above the chat box.")
            }
            return
        }

        val replace = plan.replacesDraft && open != null
        val cardId = if (replace) open!!.id else store.newId()
        val draft = Draft(
            id = cardId,
            items = plan.items.map(::firstLook),
            day = plan.day ?: open?.draft?.day?.takeIf { replace } ?: LocalDate.now(),
            slot = plan.slot ?: open?.draft?.slot?.takeIf { replace } ?: mealSlotIn(msg.text) ?: mealSlotFor(LocalTime.now()),
            question = plan.question.takeIf { plan.action == "ask" },
            saveAs = plan.saveMealName ?: open?.draft?.saveAs?.takeIf { replace },
        )
        if (plan.reply.isNotBlank()) say(plan.reply)
        store.update { list ->
            val withCard = if (replace) {
                list.map { if (it.id == cardId) ChatMessage.Card(cardId, it.at, draft) else it }
            } else {
                list + ChatMessage.Card(cardId, System.currentTimeMillis(), draft)
            }
            withCard.map { if (it.id == msg.id && it is ChatMessage.User) it.copy(cardId = cardId) else it }
        }
        fill(msg.id, cardId)
    }

    /**
     * The card row as soon as the message is understood: final for a remembered food or a label read from a
     * photo; otherwise the AI's estimate marked as loading, with what the lookup needs.
     */
    private fun firstLook(p: PlanItem): DraftItem {
        val katori = c.profile.profile.value.katori
        p.memoryId?.let(c.memory::food)?.let { mem ->
            val gramsPerUnit = p.grams / p.quantity
            val withUnit = if (p.unit !in mem.units && p.unit != mem.basis) mem.copy(units = mem.units + (p.unit to gramsPerUnit)) else mem
            return Memory.toDraftItem(withUnit, p.quantity, p.unit, p.said)
        }
        val basis = if (p.unit == "ml") Basis.MILLILITRES else Basis.GRAMS
        val unit = if (p.unit == "g" || p.unit == "ml") basis.unit else p.unit
        val meal = ChatParsing.toMealItem(p)
        return DraftItem(
            name = p.name,
            said = p.said,
            qty = if (unit == basis.unit) p.grams else p.quantity,
            unit = unit,
            units = Units.forItem(p.name, basis, unit, p.grams / p.quantity, katori, dish = p.kind == "dish" || p.kind == "restaurant"),
            per100 = p.labelPer100 ?: p.estimate ?: Nutrients(0.0, 0.0, 0.0, 0.0),
            basis = basis,
            sourceKind = if (p.labelPer100 != null) SourceKind.LABEL else SourceKind.AI,
            sourceDetail = if (p.labelPer100 != null) "your photo" else p.note,
            memoryId = null,
            loading = p.labelPer100 == null,
            lookup = if (p.labelPer100 == null) LookupSpec(p.kind, p.searchName, meal.lookup, p.restaurant, p.note) else null,
        )
    }

    // ---------- step 2: look up ----------

    private suspend fun fill(messageId: Long, cardId: Long) {
        val card = store.card(cardId)?.draft ?: return
        val todo = card.items.indices.filter { card.items[it].loading }
        if (todo.isEmpty()) return
        val lookups = todo.map { i ->
            val item = card.items[i]
            val spec = item.lookup
            AiParsing.MealItem(
                name = item.name,
                grams = item.grams,
                per100 = item.per100,
                lowKcal = null,
                highKcal = null,
                assumption = spec?.note,
                lookup = spec?.product,
                kind = spec?.kind,
                searchName = spec?.searchName,
                restaurant = spec?.restaurant,
            )
        }
        c.nutritionLookup.resolveEach(
            lookups,
            c.foodDb.all(),
            c.webCache,
            progress = { text -> store.updateUser(messageId) { it.copy(progress = text) } },
        ) { k, found, db ->
            val i = todo[k]
            val (per100, kind, detail) = when {
                db != null -> Triple(db.per100, SourceKind.DATABANK, "${db.source}: ${db.name.substringBefore(" (")}")
                found.published -> Triple(found.per100, SourceKind.WEB, found.assumption)
                else -> Triple(found.per100, SourceKind.AI, found.assumption)
            }
            store.updateItem(cardId, i) { item ->
                val units = found.servingGrams?.let { s -> item.units + ("serving" to s) } ?: item.units
                item.fill(item.copy(per100 = per100, sourceKind = kind, sourceDetail = detail, units = units))
            }
        }
    }

    // ---------- barcode ----------

    private suspend fun barcode(msg: ChatMessage.User) {
        val code = msg.barcode ?: return
        store.updateUser(msg.id) { it.copy(progress = "Looking up the barcode…") }
        val product: FoodProduct? = c.productCache.get(code)
            ?: when (val r = c.openFoodFacts.lookup(code)) {
                is LookupResult.Found -> when (val p = r.result) {
                    is ParseResult.Product -> p.product
                    is ParseResult.NoNutrition -> webProduct(msg.id, code, p.name)
                    ParseResult.NotFound -> webProduct(msg.id, code, null)
                }
                is LookupResult.Failed -> null
            }
        if (product == null) {
            say("I could not find this product's values. Take a photo of its nutrition label and send it: I will read it.")
            return
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
        val cardId = store.newId()
        store.add(ChatMessage.Card(cardId, System.currentTimeMillis(), Draft(cardId, listOf(item), LocalDate.now(), mealSlotFor(LocalTime.now()))))
        store.updateUser(msg.id) { it.copy(cardId = cardId) }
    }

    private suspend fun webProduct(messageId: Long, code: String, name: String?): FoodProduct? {
        store.updateUser(messageId) { it.copy(progress = "Not in Open Food Facts: searching the web…") }
        return (c.nutritionLookup.web(code, name) as? LookupOutcome.Found)?.product
    }

    // ---------- helpers ----------

    private fun say(text: String) = store.add(ChatMessage.Bot(store.newId(), System.currentTimeMillis(), text))

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
}
