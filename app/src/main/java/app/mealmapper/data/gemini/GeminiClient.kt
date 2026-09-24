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
        val request = Request.Builder()
            .url("$BASE/models/${settings.model}:generateContent")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody(JSON))
            .build()
        try {
            http.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) throw GeminiException(errorMessage(response.code, text))
                GeminiParsing.reply(text)
            }
        } catch (e: IOException) {
            throw GeminiException("No connection to Gemini. Check your internet and try again.")
        }
    }

    /** Cheap call that proves the key and model work. Used by the Settings "Test" button. */
    suspend fun test(): String = withContext(Dispatchers.IO) {
        val key = settings.apiKey() ?: throw GeminiException("No key saved.")
        val request = Request.Builder().url("$BASE/models/${settings.model}").header("x-goog-api-key", key).get().build()
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw GeminiException(errorMessage(response.code, response.body.string()))
                "Key works. Model: ${settings.model}"
            }
        } catch (e: IOException) {
            throw GeminiException("No connection to Gemini. Check your internet and try again.")
        }
    }

    private fun errorMessage(code: Int, body: String): String = when (code) {
        400 -> if (body.contains("API key", ignoreCase = true)) "Gemini says the API key is not valid." else "Gemini rejected the request (400)."
        401, 403 -> "Gemini says the API key is not allowed. Check it in Google AI Studio."
        404 -> "Model \"${settings.model}\" not found. Change the model in Settings."
        429 -> "Gemini's free limit is used up for now. Try again in a minute."
        else -> "Gemini error $code. Try again."
    }

    private companion object {
        const val BASE = "https://generativelanguage.googleapis.com/v1beta"
        val JSON = "application/json".toMediaType()
    }
}

