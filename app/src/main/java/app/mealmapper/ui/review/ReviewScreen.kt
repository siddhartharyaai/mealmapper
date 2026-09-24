package app.mealmapper.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mealmapper.domain.MealSlot
import app.mealmapper.domain.Nutrients
import app.mealmapper.domain.ProductSource
import app.mealmapper.domain.isIndianBarcode
import app.mealmapper.domain.portionOptions
import app.mealmapper.ui.common.fmt
import app.mealmapper.ui.common.kcal
import app.mealmapper.ui.theme.tabular

@Composable
fun ReviewScreen(
    viewModel: ReviewViewModel,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    onPhotographLabel: (barcode: String?, name: String?) -> Unit,
    onSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state) {
        (state as? ReviewState.Saved)?.let { onSaved(it.message) }
    }

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
                Text("Review", style = MaterialTheme.typography.titleLarge)
            }
            when (val s = state) {
                is ReviewState.Loading -> Loading(s.message)
                is ReviewState.Saved -> Loading("Saving…")
                is ReviewState.Failed -> Failed(s.message, onRetry = viewModel::start, onBack = onBack)
                is ReviewState.NotFound -> NotFound(
                    s,
                    onWeb = viewModel::searchWeb,
                    onLabel = { onPhotographLabel(s.barcode, s.productName) },
                    onManual = viewModel::enterManually,
                    onSettings = onSettings,
                    onBack = onBack,
                )
                is ReviewState.Ready -> Form(s.form, viewModel)
            }
        }
    }
}

@Composable
private fun Loading(message: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator()
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Failed(message: String, onRetry: () -> Unit, onBack: () -> Unit) {
    Text(message, style = MaterialTheme.typography.bodyLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onRetry) { Text("Try again") }
        OutlinedButton(onClick = onBack) { Text("Back") }
    }
}

@Composable
private fun NotFound(
    state: ReviewState.NotFound,
    onWeb: () -> Unit,
    onLabel: () -> Unit,
    onManual: () -> Unit,
    onSettings: () -> Unit,
    onBack: () -> Unit,
) {
    Text(state.productName ?: "Not found", style = MaterialTheme.typography.headlineSmall)
    Text(
        buildString {
            append(state.reason)
            state.barcode?.takeIf { it.isNotBlank() }?.let {
                append(" Barcode $it.")
                if (isIndianBarcode(it) && !state.webTried) append(" Many Indian products are missing from Open Food Facts.")
            }
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = onLabel, modifier = Modifier.fillMaxWidth()) { Text("Photograph the label (most accurate)") }
    when {
        !state.canSearchWeb -> OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) {
            Text("Add a Groq key to search the web")
        }
        else -> OutlinedButton(onClick = onWeb, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.webTried) "Search the web again" else "Find it online")
        }
    }
    OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth()) { Text("Type the label values") }
    TextButton(onClick = onBack) { Text("Back") }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Form(form: ReviewForm, vm: ReviewViewModel) {
    val unit = form.product.basis.unit
    val portion = form.portion

    OutlinedTextField(
        value = form.name,
        onValueChange = vm::setName,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Food") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
    )
    Text(
        "${form.source.label} · values per 100 $unit",
        style = MaterialTheme.typography.bodySmall,
        color = if (form.source is ProductSource.Web && (form.source as ProductSource.Web).agreeing < 2) {
            MaterialTheme.colorScheme.tertiary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
    if (form.problems.isNotEmpty()) {
        Warning("Check these against the pack: " + form.problems.joinToString(" "))
    }

    Section("How much")
    if (!form.isManual) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            portionOptions(form.product).forEach { option ->
                FilterChip(
                    selected = form.amount == option.amount,
                    onClick = { vm.setAmount(option.amount.fmt()) },
                    label = { Text(option.label) },
                )
            }
        }
    }
    OutlinedTextField(
        value = form.amountText,
        onValueChange = vm::setAmount,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Amount") },
        suffix = { Text(unit) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )

    form.amountFromNote?.let {
        Text(
            "From your note: $it",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    if (form.amountFromNote == null && form.note.isNotBlank() && !form.isManual) {
        Text(
            "Your note did not give an amount in g, ml, pack or servings. Set the amount above.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Totals(portion)
    if (form.per100.energyLooksWrong()) {
        Warning("Calories do not match protein, carbs and fat on this label. Check the values below.")
    }

    Section("Meal")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MealSlot.entries.forEach { slot ->
            FilterChip(
                selected = form.slot == slot,
                onClick = { vm.setSlot(slot) },
                label = { Text(slot.name.lowercase().replaceFirstChar(Char::uppercase)) },
            )
        }
    }

    OutlinedTextField(
        value = form.note,
        onValueChange = vm::setNote,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Note (optional)") },
        placeholder = { Text("half pack, with chai…") },
        supportingText = { Text("Shows next to the name in Google Health.") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
    )

    TextButton(onClick = vm::toggleEditLabel) {
        Text(if (form.editingLabel) "Hide label values" else "Check label values (per 100 $unit)")
    }
    if (form.editingLabel) {
        NutrientField.entries.forEach { field ->
            OutlinedTextField(
                value = form.per100Text[field].orEmpty(),
                onValueChange = { vm.setPer100(field, it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (field.required) field.label else "${field.label} (optional)") },
                suffix = { Text("${field.unit} / 100 $unit") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        }
    }

    form.error?.let { Warning(it) }

    Button(
        onClick = vm::save,
        enabled = !form.saving && portion != null,
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
    ) {
        if (form.saving) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Text("Save to Health Connect")
        }
    }
}

@Composable
private fun Totals(n: Nutrients?) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (n == null) {
                Text("Enter an amount to see the totals.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(n.energyKcal.kcal(), style = MaterialTheme.typography.displaySmall.tabular())
                Text(" kcal", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))
            }
            Line("Protein", n.proteinG, "g", strong = true)
            Line("Carbohydrate", n.carbsG, "g", strong = true)
            n.sugarG?.let { Line("  of which sugar", it, "g") }
            Line("Fat", n.fatG, "g", strong = true)
            n.saturatedFatG?.let { Line("  of which saturated", it, "g") }
            n.fiberG?.let { Line("Fibre", it, "g") }
            n.sodiumMg?.let { Line("Sodium", it, "mg") }
        }
    }
}

@Composable
private fun Line(label: String, value: Double, unit: String, strong: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            "${value.fmt()} $unit",
            style = MaterialTheme.typography.bodyMedium.tabular(),
            fontWeight = if (strong) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

@Composable
private fun Section(title: String) =
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))

@Composable
private fun Warning(text: String) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Text(text, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
    }
}
