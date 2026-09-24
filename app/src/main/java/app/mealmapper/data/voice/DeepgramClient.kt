package app.mealmapper.data.voice

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody

class VoiceException(message: String) : Exception(message)

/**
 * Deepgram pre-recorded transcription (POST /v1/listen), as documented on developers.deepgram.com on 24 Sep 2026:
 * header "Authorization: Token <key>", audio bytes as the body. Nova-3 multilingual pre-recorded costs
 * $0.0052/min plus $0.0013/min for keyterms (pricing page); new accounts get $200 credit.
 */
class DeepgramClient(private val settings: DeepgramSettings) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun transcribe(audio: File, mimeType: String = "audio/mp4"): String = withContext(Dispatchers.IO) {
        val key = settings.apiKey() ?: throw VoiceException("Add your Deepgram API key in Settings first.")
        val request = Request.Builder()
            .url(DeepgramParsing.listenUrl())
            .header("Authorization", "Token $key")
            .post(audio.asRequestBody(mimeType.toMediaType()))
            .build()
        try {
            http.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) throw VoiceException(message(response.code, body))
                DeepgramParsing.transcript(body)
            }
        } catch (e: IOException) {
            throw VoiceException("No connection to Deepgram. Check your internet and try again.")
        }
    }

    /** Checks the key with GET /v1/projects (lists the key's projects; no audio, no cost). */
    suspend fun test(): String = withContext(Dispatchers.IO) {
        val key = settings.apiKey() ?: return@withContext "No key saved."
        val request = Request.Builder().url("https://api.deepgram.com/v1/projects").header("Authorization", "Token $key").get().build()
        try {
            http.newCall(request).execute().use { r ->
                if (r.isSuccessful) "Ready. Deepgram accepted the key. Speak a meal to try it." else message(r.code, r.body.string())
            }
        } catch (e: IOException) {
            "No connection to Deepgram. Check your internet and try again."
        }
    }

    private fun message(code: Int, body: String): String {
        val plain = when (code) {
            401, 403 -> "Deepgram did not accept the key. Check it in Settings."
            402 -> "Deepgram credit is used up. Add credit in the Deepgram console."
            429 -> "Deepgram's rate limit is reached. Try again in a minute."
            else -> "Deepgram error $code."
        }
        return DeepgramParsing.errorMessage(body)?.let { "$plain\nDeepgram says: ${it.take(200)}" } ?: plain
    }
}
