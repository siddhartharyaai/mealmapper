package app.mealmapper.data.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/** Records the microphone to a small AAC file (16 kHz mono), which Deepgram accepts as audio/mp4. */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null

    fun start() {
        stopQuietly()
        val out = File(context.cacheDir, "voice-${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioChannels(1)
        r.setAudioSamplingRate(16_000)
        r.setAudioEncodingBitRate(32_000)
        r.setMaxDuration(MAX_MS)
        r.setOutputFile(out.absolutePath)
        r.prepare()
        r.start()
        recorder = r
        file = out
    }

    /** Stops and returns the recording, or null if it was too short to contain speech. */
    fun stop(): File? {
        val r = recorder ?: return null
        val out = file
        recorder = null
        file = null
        val ok = runCatching { r.stop() }.isSuccess // stop() throws when nothing was recorded
        r.release()
        return out?.takeIf { ok && it.length() > 1_000 }
    }

    fun stopQuietly() {
        recorder?.let { runCatching { it.stop() }; it.release() }
        recorder = null
        file?.delete()
        file = null
    }

    companion object {
        const val MAX_MS = 180_000 // 3 minutes: enough to narrate a whole meal
    }
}
