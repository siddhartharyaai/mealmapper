package app.mealmapper.data.chat

import android.content.Context
import app.mealmapper.domain.Draft
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The conversation, in one JSON file. The screen and the background worker both change it, so every change is
 * synchronized and written at once; the screen watches [messages].
 */
class ChatStore(context: Context) {
    private val file = File(context.filesDir, "chat.json")
    val photoDir = File(context.filesDir, "chat-photos").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val serializer = ListSerializer(ChatMessage.serializer())

    private val _messages = MutableStateFlow(load())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    private var lastId = _messages.value.maxOfOrNull { it.id } ?: 0L

    @Synchronized
    fun newId(): Long = ++lastId

    @Synchronized
    fun add(m: ChatMessage) = save(_messages.value + m)

    @Synchronized
    fun update(block: (List<ChatMessage>) -> List<ChatMessage>) = save(block(_messages.value))

    @Synchronized
    fun updateUser(id: Long, block: (ChatMessage.User) -> ChatMessage.User) =
        save(_messages.value.map { if (it.id == id && it is ChatMessage.User) block(it) else it })

    @Synchronized
    fun updateCard(id: Long, block: (Draft) -> Draft) =
        save(_messages.value.map { if (it.id == id && it is ChatMessage.Card) it.copy(draft = block(it.draft)) else it })

    /** Replaces the card's item [index] (when it still exists and is open). */
    fun updateItem(cardId: Long, index: Int, block: (app.mealmapper.domain.DraftItem) -> app.mealmapper.domain.DraftItem) =
        updateCard(cardId) { d ->
            if (d.state != Draft.State.PENDING) d else d.copy(items = d.items.mapIndexed { i, it -> if (i == index) block(it) else it })
        }

    fun user(id: Long): ChatMessage.User? = _messages.value.firstOrNull { it.id == id } as? ChatMessage.User
    fun card(id: Long): ChatMessage.Card? = _messages.value.firstOrNull { it.id == id } as? ChatMessage.Card

    private fun save(list: List<ChatMessage>) {
        _messages.value = list
        runCatching {
            val tmp = File(file.parentFile, "chat.json.tmp")
            tmp.writeText(json.encodeToString(serializer, list))
            tmp.renameTo(file)
        }
    }

    private fun load(): List<ChatMessage> {
        val all = runCatching { json.decodeFromString(serializer, file.readText()) }.getOrElse { emptyList() }
        val kept = ChatHistory.prune(all, LocalDate.now(), ZoneId.systemDefault())
        // Photos belong to messages; delete the ones whose message is gone.
        val used = kept.flatMap { (it as? ChatMessage.User)?.photos.orEmpty() }.toSet()
        photoDir.listFiles()?.filter { it.path !in used }?.forEach { it.delete() }
        return kept
    }
}
