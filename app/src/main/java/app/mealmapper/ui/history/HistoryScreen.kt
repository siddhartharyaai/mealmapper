package app.mealmapper.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mealmapper.data.log.LoggedItem
import app.mealmapper.domain.MealSlot
import app.mealmapper.ui.common.fmt
import app.mealmapper.ui.common.kcal
import app.mealmapper.ui.theme.tabular
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HistoryScreen(viewModel: HistoryViewModel, onBack: () -> Unit) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<LoggedItem?>(null) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    val zone = ZoneId.systemDefault()
    val cutoff = LocalDate.now().minusDays(30)
    val days = items
        .groupBy { it.time.atZone(zone).toLocalDate() }
        .filterKeys { !it.isBefore(cutoff) }
        .toSortedMap(Comparator.reverseOrder())

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text("Back") }
                    Text("History", style = MaterialTheme.typography.titleLarge)
                }
            }
            if (days.isEmpty()) {
                item {
                    Text(
                        "Nothing logged with Meal Mapper in the last 30 days. Food logged in other apps shows in Google Health only.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            days.forEach { (day, dayItems) ->
                item(key = day.toString()) { DayHeader(day, dayItems) }
                val bySlot = dayItems.sortedBy { it.eatenAt }.groupBy { it.mealSlot }
                MealSlot.entries.forEach { slot ->
                    val slotItems = bySlot[slot] ?: return@forEach
                    item(key = "$day-$slot") {
                        Text(
                            "${slot.label()} · ${slotItems.sumOf { it.nutrients.energyKcal }.kcal()} kcal",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    items(slotItems, key = { it.clientId }) { item ->
                        ItemRow(item, onClick = { editing = item }, onStar = { viewModel.toggleFavourite(item) })
                    }
                }
            }
            item { Text(" ", Modifier.padding(bottom = 24.dp)) }
        }
    }

    editing?.let { item ->
        EditDialog(
            item = item,
            onDismiss = { editing = null },
            onSave = { name, amount, slot ->
                viewModel.update(item, name, amount, slot)
                editing = null
            },
            onDelete = {
                viewModel.delete(item)
                editing = null
            },
        )
    }
}

@Composable
private fun DayHeader(day: LocalDate, items: List<LoggedItem>) {
    val total = items.sumOf { it.nutrients.energyKcal }
    val label = when (day) {
        LocalDate.now() -> "Today"
        LocalDate.now().minusDays(1) -> "Yesterday"
        else -> day.format(DateTimeFormatter.ofPattern("EEE, d MMM"))
    }
    Column(Modifier.padding(top = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text("${total.kcal()} kcal", style = MaterialTheme.typography.titleMedium.tabular())
        }
        HorizontalDivider(Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun ItemRow(item: LoggedItem, onClick: () -> Unit, onStar: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${item.amount.fmt()} ${item.unit}" + (if (item.estimate) " · estimate" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = if (item.estimate) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text("${item.nutrients.energyKcal.kcal()} kcal", style = MaterialTheme.typography.bodyMedium.tabular())
        TextButton(onClick = onStar) {
            Text(if (item.favourite) "★" else "☆", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditDialog(
    item: LoggedItem,
    onDismiss: () -> Unit,
    onSave: (String, Double, MealSlot) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember { mutableStateOf(item.name) }
    var amountText by remember { mutableStateOf(item.amount.fmt()) }
    var slot by remember { mutableStateOf(item.mealSlot) }
    var confirmDelete by remember { mutableStateOf(false) }
    val amount = amountText.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it <= 5000 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit entry") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(100) }, label = { Text("Food") }, singleLine = true)
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it.filter { c -> c.isDigit() || c == '.' }.take(6) },
                    label = { Text("Amount") },
                    suffix = { Text(item.unit) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Text(
                    amount?.let { "${item.per100.scaled(it / 100.0).energyKcal.kcal()} kcal" } ?: "Enter an amount",
                    style = MaterialTheme.typography.titleMedium.tabular(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MealSlot.entries.forEach { s ->
                        FilterChip(selected = slot == s, onClick = { slot = s }, label = { Text(s.label()) })
                    }
                }
                if (confirmDelete) {
                    Text("Delete from Meal Mapper and Google Health?", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { amount?.let { onSave(name, it, slot) } }, enabled = amount != null) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { if (confirmDelete) onDelete() else confirmDelete = true }) {
                    Text(if (confirmDelete) "Yes, delete" else "Delete", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

private fun MealSlot.label() = name.lowercase().replaceFirstChar(Char::uppercase)
