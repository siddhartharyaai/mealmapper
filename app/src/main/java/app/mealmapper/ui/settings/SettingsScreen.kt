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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.mealmapper.data.settings.Profile
import app.mealmapper.data.settings.ProfileStore
import app.mealmapper.domain.ProfileRules
import app.mealmapper.ui.common.fmt

@Composable
fun SettingsScreen(store: ProfileStore, onBack: () -> Unit, onHealthCheck: () -> Unit) {
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
