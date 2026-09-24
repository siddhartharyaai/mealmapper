package app.mealmapper.ui.common

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mealmapper.MealMapperApp
import app.mealmapper.data.voice.VoiceException
import app.mealmapper.data.voice.VoiceRecorder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class VoiceState { IDLE, RECORDING, TRANSCRIBING }

/**
 * Speak in Hindi, English or both in one sentence ("gatte ki sabzi with two wheat rotis"): no language switch.
 * Tap to start, tap to stop; Deepgram Nova-3 (multilingual) writes it down; the text lands in the field,
 * where it can be fixed before estimating. The recording is deleted after sending.
 */
@Composable
fun VoiceInput(onText: (String) -> Unit, onUnavailable: (String) -> Unit, prompt: String = "Speak") {
    val context = LocalContext.current
    val container = (context.applicationContext as MealMapperApp).container
    val hasKey by container.deepgramSettings.hasKey.collectAsStateWithLifecycle()
    val recorder = remember { VoiceRecorder(context) }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(VoiceState.IDLE) }
    var seconds by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) { onDispose { recorder.stopQuietly() } }

    fun startRecording() {
        error = null
        runCatching { recorder.start() }
            .onSuccess { seconds = 0; state = VoiceState.RECORDING }
            .onFailure { error = "Could not start the microphone." }
    }

    fun stopAndSend() {
        val file = recorder.stop()
        if (file == null) {
            state = VoiceState.IDLE
            error = "Nothing recorded. Hold the phone closer and try again."
            return
        }
        state = VoiceState.TRANSCRIBING
        scope.launch {
            try {
                val text = container.deepgram.transcribe(file)
                if (text.isBlank()) error = "Deepgram heard no words. Try again a little louder." else onText(text)
            } catch (e: VoiceException) {
                error = e.message
            } finally {
                file.delete()
                state = VoiceState.IDLE
            }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else onUnavailable("Microphone access is needed to speak your meal.")
    }

    LaunchedEffect(state) {
        while (state == VoiceState.RECORDING) {
            delay(1_000)
            seconds++
            if (seconds * 1_000 >= VoiceRecorder.MAX_MS) stopAndSend()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
            !hasKey -> Text(
                "Voice: add your Deepgram API key in Settings to speak meals in Hindi, English or both.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state == VoiceState.RECORDING -> Button(
                onClick = ::stopAndSend,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text("■  Stop  ·  0:${seconds.toString().padStart(2, '0')}") }
            state == VoiceState.TRANSCRIBING -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Writing it down…")
            }
            else -> OutlinedButton(
                onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        startRecording()
                    } else {
                        permission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("🎙  $prompt  ·  Hindi, English or both") }
        }
        if (state == VoiceState.RECORDING) {
            Text("Listening… tap Stop when done.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}
