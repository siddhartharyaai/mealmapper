package app.mealmapper.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import app.mealmapper.domain.MAX_DAYS_BACK
import app.mealmapper.domain.MealSlot
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Where the meal came from, shown under the chips so the choice never feels arbitrary. */
enum class SlotReason(val text: String) { TIME("Set from the time"), WORDS("From what you said"), CHOSEN("") }

private val DAY_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")
private val DAY_LONG: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM")

/**
 * When the food was eaten: the day (Today, Yesterday, or any of the last 30 days) and the meal.
 * Same component on every logging screen and on Review.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MealPicker(
    slot: MealSlot,
    reason: SlotReason,
    onChange: (MealSlot) -> Unit,
    day: LocalDate = LocalDate.now(),
    onDay: ((LocalDate) -> Unit)? = null,
    /** One line ("Today · Dinner · Change") that opens the full picker; for screens that need the room. */
    compact: Boolean = false,
) {
    val today = LocalDate.now()
    var pickingDate by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(!compact) }

    if (!expanded) {
        val dayText = when (day) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> day.format(DAY_LABEL)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$dayText · ${slot.label}",
                style = MaterialTheme.typography.titleSmall,
                color = if (day != today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            TextButton(onClick = { expanded = true }) { Text("Change") }
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (onDay != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = day == today, onClick = { onDay(today) }, label = { Text("Today") })
                FilterChip(selected = day == today.minusDays(1), onClick = { onDay(today.minusDays(1)) }, label = { Text("Yesterday") })
                val other = day.isBefore(today.minusDays(1))
                FilterChip(
                    selected = other,
                    onClick = { pickingDate = true },
                    label = { Text(if (other) "📅 ${day.format(DAY_LABEL)}" else "📅 Other day") },
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MealSlot.entries.forEach { s ->
                FilterChip(selected = slot == s, onClick = { onChange(s) }, label = { Text(s.label) })
            }
        }
        if (day != today) {
            Text(
                "Logging for ${day.format(DAY_LONG)} · Google Health will add it to that day",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        } else if (reason != SlotReason.CHOSEN) {
            Text(
                reason.text + " · tap to change",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (pickingDate && onDay != null) {
        val earliest = today.minusDays(MAX_DAYS_BACK)
        val state = rememberDatePickerState(
            initialSelectedDateMillis = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val d = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                    return !d.isAfter(today) && !d.isBefore(earliest)
                }

                override fun isSelectableYear(year: Int) = year in earliest.year..today.year
            },
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onDay(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    pickingDate = false
                }) { Text("Use this day") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("Cancel") } },
        ) { DatePicker(state = state, showModeToggle = false) }
    }
}
