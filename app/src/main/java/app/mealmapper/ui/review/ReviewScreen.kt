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
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mealmapper.domain.MealSlot
import app.mealmapper.domain.Nutrients
import app.mealmapper.domain.PieceModel
import app.mealmapper.domain.ProductSource
import app.mealmapper.domain.isIndianBarcode
import app.mealmapper.domain.portionOptions
import app.mealmapper.ui.common.fmt
import app.mealmapper.ui.photo.PhotoKind
import app.mealmapper.ui.common.MealPicker
import app.mealmapper.ui.common.SlotReason
import app.mealmapper.data.ai.AiParsing
import app.mealmapper.ui.common.kcal
import app.mealmapper.ui.theme.tabular

@Composable
fun ReviewScreen(
    viewModel: ReviewViewModel,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    onPhotograph: (kind: PhotoKind, barcode: String?, name: String?) -> Unit,
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
                    onLabel = { onPhotograph(PhotoKind.LABEL, s.barcode, s.productName) },
                    onPack = { onPhotograph(PhotoKind.PACK, s.barcode, s.productName) },
                    onManual = viewModel::enterManually,
                    onSettings = onSettings,
                    onBack = onBack,
                )
                is ReviewState.Ready -> Form(s.form, viewModel)
                is ReviewState.Meal -> MealReview(s.form, viewModel)
                is ReviewState.Menu -> MenuPicks(s, onPick = viewModel::choosePick, onBack = onBack)
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
    onPack: () -> Unit,
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
            Text("Add a Gemini key to search the web")
        }
        else -> OutlinedButton(onClick = onWeb, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.webTried) "Search the web again" else "Find it online")
        }
    }
    OutlinedButton(onClick = onPack, modifier = Modifier.fillMaxWidth()) { Text("Photograph the pack front and search") }
    OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth()) { Text("Type the label values") }
    TextButton(onClick = onBack) { Text("Back") }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Form(form: ReviewForm, vm: ReviewViewModel) {
    val unit = form.product.basis.unit
    val portion = form.portion

    MealPicker(form.slot, SlotReason.CHOSEN, vm::setSlot, form.day, vm::setDay)

    OutlinedTextField(
        value = form.name,
        onValueChange = vm::setName,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Food") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
    )
    Text(
        form.source.label,
        style = MaterialTheme.typography.bodySmall,
        color = if (form.source is ProductSource.Web && ((form.source as ProductSource.Web).agreeing < 2 || !(form.source as ProductSource.Web).cited)) {
            MaterialTheme.colorScheme.tertiary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
    if (form.problems.isNotEmpty()) {
        val prefix = if (form.source is ProductSource.Databank) "Note: " else "Check these against the pack: "
        Warning(prefix + form.problems.joinToString(" "))
    }

    // 1. The facts, as printed. The user reads these before saying how much they ate.
    if (!form.isManual) Facts(form)
    if (form.per100.energyLooksWrong()) {
        Warning("Calories do not match protein, carbs and fat on this label. Check the values.")
    }
    TextButton(onClick = vm::toggleEditLabel) {
        Text(if (form.editingLabel) "Done correcting" else "Correct the label values")
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

    // 2. How much the user ate.
    Section("How much did you eat?")
    form.pieces?.let { p ->
        PieceStepper(p, form.pieceCount, form.pieceSize) { count, size -> vm.setPieces(count, size) }
        Text("Or choose an amount:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
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
        label = { Text("Amount eaten") },
        placeholder = { Text("e.g. 30") },
        suffix = { Text(unit) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
    form.amountFromNote?.let {
        Text("From your note: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }

    // 3. What that amount gives.
    if (portion != null) {
        Section("You ate ${form.amount!!.fmt()} $unit")
    }
    Totals(portion)


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

    form.error?.let { Warning(it) }

    Button(
        onClick = vm::save,
        enabled = !form.saving && portion != null,
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
    ) {
        if (form.saving) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Text(if (portion == null) "Enter the amount to save" else "Save ${portion.energyKcal.kcal()} kcal")
        }
    }
}

/** Top picks from a menu photo. Tapping one turns it into a meal to check and save. */
@Composable
private fun MenuPicks(state: ReviewState.Menu, onPick: (AiParsing.MealItem) -> Unit, onBack: () -> Unit) {
    Text(
        (state.restaurantName?.let { "$it · " } ?: "") +
            (state.kcalLeft?.let { if (it >= 0) "$it kcal left today" else "${-it} kcal over today" } ?: "No daily cap set"),
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        "Eggetarian dishes from this menu. Calories are estimates for a normal restaurant portion.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    state.picks.take(3).forEachIndexed { i, pick ->
        val kcal = pick.per100.scaled(pick.grams / 100.0)
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}. ${pick.name}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Text("≈ ${kcal.energyKcal.kcal()} kcal", style = MaterialTheme.typography.titleMedium.tabular())
                }
                Text(
                    "P ${kcal.proteinG.fmt1()} g · C ${kcal.carbsG.fmt1()} g · F ${kcal.fatG.fmt1()} g" +
                        (if (pick.lowKcal != null && pick.highKcal != null) " · ${pick.lowKcal.kcal()}–${pick.highKcal.kcal()} kcal" else ""),
                    style = MaterialTheme.typography.bodySmall.tabular(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                pick.assumption?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                OutlinedButton(onClick = { onPick(pick) }) { Text("I ordered this: log it") }
            }
        }
    }
    TextButton(onClick = onBack) { Text("Back") }
}

/**
 * "How many, what size": the way Indians count rotis, pooris, idlis and bread. Sizes for flatbreads are
 * diameters (a quarter plate is about 18 cm); the grams are typical and shown so the user can check them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PieceStepper(model: PieceModel, count: Double, size: Int, onChange: (Double, Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onChange((count - 0.5).coerceAtLeast(0.0), size) }, enabled = count > 0) { Text("−") }
            Text(
                if (count % 1.0 == 0.0) count.toInt().toString() else "%.1f".format(count),
                style = MaterialTheme.typography.headlineSmall.tabular(),
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            OutlinedButton(onClick = { onChange(if (count < 1.0) 1.0 else count + 1.0, size) }) { Text("+") }
            Text(if (count == 1.0) model.noun else model.plural, style = MaterialTheme.typography.titleMedium)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            model.sizes.forEachIndexed { i, s ->
                FilterChip(
                    selected = size == i,
                    onClick = { onChange(if (count == 0.0) 1.0 else count, i) },
                    label = { Text("${s.label} · ${s.grams.fmt()} g") },
                )
            }
        }
    }
}

/** The product's nutrition table: per 100 g/ml, and per serving when the pack gives one. */
@Composable
private fun Facts(form: ReviewForm) {
    val unit = form.product.basis.unit
    val per100 = form.per100
    val serving = form.product.servingSize
    val perServing = serving?.let { per100.scaled(it / 100.0) }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Nutrition facts", style = MaterialTheme.typography.titleMedium)
            form.product.packSize?.let {
                Text("Pack: ${it.fmt()} $unit", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FactRow("", "per 100 $unit", perServing?.let { "per ${serving.fmt()} $unit" }, header = true)
            FactRow("Energy", "${per100.energyKcal.kcal()} kcal", perServing?.let { "${it.energyKcal.kcal()} kcal" }, strong = true)
            FactRow("Protein", "${per100.proteinG.fmt1()} g", perServing?.let { "${it.proteinG.fmt1()} g" }, strong = true)
            FactRow("Carbohydrate", "${per100.carbsG.fmt1()} g", perServing?.let { "${it.carbsG.fmt1()} g" }, strong = true)
            per100.sugarG?.let { v -> FactRow("  of which sugar", "${v.fmt1()} g", perServing?.sugarG?.let { "${it.fmt1()} g" }) }
            FactRow("Fat", "${per100.fatG.fmt1()} g", perServing?.let { "${it.fatG.fmt1()} g" }, strong = true)
            per100.saturatedFatG?.let { v -> FactRow("  of which saturated", "${v.fmt1()} g", perServing?.saturatedFatG?.let { "${it.fmt1()} g" }) }
            per100.fiberG?.let { v -> FactRow("Fibre", "${v.fmt1()} g", perServing?.fiberG?.let { "${it.fmt1()} g" }) }
            per100.sodiumMg?.let { v -> FactRow("Sodium", "${v.kcal()} mg", perServing?.sodiumMg?.let { "${it.kcal()} mg" }) }
        }
    }
}

@Composable
private fun FactRow(label: String, a: String, b: String?, strong: Boolean = false, header: Boolean = false) {
    val style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium.tabular()
    val weight = if (strong) FontWeight.Medium else FontWeight.Normal
    val color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1.4f), style = MaterialTheme.typography.bodyMedium)
        Text(a, Modifier.weight(1f), style = style, fontWeight = weight, color = color, textAlign = TextAlign.End)
        if (b != null) Text(b, Modifier.weight(1f), style = style, fontWeight = weight, color = color, textAlign = TextAlign.End)
    }
}

private fun Double.fmt1(): String = "%.1f".format(this).removeSuffix(".0")

/** A plate of food: one row per item, grams editable, each item can be left out. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MealReview(form: MealForm, vm: ReviewViewModel) {
    val total = form.total
    val range = form.range
    MealPicker(form.slot, SlotReason.CHOSEN, vm::setMealSlot, form.day, vm::setMealDay)
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Text(
            if (form.restaurant) {
                "Estimate, not measured. Gemini estimated each item from the photo and your note using typical restaurant " +
                    "values; published chain values are used where found. Fix the grams if you know them."
            } else {
                "Estimate, not measured. Gemini estimated the grams from the photo and your note. Where it found the same " +
                    "dish in the food databank (INDB/IFCT), those values are used. Fix the grams if you know them."
            },
            Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (total == null) {
                Text("Enter grams for every ticked item.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text("≈ " + total.energyKcal.kcal(), style = MaterialTheme.typography.displaySmall.tabular())
                Text(" kcal", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))
            }
            range?.takeIf { it.second - it.first >= 1 }?.let {
                Text("Likely ${it.first.kcal()}–${it.second.kcal()} kcal", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Line("Protein", total.proteinG, "g", strong = true)
            Line("Carbohydrate", total.carbsG, "g", strong = true)
            Line("Fat", total.fatG, "g", strong = true)
            total.fiberG?.let { Line("Fibre", it, "g") }
        }
    }

    form.rows.forEachIndexed { i, row ->
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (row.include) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = row.include, onCheckedChange = { vm.toggleRow(i) })
                    Text(row.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Text(
                        row.nutrients?.let { "${it.energyKcal.kcal()} kcal" } ?: "–",
                        style = MaterialTheme.typography.titleSmall.tabular(),
                    )
                }
                if (row.include) {
                    row.pieces?.let { p ->
                        PieceStepper(p, row.pieceCount, row.pieceSize) { count, size -> vm.setRowPieces(i, count, size) }
                    }
                    OutlinedTextField(
                        value = row.gramsText,
                        onValueChange = { vm.setRowGrams(i, it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Amount") },
                        suffix = { Text("g") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    row.nutrients?.let { n ->
                        Text(
                            "P ${n.proteinG.fmt1()} g · C ${n.carbsG.fmt1()} g · F ${n.fatG.fmt1()} g" +
                                (row.range?.let { " · range ${it.first.kcal()}–${it.second.kcal()}" } ?: ""),
                            style = MaterialTheme.typography.bodySmall.tabular(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    row.db?.let { db ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (row.useDb) "Values: ${db.name.substringBefore(" (")} · ${db.source}" else "Values: AI estimate",
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (row.useDb) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                            )
                            TextButton(onClick = { vm.toggleRowDb(i) }) { Text(if (row.useDb) "Use AI" else "Use databank") }
                        }
                    }
                    row.assumption?.let {
                        if (row.published) {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        } else {
                            Text("Assumed: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                }
            }
        }
    }


    form.error?.let { Warning(it) }

    Button(
        onClick = vm::saveMeal,
        enabled = form.canSave,
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
    ) {
        if (form.saving) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Text(total?.let { "Save meal · ${it.energyKcal.kcal()} kcal" } ?: "Save meal")
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
                Text("Enter the amount you ate to see your totals.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
