package app.mealmapper.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import app.mealmapper.data.ai.AiSettings
import app.mealmapper.data.ai.GeminiClient
import app.mealmapper.data.settings.Profile
import app.mealmapper.data.settings.ProfileStore
import app.mealmapper.data.voice.DeepgramClient
import app.mealmapper.data.voice.DeepgramSettings
import app.mealmapper.domain.ProfileRules
import app.mealmapper.ui.common.fmt

@Composable
fun SettingsScreen(
    store: ProfileStore,
    ai: AiSettings,
    gemini: GeminiClient,
    voice: DeepgramSettings,
    deepgram: DeepgramClient,
    onBack: () -> Unit,
    onHealthCheck: () -> Unit,
) {
    val saved = store.profile.value
    var age by rememberSaveable { mutableStateOf(saved.ageYears?.toString().orEmpty()) }
    var weight by rememberSaveable { mutableStateOf(saved.weightKg?.fmt().orEmpty()) }
    var cap by rememberSaveable { mutableStateOf(saved.dailyCapKcal?.toString().orEmpty()) }
    var katori by rememberSaveable { mutableStateOf(saved.katoriMl?.toString().orEmpty()) }
    var oil by rememberSaveable { mutableStateOf(saved.oilLitresPerMonth?.fmt().orEmpty()) }
    var people by rememberSaveable { mutableStateOf(saved.peopleAtHome?.toString().orEmpty()) }
    var done by rememberSaveable { mutableStateOf(false) }

    val ageValue = age.toIntOrNull()
    val weightValue = weight.replace(',', '.').toDoubleOrNull()
    val capValue = cap.toIntOrNull()
    val ageError = ProfileRules.ageError(ageValue)
    val weightError = ProfileRules.weightError(weightValue)
    val capError = ProfileRules.capError(capValue)
    val katoriValue = katori.toIntOrNull()
    val oilValue = oil.replace(',', '.').toDoubleOrNull()
    val peopleValue = people.toIntOrNull()
    val katoriError = katoriValue?.takeIf { it !in 50..500 }?.let { "Between 50 and 500 ml." }
    val oilError = oilValue?.takeIf { it <= 0 || it > 30 }?.let { "Between 0.1 and 30 litres." }
    val peopleError = peopleValue?.takeIf { it !in 1..20 }?.let { "Between 1 and 20." }
    val valid = ageError == null && weightError == null && capError == null &&
        katoriError == null && oilError == null && peopleError == null

    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("Back") }
                Text("Settings", style = MaterialTheme.typography.titleLarge)
            }

            Text("You", style = MaterialTheme.typography.titleMedium)
            NumberField("Age", age, "years", ageError) { age = it.filter(Char::isDigit).take(3); done = false }
            NumberField("Weight", weight, "kg", weightError, decimal = true) {
                weight = it.filter { c -> c.isDigit() || c == '.' }.take(5); done = false
            }
            NumberField(
                "Daily calorie cap",
                cap,
                "kcal",
                capError ?: "The most you want to eat in a day. Drives the ticker on Home.",
                isError = capError != null,
            ) { cap = it.filter(Char::isDigit).take(4); done = false }

            Text("Your kitchen", style = MaterialTheme.typography.titleMedium)
            Text(
                "Makes meal estimates fit your home. Measure your katori once with a measuring cup of water.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            NumberField("Katori size", katori, "ml", katoriError ?: "Blank = 150 ml.", isError = katoriError != null) {
                katori = it.filter(Char::isDigit).take(3); done = false
            }
            NumberField(
                "Oil + ghee bought per month",
                oil,
                "litres",
                oilError ?: "All cooking oil and ghee together, from your monthly shopping.",
                isError = oilError != null,
                decimal = true,
            ) { oil = it.filter { c -> c.isDigit() || c == '.' }.take(4); done = false }
            NumberField("People eating at home", people, "people", peopleError, isError = peopleError != null) {
                people = it.filter(Char::isDigit).take(2); done = false
            }
            val perDay = Profile(oilLitresPerMonth = oilValue, peopleAtHome = peopleValue).oilGramsPerPersonDay
            if (perDay != null) {
                Text(
                    "≈ ${perDay.fmt()} g oil and ghee per person per day. Meal estimates use this for home food.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Button(
                onClick = {
                    store.save(Profile(ageValue, weightValue, capValue, katoriValue, oilValue, peopleValue))
                    done = true
                },
                enabled = valid,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (done) "Saved" else "Save") }
            Text(
                "Age and weight will be used for menu suggestions later. Everything stays on this phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            AiSection(ai, gemini)

            HorizontalDivider()
            VoiceSection(voice, deepgram)

            HorizontalDivider()
            Text("Health Connect", style = MaterialTheme.typography.titleMedium)
            Text(
                "Check access, and write a test entry to confirm it reaches Google Health and Samsung Health.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onHealthCheck, modifier = Modifier.fillMaxWidth()) { Text("Health Connect check") }
        }
    }
}

@Composable
private fun VoiceSection(settings: DeepgramSettings, client: DeepgramClient) {
    val hasKey by settings.hasKey.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var keyInput by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun test() {
        busy = true
        status = "Checking the key…"
        scope.launch {
            status = client.test()
            busy = false
        }
    }

    Text("Voice: Deepgram Nova-3", style = MaterialTheme.typography.titleMedium)
    Text(
        "Speak meals in Hindi, English or both in one sentence. About ₹0.55 per minute of speech; new Deepgram " +
            "accounts get \$200 free credit. Audio goes to Deepgram only when you tap Speak.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (hasKey) {
        Text("API key saved and encrypted on this phone.", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = ::test, enabled = !busy) { Text("Test key") }
            TextButton(onClick = {
                settings.clearKey()
                status = "Key removed."
            }) { Text("Remove key") }
        }
    } else {
        Text(
            "Create a key at console.deepgram.com → API Keys (role Member is enough). Paste it here, never in a chat.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = keyInput,
            onValueChange = { keyInput = it.trim() },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Deepgram API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Button(
            onClick = {
                settings.saveKey(keyInput)
                keyInput = ""
                test()
            },
            enabled = keyInput.length >= 20,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save key") }
    }
    status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    unit: String,
    help: String?,
    isError: Boolean = help != null,
    decimal: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        suffix = { Text(unit) },
        singleLine = true,
        isError = isError,
        supportingText = if (help != null) {
            { Text(help) }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
    )
}

/** Gemini API key (encrypted on the phone) and the model name. */
@Composable
private fun AiSection(ai: AiSettings, client: GeminiClient) {
    val hasKey by ai.hasKey.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var keyInput by remember { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf(ai.model) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun test() {
        busy = true
        status = "Testing Gemini and Google Search… (up to 30 seconds)"
        scope.launch {
            status = runCatching { client.test() }.getOrElse { it.message ?: "Test failed." }
            model = ai.model
            busy = false
        }
    }

    Text("AI: Gemini (reads labels, finds products online)", style = MaterialTheme.typography.titleMedium)
    if (hasKey) {
        Text("API key saved and encrypted on this phone.", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = ::test, enabled = !busy) { Text("Test key") }
            TextButton(onClick = {
                ai.clearKey()
                status = "Key removed."
            }) { Text("Remove key") }
        }
    } else {
        Text(
            "Create a key at aistudio.google.com → Get API key, in a project with billing on " +
                "(web search is not in Gemini's free tier). Paste it here, never in a chat.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = keyInput,
            onValueChange = { keyInput = it.trim() },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Gemini API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Button(
            onClick = {
                ai.saveKey(keyInput)
                keyInput = ""
                test()
            },
            enabled = keyInput.length >= 20,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save key") }
    }
    OutlinedTextField(
        value = model,
        onValueChange = { model = it.trim() },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Model") },
        supportingText = { Text("Leave as is. If Google retires it, the app switches to the next model itself.") },
        singleLine = true,
    )
    if (model != ai.model) {
        TextButton(onClick = {
            ai.model = model
            model = ai.model
            status = "Model saved."
        }) { Text("Use this model") }
    }
    status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
}
