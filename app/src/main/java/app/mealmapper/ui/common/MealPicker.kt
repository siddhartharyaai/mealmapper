package app.mealmapper.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import app.mealmapper.domain.MealSlot

/** Where the meal came from, shown under the chips so the choice never feels arbitrary. */
enum class SlotReason(val text: String) { TIME("Set from the time"), WORDS("From what you said"), CHOSEN("") }

/** One row of meal chips, the same on every logging screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MealPicker(slot: MealSlot, reason: SlotReason, onChange: (MealSlot) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MealSlot.entries.forEach { s ->
                FilterChip(selected = slot == s, onClick = { onChange(s) }, label = { Text(s.label) })
            }
        }
        if (reason != SlotReason.CHOSEN) {
            Text(
                reason.text + " · tap to change",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
