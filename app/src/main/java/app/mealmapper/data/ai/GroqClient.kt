package app.mealmapper.data.ai

import android.util.Base64
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Groq's OpenAI-compatible chat completions API. The key goes in the Authorization header only. */
class GroqClient(private val settings: AiSettings) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // Web search runs several searches before answering.
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    /** Reads images with the vision model. */
    suspend fun vision(prompt: String, jpegs: List<ByteArray>): AiReply = chat(settings.visionModel, prompt, jpegs)

    /** Searches the web with the compound system (text only). */
    suspend fun webSearch(prompt: String): AiReply = chat(settings.webModel, prompt, emptyList())

    private suspend fun chat(model: String, prompt: String, jpegs: List<ByteArray>): AiReply = withContext(Dispatchers.IO) {
        val key = key()
        val body = buildJsonObject {
            put("model", model)
            put("temperature", 0.1)
            putJsonArray("messages") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        if (jpegs.isEmpty()) {
                            put("content", prompt)
                        } else {
                            putJsonArray("content") {
                                add(buildJsonObject { put("type", "text"); put("text", prompt) })
                                jpegs.forEach { img ->
                                    add(
                                        buildJsonObject {
                                            put("type", "image_url")
                                            putJsonObject("image_url") {
                                                put("url", "data:image/jpeg;base64," + Base64.encodeToString(img, Base64.NO_WRAP))
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
            }
        }
        val request = Request.Builder()
            .url("$BASE/chat/completions")
            .header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody(JSON))
            .build()
        execute(request, model) { AiParsing.reply(it) }
    }

    /**
     * Checks the key and that both models exist for it. Uses the free models list: no tokens spent.
     */
    suspend fun test(): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$BASE/models").header("Authorization", "Bearer ${key()}").get().build()
        val available = execute(request, "") { body ->
            val data = Json.parseToJsonElement(body).jsonObject["data"] as? JsonArray
            data.orEmpty().mapNotNull { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.content }.toSet()
        }
        val missing = listOf(settings.visionModel, settings.webModel).filterNot { it in available }
        if (missing.isEmpty()) {
            "Key works. Vision: ${settings.visionModel} · Web: ${settings.webModel}"
        } else {
            val visionLike = available.filter { it.contains("qwen", true) || it.contains("vision", true) || it.contains("llama-4", true) }
            "Key works, but not available on it: ${missing.joinToString()}. " +
                "Models on your key that may read images: ${visionLike.take(5).joinToString().ifEmpty { "none found" }}."
        }
    }

    private fun key(): String = settings.apiKey() ?: throw AiException("Add your Groq API key in Settings first.")

    private fun <T> execute(request: Request, model: String, parse: (String) -> T): T = try {
        http.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw AiException(errorMessage(response.code, text, model))
            parse(text)
        }
    } catch (e: IOException) {
        throw AiException("No connection to Groq. Check your internet and try again.")
    }

    /** Groq's own reason, shortened, so the user (and developer) can see what actually failed. */
    private fun errorMessage(code: Int, body: String, model: String): String {
        val groq = AiParsing.errorMessage(body)?.take(220)
        val plain = when (code) {
            401 -> "The Groq API key is not valid."
            403 -> "This Groq key is not allowed to use \"$model\"."
            404 -> "Model \"$model\" is not available. Change it in Settings."
            413 -> "The photo is too large for Groq."
            429 -> "Groq's rate limit for \"$model\" is reached. Wait a minute and try again."
            else -> "Groq error $code."
        }
        return if (groq != null) "$plain\nGroq says: $groq" else plain
    }

    private companion object {
        const val BASE = "https://api.groq.com/openai/v1"
        val JSON = "application/json".toMediaType()
    }
}
