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

    /** Reads images. Falls back through [VISION_MODELS] if the chosen model is not on this key. */
    suspend fun vision(prompt: String, jpegs: List<ByteArray>): AiReply =
        withFallback(settings.visionModel, VISION_MODELS, { settings.visionModel = it }) { chat(it, prompt, jpegs) }

    /** Searches the web. Falls back through [WEB_MODELS] if the chosen model is not on this key. */
    suspend fun webSearch(prompt: String): AiReply =
        withFallback(settings.webModel, WEB_MODELS, { settings.webModel = it }) { chat(it, prompt, emptyList(), search = true) }

    /**
     * Tries the saved model, then the known alternatives. Only "this model is not usable on this key"
     * errors move to the next model; anything else (bad key, no connection, rate limit) stops at once.
     * The first model that works is saved, so the next call goes straight to it.
     */
    private suspend fun withFallback(
        saved: String,
        candidates: List<String>,
        remember: (String) -> Unit,
        call: suspend (String) -> AiReply,
    ): AiReply {
        var last: ModelUnavailable? = null
        for (model in (listOf(saved) + candidates).distinct()) {
            try {
                return call(model).also { if (model != saved) remember(model) }
            } catch (e: ModelUnavailable) {
                last = e
            }
        }
        throw AiException(last?.message ?: "No usable model on this Groq key.")
    }

    private suspend fun chat(model: String, prompt: String, jpegs: List<ByteArray>, search: Boolean = false): AiReply =
        withContext(Dispatchers.IO) {
        val key = key()
        val body = buildJsonObject {
            put("model", model)
            put("temperature", 0.1)
            // gpt-oss models search with Groq's built-in browser_search tool (docs, 24 Sep 2026).
            // Groq recommends low reasoning effort with it: faster, fewer tokens, same quality for lookups.
            if (search && model.startsWith("openai/gpt-oss")) {
                putJsonArray("tools") { add(buildJsonObject { put("type", "browser_search") }) }
                put("tool_choice", "required")
                put("reasoning_effort", "low")
            }
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
     * Sets itself up: reads the models this key has, picks the first usable one for each job, saves it,
     * then proves each with a real (tiny) request. Nothing is assumed from documentation.
     */
    suspend fun test(): String {
        val available = listModels()
        fun pick(saved: String, candidates: List<String>) =
            (listOf(saved) + candidates).distinct().firstOrNull { it in available }
        val vision = pick(settings.visionModel, VISION_MODELS)
        val web = pick(settings.webModel, WEB_MODELS)
        if (vision == null || web == null) {
            return "Key works, but it has no " + listOfNotNull(
                "image model".takeIf { vision == null },
                "web search model".takeIf { web == null },
            ).joinToString(" and ") + ". Models on your key: ${available.sorted().joinToString()}."
        }
        settings.visionModel = vision
        settings.webModel = web
        // Real calls: a model can be listed and still refuse requests (tier, org settings).
        chat(vision, "Reply with the word OK.", emptyList())
        chat(web, "Reply with the word OK.", emptyList())
        return "Ready. Image model: $vision · Web search: $web"
    }

    private suspend fun listModels(): Set<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$BASE/models").header("Authorization", "Bearer ${key()}").get().build()
        execute(request, "") { body ->
            val data = Json.parseToJsonElement(body).jsonObject["data"] as? JsonArray
            data.orEmpty().mapNotNull { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.content }.toSet()
        }
    }

    private fun key(): String = settings.apiKey() ?: throw AiException("Add your Groq API key in Settings first.")

    private fun <T> execute(request: Request, model: String, parse: (String) -> T): T = try {
        http.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                val message = errorMessage(response.code, text, model)
                // 404 / "model not found" / "not available on your plan": try the next model.
                if (model.isNotEmpty() && isModelUnavailable(response.code, text)) throw ModelUnavailable(message)
                throw AiException(message)
            }
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

    private fun isModelUnavailable(code: Int, body: String): Boolean {
        if (code == 404) return true
        val m = (AiParsing.errorMessage(body) ?: body).lowercase()
        return (code == 400 || code == 403) &&
            (m.contains("model") && (m.contains("not found") || m.contains("does not exist") ||
                m.contains("decommissioned") || m.contains("not available") || m.contains("access") ||
                m.contains("vision") || m.contains("image") || m.contains("tool")))
    }

    private class ModelUnavailable(message: String) : Exception(message)

    companion object {
        /**
         * Read from console.groq.com/docs/models on 24 September 2026: qwen3.8-27b is the only image model
         * (preview). Llama 4 entries stay as fallbacks for keys on older plans.
         */
        val VISION_MODELS = listOf(
            "qwen/qwen3.8-27b",
            "meta-llama/llama-4-scout-17b-16e-instruct",
            "meta-llama/llama-4-maverick-17b-128e-instruct",
        )

        /** Models with Groq's browser_search tool (production). groq/compound is no longer offered. */
        val WEB_MODELS = listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b")

        private const val BASE = "https://api.groq.com/openai/v1"
        private val JSON = "application/json".toMediaType()
    }
}
