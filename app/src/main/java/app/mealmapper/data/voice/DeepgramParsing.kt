package app.mealmapper.data.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Pure parsing and request building for Deepgram's /v1/listen. Unit-tested without Android. */
object DeepgramParsing {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Words Deepgram may not expect, spelled the way we want them back. Keyterm prompting works with
     * Nova-3 in multilingual mode (docs: "Keyterm Prompting", 24 Sep 2026); limit 500 tokens per request.
     */
    val KEYTERMS = listOf(
        "roti", "phulka", "chapati", "paratha", "thepla", "bhakri", "puri", "naan", "pav", "katori",
        "dal", "sabzi", "gatte", "gatte ki sabzi", "rajma", "chole", "kadhi", "khichdi", "pulao", "biryani",
        "poha", "upma", "sheera", "idli", "dosa", "vada", "uttapam", "dhokla", "misal", "usal", "pav bhaji", "vada pav",
        "paneer", "bhindi", "baingan", "lauki", "karela", "tinda", "methi", "palak", "aloo", "gobi", "matar",
        "dahi", "raita", "chaas", "lassi", "ghee", "besan", "makhana", "chai", "nimbu pani",
        "ladoo", "halwa", "kheer", "barfi", "jalebi", "gulab jamun", "chikki", "chivda", "farsan", "peg",
    )

    const val BASE = "https://api.deepgram.com/v1/listen"

    /** Nova-3 with code-switching (language=multi): Hindi and English mixed in one sentence, no language switch. */
    fun listenUrl(keyterms: List<String> = KEYTERMS): String = buildString {
        append(BASE).append("?model=nova-3&language=multi")
        keyterms.forEach { append("&keyterm=").append(java.net.URLEncoder.encode(it, "UTF-8")) }
    }

    /** The transcript of the first channel's best alternative, or "" when nothing was heard. */
    fun transcript(body: String): String {
        val root = json.parseToJsonElement(body).jsonObject
        val channels = (root["results"] as? JsonObject)?.get("channels") as? JsonArray ?: return ""
        val alt = ((channels.firstOrNull() as? JsonObject)?.get("alternatives") as? JsonArray)?.firstOrNull() as? JsonObject
        return (alt?.get("transcript") as? JsonPrimitive)?.content?.trim().orEmpty()
    }

    /** Deepgram's error text: {"err_code":..,"err_msg":".."} or {"message":".."}. */
    fun errorMessage(body: String): String? = runCatching {
        val o = json.parseToJsonElement(body).jsonObject
        ((o["err_msg"] ?: o["message"] ?: o["reason"]) as? JsonPrimitive)?.content
    }.getOrNull()
}
