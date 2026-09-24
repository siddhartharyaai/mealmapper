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
import app.mealmapper.data.gemini.GeminiClient
import app.mealmapper.data.gemini.GeminiSettings
import app.mealmapper.data.settings.Profile
import app.mealmapper.data.settings.ProfileStore
import app.mealmapper.domain.ProfileRules
import app.mealmapper.ui.common.fmt

@Composable
fun SettingsScreen(
    store: ProfileStore,
    gemini: GeminiSettings,
    geminiClient: GeminiClient,
    onBack: () -> Unit,
    onHealthCheck: () -> Unit,
) {
    val saved = store.profile.value
    var age by rememberSaveable { mutableStateOf(saved.ageYears?.toString().orEmpty()) }
    var weight by rememberSaveable { mutableStateOf(saved.weightKg?.fmt().orEmpty()) }
    var cap by rememberSaveable { mutableStateOf(saved.dailyCapKcal?.toString().orEmpty()) }
    var done by rememberSaveable { mutableStateOf(false) }

    val ageValue = age.toIntOrNull()
    val weightValue = weight.replace(',', '.').toDoubleOrNull()
    val capValue = cap.toIntOrNull()
    val ageError = ProfileRules.ageError(ageValue)
    val weightError = ProfileRules.weightError(weightValue)
    val capError = ProfileRules.capError(capValue)
    val valid = ageError == null && weightError == null && capError == null

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

            Button(
                onClick = {
                    store.save(Profile(ageValue, weightValue, capValue))
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
            GeminiSection(gemini, geminiClient)

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

/** Gemini API key (encrypted on the phone) and model name. */
@Composable
private fun GeminiSection(gemini: GeminiSettings, client: GeminiClient) {
    val hasKey by gemini.hasKey.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var keyInput by remember { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf(gemini.model) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun test() {
        busy = true
        status = "Testing…"
        scope.launch {
            status = runCatching { client.test() }.getOrElse { it.message ?: "Test failed." }
            busy = false
        }
    }

    Text("Gemini (reads labels, finds products online)", style = MaterialTheme.typography.titleMedium)
    if (hasKey) {
        Text("API key saved and encrypted on this phone.", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = ::test, enabled = !busy) { Text("Test key") }
            TextButton(onClick = {
                gemini.clearKey()
                status = "Key removed."
            }) { Text("Remove key") }
        }
    } else {
        Text(
            "Get a free key at aistudio.google.com → Get API key. Paste it here, never in a chat.",
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
                gemini.saveKey(keyInput)
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
        supportingText = { Text("Leave as is. Change only if Google retires it.") },
        singleLine = true,
    )
    if (model != gemini.model) {
        TextButton(onClick = {
            gemini.model = model
            model = gemini.model
            status = "Model set to ${gemini.model}."
        }) { Text("Use this model") }
    }
    status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
}
