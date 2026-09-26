package app.mealmapper.ui.common

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Box
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
 * The chat bar's mic: tap to talk (Hindi, English or both), tap again to stop. Deepgram Nova-3 multilingual writes
 * it down and the text lands in the message box, where it can be fixed before sending. The recording is deleted.
 */
@Composable
fun VoiceMic(onText: (String) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as MealMapperApp).container
    val hasKey by container.deepgramSettings.hasKey.collectAsStateWithLifecycle()
    val recorder = remember { VoiceRecorder(context) }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(VoiceState.IDLE) }
    var seconds by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) { onDispose { recorder.stopQuietly() } }

    fun startRecording() {
        runCatching { recorder.start() }
            .onSuccess { seconds = 0; state = VoiceState.RECORDING }
            .onFailure { onError("Could not start the microphone.") }
    }

    fun stopAndSend() {
        val file = recorder.stop()
        if (file == null) {
            state = VoiceState.IDLE
            onError("Nothing recorded. Hold the phone closer and try again.")
            return
        }
        state = VoiceState.TRANSCRIBING
        scope.launch {
            try {
                val text = container.deepgram.transcribe(file)
                if (text.isBlank()) onError("No words heard. Try again a little louder.") else onText(text)
            } catch (e: VoiceException) {
                onError(e.message ?: "Voice failed. Try again.")
            } finally {
                file.delete()
                state = VoiceState.IDLE
            }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else onError("Microphone access is needed to speak your meal.")
    }

    LaunchedEffect(state) {
        while (state == VoiceState.RECORDING) {
            delay(1_000)
            seconds++
            if (seconds * 1_000 >= VoiceRecorder.MAX_MS) stopAndSend()
        }
    }

    when (state) {
        VoiceState.RECORDING -> Button(
            onClick = ::stopAndSend,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        ) { Text("■ ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}") }
        VoiceState.TRANSCRIBING -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        }
        VoiceState.IDLE -> TextButton(onClick = {
            when {
                !hasKey -> onError("Add your Deepgram key in Settings to speak your meals.")
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> startRecording()
                else -> permission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }) { Text("🎙", style = MaterialTheme.typography.titleLarge) }
    }
}
