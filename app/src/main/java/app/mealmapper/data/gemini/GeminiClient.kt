package app.mealmapper.data.gemini

import android.util.Base64
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Thin REST client for generateContent (v1beta). The API key goes in a header, never in the URL. */
class GeminiClient(private val settings: GeminiSettings) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // Grounded search plus thinking can take a while on a slow connection.
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    /**
     * @param jpegImages images to send (already compressed)
     * @param webSearch turns on Google Search grounding. Do not combine with JSON response mode:
     *   on gemini-3.5-flash that silently disables the search (google-gemini/cookbook issue 1274).
     * @param jsonResponse asks for application/json output (label reading only).
     */
    suspend fun generate(
        prompt: String,
        jpegImages: List<ByteArray> = emptyList(),
        webSearch: Boolean = false,
        jsonResponse: Boolean = false,
    ): GeminiReply = withContext(Dispatchers.IO) {
        val key = settings.apiKey() ?: throw GeminiException("Add your Gemini API key in Settings first.")
        val body = buildJsonObject {
            putJsonArray("contents") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            jpegImages.forEach { img ->
                                add(
                                    buildJsonObject {
                                        putJsonObject("inline_data") {
                                            put("mime_type", "image/jpeg")
                                            put("data", Base64.encodeToString(img, Base64.NO_WRAP))
                                        }
                                    },
                                )
                            }
                            add(buildJsonObject { put("text", prompt) })
                        }
                    },
                )
            }
            if (webSearch) put("tools", buildJsonArray { add(buildJsonObject { putJsonObject("google_search") {} }) })
            putJsonObject("generationConfig") {
                put("temperature", 0.1)
                if (jsonResponse) put("responseMimeType", "application/json")
            }
        }
        // Try the chosen model, then the fallbacks. A 429 on a free key usually means that model has
        // no free quota (limit 0), not that the user made too many requests.
        val models = (listOf(settings.model) + FALLBACK_MODELS).distinct()
        var lastError: GeminiException? = null
        for (model in models) {
            val request = Request.Builder()
                .url("$BASE/models/$model:generateContent")
                .header("x-goog-api-key", key)
                .post(body.toString().toRequestBody(JSON))
                .build()
            try {
                http.newCall(request).execute().use { response ->
                    val text = response.body.string()
                    if (response.isSuccessful) return@withContext GeminiParsing.reply(text).copy(model = model)
                    lastError = GeminiException(errorMessage(response.code, text, model))
                    if (response.code != 429 && response.code != 404) throw lastError!!
                }
            } catch (e: IOException) {
                throw GeminiException("No connection to Gemini. Check your internet and try again.")
            }
        }
        throw lastError ?: GeminiException("Gemini did not answer.")
    }

    /**
     * A real (tiny) request, so quota problems show up here and not in the middle of a lookup.
     * Uses web search too, because that is what product lookups need.
     */
    suspend fun test(): String {
        val reply = generate("Reply with the single word OK.", webSearch = true)
        return "Key works with web search. Model: ${reply.model}"
    }

    /** Google's own reason, shortened, so the user (and developer) can see what actually failed. */
    private fun errorMessage(code: Int, body: String, model: String): String {
        val google = GeminiParsing.errorMessage(body)?.take(220)
        val plain = when (code) {
            400 -> if (body.contains("API key", ignoreCase = true)) "The API key is not valid." else "Gemini rejected the request."
            401, 403 -> "The API key is not allowed. Check it in Google AI Studio."
            404 -> "Model \"$model\" is not available to this key."
            429 -> "No quota left for \"$model\" on this key (free keys have 0 for some models)."
            else -> "Gemini error $code."
        }
        return if (google != null) "$plain\nGoogle says: $google" else plain
    }

    private companion object {
        const val BASE = "https://generativelanguage.googleapis.com/v1beta"
        /** Flash-Lite has the largest free daily quota (about 500/day in September 2026). */
        val FALLBACK_MODELS = listOf("gemini-3.1-flash-lite", "gemini-3.5-flash-lite")
        val JSON = "application/json".toMediaType()
    }
}

