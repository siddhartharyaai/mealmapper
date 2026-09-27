package app.mealmapper.data.chat

import app.mealmapper.domain.Basis
import app.mealmapper.domain.Draft
import app.mealmapper.domain.DraftItem
import app.mealmapper.domain.MealSlot
import app.mealmapper.domain.Nutrients
import app.mealmapper.domain.SourceKind
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatHistoryTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val today = LocalDate.of(2026, 9, 27)
    private fun at(day: LocalDate) = day.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
    private val item = DraftItem("Almonds", null, 5.0, "piece", mapOf("piece" to 1.2, "g" to 1.0), Nutrients(579.0, 21.0, 22.0, 50.0),
        Basis.GRAMS, SourceKind.DATABANK, "IFCT", null)
    private fun card(id: Long, day: LocalDate, state: Draft.State) = ChatMessage.Card(id, at(day), Draft(id, listOf(item), day, MealSlot.BREAKFAST, state = state))

    @Test fun keepsTodayYesterdayAndAnythingOpen() {
        val old = today.minusDays(3)
        val list = listOf(
            ChatMessage.User(1, at(old), "old and done"),
            card(2, old, Draft.State.LOGGED),
            card(3, old, Draft.State.PENDING),
            ChatMessage.User(4, at(old), "old but still working", status = ChatMessage.Status.WORKING),
            ChatMessage.Bot(5, at(today.minusDays(1)), "yesterday"),
            ChatMessage.User(6, at(today), "today"),
        )
        assertEquals(listOf(3L, 4L, 5L, 6L), ChatHistory.prune(list, today, zone).map { it.id })
    }

    @Test fun openCardIsTheLastPendingOneBeforeTheMessage() {
        val list = listOf(card(1, today, Draft.State.PENDING), card(2, today, Draft.State.LOGGED), ChatMessage.User(3, at(today), "make it 6"), card(4, today, Draft.State.PENDING))
        assertEquals(1L, ChatHistory.openCard(list, beforeId = 3)?.id)
        assertNull(ChatHistory.openCard(list, beforeId = 1))
        assertEquals(2L, ChatHistory.lastLoggedCard(list)?.id)
    }

    @Test fun conversationRoundTripsThroughJson() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        val s = ListSerializer(ChatMessage.serializer())
        val list: List<ChatMessage> = listOf(
            ChatMessage.User(1, 10, "5 soaked almonds", photos = listOf("/data/p.jpg"), status = ChatMessage.Status.FAILED, error = "timed out", cardId = 2),
            card(2, today, Draft.State.PENDING),
            ChatMessage.Bot(3, 11, "Saved", error = true),
        )
        assertEquals(list, json.decodeFromString(s, json.encodeToString(s, list)))
    }
}
