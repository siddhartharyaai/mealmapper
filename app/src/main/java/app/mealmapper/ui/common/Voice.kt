package app.mealmapper.ui.common

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp

/** Speech languages offered. English (India) copes with Hinglish food words; Hindi returns Devanagari, which Gemini reads. */
enum class VoiceLanguage(val tag: String, val label: String) {
    ENGLISH_INDIA("en-IN", "English (India)"),
    HINDI("hi-IN", "हिंदी"),
}

/**
 * Android's own speech recognizer (the Google app on most phones): free, no key, no audio stored by Meal Mapper.
 * The recognizer shows its own listening screen and asks for the microphone itself, so the app needs no
 * RECORD_AUDIO permission. The words land in the text field, where the user can fix them before estimating.
 */
@Composable
fun VoiceInput(onText: (String) -> Unit, onUnavailable: () -> Unit, prompt: String = "Say what you ate, with amounts") {
    var language by rememberSaveable { mutableStateOf(VoiceLanguage.ENGLISH_INDIA) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (result.resultCode == Activity.RESULT_OK && !text.isNullOrBlank()) onText(text)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.tag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language.tag)
                putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
            }
            try {
                launcher.launch(intent)
            } catch (e: ActivityNotFoundException) {
                onUnavailable()
            }
        }) { Text("Speak") }
        VoiceLanguage.entries.forEach { l ->
            FilterChip(selected = language == l, onClick = { language = l }, label = { Text(l.label) })
        }
    }
}
