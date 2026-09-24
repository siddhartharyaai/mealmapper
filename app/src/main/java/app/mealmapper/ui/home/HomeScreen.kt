package app.mealmapper.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mealmapper.CrashLog
import app.mealmapper.data.log.LoggedItem
import app.mealmapper.ui.common.fmt
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.ui.text.style.TextOverflow
import app.mealmapper.domain.DayTotals
import app.mealmapper.ui.theme.tabular
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    savedMessage: String?,
    onMessageShown: () -> Unit,
    onBarcode: () -> Unit,
    onCamera: () -> Unit,
    onUpload: () -> Unit,
    onType: () -> Unit,
    onMenu: () -> Unit,
    onSettings: () -> Unit,
    onHistory: () -> Unit,
    onHealthCheck: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val quick by viewModel.quick.collectAsStateWithLifecycle()
    val quickMessage by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(quickMessage) {
        quickMessage?.let {
            viewModel.messageShown()
            val undo = it.startsWith("Logged:")
            val result = snackbar.showSnackbar(it, actionLabel = if (undo) "Undo" else null, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) viewModel.undoLast()
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    LaunchedEffect(savedMessage) {
        savedMessage?.let {
            viewModel.refresh()
            snackbar.showSnackbar(it)
            onMessageShown()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Meal Mapper", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM")),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onHistory) { Text("History") }
                TextButton(onClick = onSettings) { Text("Settings") }
            }

            CrashCard()

            when (state.healthReady) {
                false -> Card(
                    Modifier.fillMaxWidth().clickable(onClick = onHealthCheck),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Text(
                        "Health Connect access is off or incomplete. Tap to allow it, or nothing reaches Google Health.",
                        Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                true -> Ticker(state.totals, state.capKcal, state.totalsError, onSettings)
                null -> Unit
            }

            Text("Log what you ate", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            CaptureOption("Scan barcode", "Packaged food. Open Food Facts, then the web.", enabled = true, onClick = onBarcode)
            CaptureOption("Take a photo", "A meal, a nutrition label or a pack front.", enabled = true, onClick = onCamera)
            CaptureOption("Upload a photo", "From your gallery. Meal, label or pack front.", enabled = true, onClick = onUpload)
            CaptureOption("Type what you ate", "\"2 phulka, 1 katori dal, 1 glass buttermilk\"", enabled = true, onClick = onType)
            CaptureOption("Scan a menu", "Top 3 picks for your calories left.", enabled = true, onClick = onMenu)

            if (quick.favourites.isNotEmpty() || quick.recent.isNotEmpty()) {
                Text("Log again", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                Text(
                    "One tap logs the same amount now. Star foods in History to keep them here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                (quick.favourites + quick.recent).forEach { item ->
                    QuickRow(item, onClick = { viewModel.logAgain(item) })
                }
            }
        }
    }
}

@Composable
private fun QuickRow(item: LoggedItem, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (item.favourite) "★ " else "+ ", color = MaterialTheme.colorScheme.primary)
        Text(item.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            "${item.amount.fmt()} ${item.unit} · ${item.nutrients.energyKcal.roundToInt()} kcal",
            style = MaterialTheme.typography.bodyMedium.tabular(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Calories eaten today vs the user's cap. Totals include every app that writes to Health Connect. */
@Composable
private fun Ticker(totals: DayTotals?, cap: Int?, failed: Boolean, onSettings: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (totals == null) {
                Text(
                    if (failed) "Could not read today's total from Health Connect." else "Reading today's total…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            val eaten = totals.energyKcal.roundToInt()
            if (cap == null) {
                Headline("${eaten.grouped()} kcal", "eaten today")
                TextButton(onClick = onSettings, modifier = Modifier.padding(start = 0.dp)) { Text("Set a daily calorie cap") }
            } else {
                val left = cap - eaten
                val over = left < 0
                Headline(
                    "${abs(left).grouped()} kcal",
                    if (over) "over your cap" else "left today",
                    over = over,
                )
                LinearProgressIndicator(
                    progress = { (eaten.toFloat() / cap).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    drawStopIndicator = {},
                )
                Text(
                    "${eaten.grouped()} of ${cap.grouped()} kcal",
                    style = MaterialTheme.typography.bodyMedium.tabular(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "Protein ${totals.proteinG.roundToInt()} g · Carbs ${totals.carbsG.roundToInt()} g · Fat ${totals.fatG.roundToInt()} g",
                style = MaterialTheme.typography.bodyMedium.tabular(),
            )
        }
    }
}

@Composable
private fun Headline(value: String, label: String, over: Boolean = false) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            value,
            style = MaterialTheme.typography.headlineMedium.tabular(),
            color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 3.dp))
    }
}

@Composable
private fun CaptureOption(title: String, detail: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = if (enabled) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    } else {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    }
    val textColor = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        colors = colors,
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = textColor)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = textColor)
        }
    }
}

private fun Int.grouped(): String = "%,d".format(this)

/** Shown once after a crash. The user copies the report and sends it; then dismisses it. */
@Composable
private fun CrashCard() {
    val context = LocalContext.current
    var report by remember { mutableStateOf(CrashLog.read(context)) }
    val text = report ?: return
    val clipboard = LocalClipboardManager.current
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Meal Mapper crashed last time. Copy the report and send it to the developer.",
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text.lines().take(4).joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text("Copy report") }
                TextButton(onClick = {
                    CrashLog.clear(context)
                    report = null
                }) { Text("Dismiss") }
            }
        }
    }
}
