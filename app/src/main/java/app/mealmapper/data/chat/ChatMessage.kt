package app.mealmapper.data.chat

import app.mealmapper.domain.Draft
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One line of the conversation. Saved to the phone, so a restart or a closed app loses nothing. */
@Serializable
sealed interface ChatMessage {
    val id: Long

    /** When it was added, epoch milliseconds. */
    val at: Long

    /**
     * What the user sent. Processing runs in the background (see ChatWorker), so the message itself carries its
     * state: waiting, working (with a progress line), done, or failed (with the reason and a Retry button).
     */
    @Serializable
    @SerialName("user")
    data class User(
        override val id: Long,
        override val at: Long,
        val text: String,
        /** JPEG files in the app's own storage (copied on send, so they survive a restart). */
        val photos: List<String> = emptyList(),
        val barcode: String? = null,
        val status: Status = Status.DONE,
        val progress: String? = null,
        val error: String? = null,
        /** The card this message produced; set as soon as it exists so a resumed job only finishes its lookups. */
        val cardId: Long? = null,
    ) : ChatMessage {
        val active: Boolean get() = status == Status.QUEUED || status == Status.WORKING
    }

    @Serializable
    @SerialName("bot")
    data class Bot(override val id: Long, override val at: Long, val text: String, val error: Boolean = false) : ChatMessage

    @Serializable
    @SerialName("card")
    data class Card(override val id: Long, override val at: Long, val draft: Draft) : ChatMessage

    enum class Status { QUEUED, WORKING, DONE, FAILED }
}

object ChatHistory {
    /**
     * What to keep when the app starts: today's and yesterday's conversation, plus anything still open
     * (a card not logged yet, a message still being processed) however old.
     */
    fun prune(list: List<ChatMessage>, today: LocalDate, zone: ZoneId): List<ChatMessage> {
        val since = today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return list.filter { m ->
            m.at >= since ||
                (m is ChatMessage.Card && m.draft.state == Draft.State.PENDING) ||
                (m is ChatMessage.User && m.active)
        }
    }

    /** The newest card still open before message [beforeId]: the one a correction ("make it 2 scoops") applies to. */
    fun openCard(list: List<ChatMessage>, beforeId: Long): ChatMessage.Card? =
        list.lastOrNull { it.id < beforeId && it is ChatMessage.Card && it.draft.state == Draft.State.PENDING } as? ChatMessage.Card

    fun lastLoggedCard(list: List<ChatMessage>): ChatMessage.Card? =
        list.lastOrNull { it is ChatMessage.Card && it.draft.state == Draft.State.LOGGED } as? ChatMessage.Card
}
