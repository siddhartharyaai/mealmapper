package app.mealmapper.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.mealmapper.data.health.HealthConnectAvailability
import app.mealmapper.data.health.HealthConnectGateway
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun HomeScreen(
    healthConnect: HealthConnectGateway,
    savedMessage: String?,
    onMessageShown: () -> Unit,
    onBarcode: () -> Unit,
    onSetup: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    var healthReady by remember { mutableStateOf(true) }
    var resumes by remember { mutableIntStateOf(0) }

    // Re-check on every return to this screen: the user may have changed access in settings.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes++ }
    LaunchedEffect(resumes) {
        healthReady = healthConnect.availability() == HealthConnectAvailability.AVAILABLE &&
            runCatching { healthConnect.hasAllPermissions() }.getOrDefault(false)
    }
    LaunchedEffect(savedMessage) {
        savedMessage?.let {
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
                TextButton(onClick = onSetup) { Text("Setup") }
            }

            if (!healthReady) {
                Card(
                    Modifier.fillMaxWidth().clickable(onClick = onSetup),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Text(
                        "Health Connect access is off. Tap to fix it, or nothing will reach Google Health.",
                        Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            Text(
                "Log what you ate",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
            CaptureOption("Scan barcode", "Packaged food. Looks it up on Open Food Facts.", enabled = true, onClick = onBarcode)
            CaptureOption("Take a photo", "A meal or a nutrition label. Next build.", enabled = false, onClick = {})
            CaptureOption("Upload a photo", "From your gallery. Next build.", enabled = false, onClick = {})
        }
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
