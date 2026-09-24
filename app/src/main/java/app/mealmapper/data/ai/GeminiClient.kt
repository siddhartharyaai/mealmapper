package app.mealmapper.data.ai

import android.util.Base64
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Gemini Interactions API (POST /v1beta/interactions), as documented on ai.google.dev on 24 Sep 2026.
 * generateContent is marked "Legacy" there. The key goes in the x-goog-api-key header, never the URL.
 *
 * Google Search grounding is NOT available on the free tier for any Gemini 3.x model (pricing page);
 * the project must have billing on. Paid tier includes 5,000 free search requests per month.
 */
class GeminiClient(private val settings: AiSettings) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // Search plus thinking can take a while on mobile data.
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    /** Reads images (labels, pack fronts). */
    suspend fun vision(prompt: String, jpegs: List<ByteArray>): AiReply =
        withFallback { model -> interact(model, prompt, jpegs, search = false) }

    /** Answers with Google Search. Sources come back as url_citation annotations. */
    suspend fun webSearch(prompt: String): AiReply =
        withFallback { model -> interact(model, prompt, emptyList(), search = true) }

    /**
     * Proves the key, billing and search in one real request. Search is the feature that needs billing,
     * so it is what we test; a plain request could pass while every lookup fails.
     */
    suspend fun test(): String {
        val reply = webSearch("Use Google Search: what is the capital of India? Answer in one word.")
        return if (reply.sites.isNotEmpty()) {
            "Ready. Model: ${reply.model} · Google Search works (sources: ${reply.sites.take(2).joinToString()})."
        } else {
            "The key works, but Google Search did not run. Check that billing is on for this project in AI Studio."
        }
    }

    /** Only "model not available" errors move to the next model; the first model that works is saved. */
    private suspend fun withFallback(call: suspend (String) -> AiReply): AiReply {
        var last: ModelUnavailable? = null
        val saved = settings.model
        for (model in (listOf(saved) + MODELS).distinct()) {
            try {
                return call(model).also { if (model != saved) settings.model = model }
            } catch (e: ModelUnavailable) {
                last = e
            }
        }
        throw AiException(last?.message ?: "No usable Gemini model on this key.")
    }

    private suspend fun interact(model: String, prompt: String, jpegs: List<ByteArray>, search: Boolean): AiReply =
        withContext(Dispatchers.IO) {
            val key = settings.apiKey() ?: throw AiException("Add your Gemini API key in Settings first.")
            val body = buildJsonObject {
                put("model", model)
                if (jpegs.isEmpty()) {
                    put("input", prompt)
                } else {
                    putJsonArray("input") {
                        add(buildJsonObject { put("type", "text"); put("text", prompt) })
                        jpegs.forEach { img ->
                            add(
                                buildJsonObject {
                                    put("type", "image")
                                    put("data", Base64.encodeToString(img, Base64.NO_WRAP))
                                    put("mime_type", "image/jpeg")
                                },
                            )
                        }
                    }
                }
                if (search) put("tools", buildJsonArray { add(buildJsonObject { put("type", "google_search") }) })
            }
            val request = Request.Builder()
                .url("$BASE/interactions")
                .header("x-goog-api-key", key)
                .post(body.toString().toRequestBody(JSON))
                .build()
            try {
                http.newCall(request).execute().use { response ->
                    val text = response.body.string()
                    if (!response.isSuccessful) {
                        val message = errorMessage(response.code, text, model, search)
                        if (response.code == 404) throw ModelUnavailable(message)
                        throw AiException(message)
                    }
                    AiParsing.interaction(text).copy(model = model)
                }
            } catch (e: IOException) {
                throw AiException("No connection to Gemini. Check your internet and try again.")
            }
        }

    /** Google's own reason, shortened, plus what it most likely means for this app. */
    private fun errorMessage(code: Int, body: String, model: String, search: Boolean): String {
        val google = AiParsing.errorMessage(body)?.take(220)
        val plain = when (code) {
            400 -> if (body.contains("API key", ignoreCase = true)) "The Gemini API key is not valid." else "Gemini rejected the request."
            401, 403 -> "This Gemini key is not allowed. Check it in Google AI Studio."
            404 -> "Model \"$model\" is not available on this key."
            429 -> if (search) {
                "Google Search quota reached, or billing is not on for this project (free keys have no search)."
            } else {
                "Gemini's rate limit is reached. Wait a minute and try again."
            }
            else -> "Gemini error $code."
        }
        return if (google != null) "$plain\nGoogle says: $google" else plain
    }

    private class ModelUnavailable(message: String) : Exception(message)

    companion object {
        /** Stable models from ai.google.dev/gemini-api/docs/models (24 Sep 2026), best first. */
        val MODELS = listOf("gemini-3.8-flash", "gemini-3.7-flash", "gemini-3.6-flash", "gemini-3.5-flash-lite")
        private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
        private val JSON = "application/json".toMediaType()
    }
}
