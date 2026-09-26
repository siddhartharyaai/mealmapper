package app.mealmapper.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mealmapper.domain.Draft
import app.mealmapper.domain.DraftItem
import app.mealmapper.domain.Nutrients
import app.mealmapper.domain.SourceKind
import app.mealmapper.ui.common.MealPicker
import app.mealmapper.ui.common.SlotReason
import app.mealmapper.ui.common.VoiceMic
import app.mealmapper.ui.common.kcal
import app.mealmapper.ui.photo.ImageTools
import app.mealmapper.ui.theme.tabular
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ChatScreen(
    vm: ChatViewModel,
    scannedBarcode: String?,
    onBarcodeConsumed: () -> Unit,
    onScan: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onHealthCheck: () -> Unit,
) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val ticker by vm.ticker.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshTicker() }
    LaunchedEffect(scannedBarcode) {
        scannedBarcode?.let {
            vm.onBarcode(it)
            onBarcodeConsumed()
        }
    }
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Meal Mapper", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onHistory) { Text("History") }
                TextButton(onClick = onSettings) { Text("Settings") }
            }
            TickerBar(ticker, onSettings, onHealthCheck)

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (messages.isEmpty()) item { Welcome() }
                items(messages, key = { it.id }) { m ->
                    when (m) {
                        is ChatMessage.User -> UserBubble(m)
                        is ChatMessage.Bot -> BotBubble(m.text, m.error)
                        is ChatMessage.Working -> Working(m.text)
                        is ChatMessage.Card -> LogCard(m.id, m.draft, vm)
                    }
                }
            }

            if (suggestions.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    suggestions.forEach { s -> AssistChip(onClick = { vm.onSuggestion(s) }, label = { Text(s.label, maxLines = 1) }) }
                }
            }
            InputBar(busy = busy, onSend = vm::send, onScan = onScan)
        }
    }
}

@Composable
private fun TickerBar(t: Ticker, onSettings: () -> Unit, onHealthCheck: () -> Unit) {
    when (t.healthReady) {
        false -> Card(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable(onClick = onHealthCheck),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Text(
                "Health Connect access is off. Tap to allow it, or nothing reaches Google Health.",
                Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        true -> {
            val totals = t.totals ?: return
            val eaten = totals.energyKcal.roundToInt()
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    val cap = t.capKcal
                    if (cap == null) {
                        Text("$eaten kcal today", style = MaterialTheme.typography.titleMedium.tabular(), modifier = Modifier.weight(1f))
                        TextButton(onClick = onSettings) { Text("Set a cap") }
                    } else {
                        val left = cap - eaten
                        Text(
                            if (left >= 0) "$left kcal left" else "${abs(left)} kcal over",
                            style = MaterialTheme.typography.titleMedium.tabular(),
                            color = if (left < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Text("$eaten / $cap", style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                t.capKcal?.let { cap ->
                    LinearProgressIndicator(
                        progress = { (eaten.toFloat() / cap).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = if (eaten > cap) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        drawStopIndicator = {},
                    )
                }
                Text(
                    "P ${totals.proteinG.roundToInt()} g · C ${totals.carbsG.roundToInt()} g · F ${totals.fatG.roundToInt()} g · " +
                        "Fibre ${totals.fiberG.roundToInt()} g · Sugar ${totals.sugarG.roundToInt()} g",
                    style = MaterialTheme.typography.bodySmall.tabular(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(Modifier.padding(top = 4.dp))
        }
        null -> Unit
    }
}

@Composable
private fun Welcome() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Tell me what you ate", style = MaterialTheme.typography.titleMedium)
            Text(
                "Type or tap 🎙 and speak, in English, Hindi or both. For example:\n" +
                    "• \"Breakfast: 2 egg omelette, 2 toast and a bowl of papaya\"\n" +
                    "• \"1 scoop True Basics whey in milk, pre breakfast\"\n" +
                    "• \"Lunch at Swati Snacks: panki and a sugarcane juice\"\n" +
                    "• \"Yesterday dinner: 2 phulka, bhindi, 1 katori dal\"",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "I find the values (your foods first, then the Indian food databank, then reliable sites), show where each " +
                    "came from, and log to Google Health when you tap Log. I remember your foods, so repeats are instant. " +
                    "Add a photo of a plate or a nutrition label with +.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UserBubble(m: ChatMessage.User) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (m.photos.isNotEmpty()) Text("📷 ${m.photos.size} photo${if (m.photos.size > 1) "s" else ""}", style = MaterialTheme.typography.bodySmall)
            if (m.text.isNotEmpty()) {
                Card(
                    Modifier.widthIn(max = 300.dp),
                    shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) { Text(m.text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), color = MaterialTheme.colorScheme.onPrimaryContainer) }
            }
        }
    }
}

@Composable
private fun BotBubble(text: String, error: Boolean) {
    Text(
        text,
        Modifier.fillMaxWidth(0.9f),
        style = MaterialTheme.typography.bodyLarge,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun Working(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LogCard(cardId: Long, d: Draft, vm: ChatViewModel) {
    var naming by remember { mutableStateOf(false) }

    val gone = d.state == Draft.State.DISCARDED
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            d.question?.takeIf { d.state == Draft.State.PENDING }?.let {
                Text(it, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }
            if (d.state == Draft.State.PENDING) {
                MealPicker(d.slot, SlotReason.CHOSEN, { vm.setSlot(cardId, it) }, d.day, { vm.setDay(cardId, it) }, compact = true)
            }
            d.items.forEachIndexed { i, item -> ItemRow(cardId, i, item, editable = d.state == Draft.State.PENDING, vm = vm) }
            HorizontalDivider()
            TotalLine(d.total)
            when (d.state) {
                Draft.State.PENDING -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { vm.log(cardId) }, enabled = d.canLog) { Text("Log it") }
                    TextButton(onClick = { naming = true }) { Text("Save as meal") }
                    TextButton(onClick = { vm.discard(cardId) }) { Text("Discard") }
                }
                Draft.State.SAVING -> Working("Logging to Google Health…")
                Draft.State.LOGGED -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Logged ✓ ${d.slot.label}", Modifier.weight(1f), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                    TextButton(onClick = { naming = true }) { Text("Save as meal") }
                    TextButton(onClick = { vm.undo(cardId) }) { Text("Undo") }
                }
                Draft.State.DISCARDED -> Text("Discarded", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (naming && !gone) {
        var name by rememberSaveable { mutableStateOf("My usual ${d.slot.label.lowercase()}") }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Save as a meal") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Next time say its name, or tap it above the chat box.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true, label = { Text("Name") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.saveAsMeal(cardId, name.ifBlank { "My usual ${d.slot.label.lowercase()}" })
                    naming = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
        )
    }

}

@Composable
private fun ItemRow(cardId: Long, index: Int, item: DraftItem, editable: Boolean, vm: ChatViewModel) {
    val struck = !item.include
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.name,
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                textDecoration = if (struck) TextDecoration.LineThrough else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${item.nutrients.energyKcal.kcal()} kcal",
                style = MaterialTheme.typography.titleSmall.tabular(),
                textDecoration = if (struck) TextDecoration.LineThrough else null,
            )
            if (editable) TextButton(onClick = { vm.toggleItem(cardId, index) }) { Text(if (struck) "↺" else "✕") }
        }
        if (editable && !struck) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { vm.stepQty(cardId, index, up = false) }) { Text("−", style = MaterialTheme.typography.titleMedium) }
                Text(item.amountText, style = MaterialTheme.typography.bodyMedium.tabular())
                TextButton(onClick = { vm.stepQty(cardId, index, up = true) }) { Text("+", style = MaterialTheme.typography.titleMedium) }
                UnitMenu(item) { vm.setUnit(cardId, index, it) }
            }
        } else {
            Text(item.amountText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val n = item.nutrients
        Text(
            "P ${n.proteinG.f1()} · C ${n.carbsG.f1()} · F ${n.fatG.f1()}" +
                (n.fiberG?.let { " · Fibre ${it.f1()}" } ?: "") + (n.sugarG?.let { " · Sugar ${it.f1()}" } ?: "") + " g",
            style = MaterialTheme.typography.bodySmall.tabular(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            item.sourceKind.badge + (item.sourceDetail?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.labelSmall,
            color = if (item.sourceKind == SourceKind.AI) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun UnitMenu(item: DraftItem, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("${item.unit} ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            item.units.forEach { (u, g) ->
                DropdownMenuItem(
                    text = { Text(if (u == item.basis.unit) u else "$u (${g.roundToInt()} ${item.basis.unit})") },
                    onClick = {
                        open = false
                        onPick(u)
                    },
                )
            }
        }
    }
}

@Composable
private fun TotalLine(t: Nutrients) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text("Total", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        Text("${t.energyKcal.kcal()} kcal", style = MaterialTheme.typography.titleMedium.tabular(), fontWeight = FontWeight.SemiBold)
    }
    Text(
        "P ${t.proteinG.roundToInt()} g · C ${t.carbsG.roundToInt()} g · F ${t.fatG.roundToInt()} g" +
            (t.fiberG?.let { " · Fibre ${it.roundToInt()} g" } ?: "") + (t.sugarG?.let { " · Sugar ${it.roundToInt()} g" } ?: ""),
        style = MaterialTheme.typography.bodySmall.tabular(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun InputBar(busy: Boolean, onSend: (String, List<Uri>) -> Unit, onScan: () -> Unit) {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    var photos by rememberSaveable { mutableStateOf(ArrayList<String>()) }
    var menu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pending by rememberSaveable { mutableStateOf<Uri?>(null) }
    var previews by remember { mutableStateOf<List<ImageBitmap>>(emptyList()) }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) pending?.let { photos = ArrayList(photos + it.toString()) }
    }
    fun launchCamera() {
        val dir = File(context.cacheDir, "photos").apply { mkdirs() }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(dir, "photo-${System.currentTimeMillis()}.jpg"))
        pending = uri
        takePicture.launch(uri)
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else error = "Camera access is needed to take a photo."
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(6)) { uris ->
        if (uris.isNotEmpty()) photos = ArrayList((photos + uris.map(Uri::toString)).distinct().take(6))
    }
    LaunchedEffect(photos) {
        previews = withContext(Dispatchers.IO) {
            photos.mapNotNull { runCatching { ImageTools.loadBitmap(context, Uri.parse(it), maxSide = 300).asImageBitmap() }.getOrNull() }
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
        error?.let { Text(it, Modifier.padding(horizontal = 8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (previews.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                previews.forEachIndexed { i, p ->
                    Box(contentAlignment = Alignment.TopEnd) {
                        Image(p, "Photo ${i + 1}", Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                        Text(
                            "✕",
                            Modifier.clickable { photos = ArrayList(photos.filterIndexed { j, _ -> j != i }) }.padding(4.dp),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                TextButton(onClick = { menu = true }) { Text("+", style = MaterialTheme.typography.titleLarge) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Take a photo") }, onClick = {
                        menu = false
                        error = null
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            launchCamera()
                        } else {
                            cameraPermission.launch(Manifest.permission.CAMERA)
                        }
                    })
                    DropdownMenuItem(text = { Text("Choose photos") }, onClick = {
                        menu = false
                        pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    })
                    DropdownMenuItem(text = { Text("Scan a barcode") }, onClick = {
                        menu = false
                        onScan()
                    })
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(2000); error = null },
                modifier = Modifier.weight(1f),
                placeholder = { Text("What did you eat?") },
                maxLines = 5,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                shape = RoundedCornerShape(24.dp),
            )
            VoiceMic(
                onText = { spoken -> text = (if (text.isBlank()) spoken else "$text $spoken").take(2000); error = null },
                onError = { error = it },
            )
            TextButton(
                onClick = {
                    onSend(text, photos.map(Uri::parse))
                    text = ""
                    photos = ArrayList()
                },
                enabled = !busy && (text.isNotBlank() || photos.isNotEmpty()),
            ) { Text("Send") }
        }
    }
}

private fun Double.f1(): String = "%.1f".format(this).removeSuffix(".0")
