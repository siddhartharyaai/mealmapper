package app.mealmapper.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mealmapper.data.fooddb.FoodDb
import app.mealmapper.domain.DbFood
import app.mealmapper.domain.FoodSearch
import app.mealmapper.ui.common.VoiceInput
import app.mealmapper.domain.MealSlot
import app.mealmapper.domain.mealSlotFor
import app.mealmapper.ui.common.MealPicker
import app.mealmapper.ui.common.SlotReason
import java.time.LocalTime
import app.mealmapper.ui.common.kcal
import app.mealmapper.ui.theme.tabular

/** Log any food by name from the offline databank. Hinglish works: "daal", "sabji", "dahi". */
@Composable
fun SearchScreen(db: FoodDb, onBack: () -> Unit, onPick: (id: String, note: String, slot: MealSlot) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var slot by rememberSaveable { mutableStateOf(mealSlotFor(LocalTime.now())) }
    var slotReason by rememberSaveable { mutableStateOf(SlotReason.TIME) }
    var foods by remember { mutableStateOf<List<DbFood>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { foods = runCatching { db.all() }.getOrElse { emptyList() } }
    val results = remember(query, foods) { foods?.let { FoodSearch.search(it, query) }.orEmpty() }

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().padding(horizontal = 20.dp)) {
            Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("Back") }
                Text("Search foods", style = MaterialTheme.typography.titleLarge)
            }
            MealPicker(slot, slotReason) { slot = it; slotReason = SlotReason.CHOSEN }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(60) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Food") },
                placeholder = { Text("dal, bhindi, poha, beer…") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
            )
            VoiceInput(
                onText = { query = it.take(60) },
                onUnavailable = { message = it },
                prompt = "Say a food",
            )
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            when {
                foods == null -> Text("Loading the databank…", Modifier.padding(top = 16.dp))
                query.isNotBlank() && results.isEmpty() -> Text(
                    "Nothing found. Try another spelling, or use Say or type what you ate.",
                    Modifier.padding(top = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(results, key = { it.id }) { food ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(food.id, "", slot) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(food.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                food.source + (if (food.warnings.isNotEmpty()) " · check values" else ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (food.warnings.isNotEmpty()) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "${food.per100.energyKcal.kcal()} kcal/100 ${food.basis.unit}",
                            style = MaterialTheme.typography.bodySmall.tabular(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
