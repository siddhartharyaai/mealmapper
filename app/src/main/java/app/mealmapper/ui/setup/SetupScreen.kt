package app.mealmapper.ui.setup

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mealmapper.data.health.HealthConnectAvailability
import app.mealmapper.data.health.HealthConnectGateway
import app.mealmapper.ui.theme.tabular
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SetupScreen(viewModel: SetupViewModel, healthConnect: HealthConnectGateway) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    // Re-check every time the user comes back (from Play Store, Health Connect settings, etc.).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    val permissionContract = remember(healthConnect) { healthConnect.permissionContract() }
    val permissionLauncher = rememberLauncherForActivityResult(permissionContract) {
        viewModel.onPermissionResult(it)
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    fun open(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // No Play Store or settings screen on this device. Nothing more we can do from here.
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Meal Mapper", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Setup check. One test entry must reach Google Health and Samsung Health " +
                    "before we build scanning on top.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val hcReady = state.availability == HealthConnectAvailability.AVAILABLE && state.permissionGranted

            StepCard(number = 1, title = "Health Connect access", done = hcReady) {
                when (state.availability) {
                    null -> Body("Checking…")
                    HealthConnectAvailability.NOT_INSTALLED -> {
                        Body("Health Connect is not on this phone. Install it from the Play Store, then come back.")
                        Button(onClick = { open(healthConnect.installIntent()) }) { Text("Install Health Connect") }
                    }
                    HealthConnectAvailability.UPDATE_REQUIRED -> {
                        Body("Health Connect needs an update before Meal Mapper can use it.")
                        Button(onClick = { open(healthConnect.installIntent()) }) { Text("Update Health Connect") }
                    }
                    HealthConnectAvailability.AVAILABLE -> when {
                        state.permissionGranted -> Body("Meal Mapper can write nutrition. It cannot read anything.")
                        else -> {
                            Body("Meal Mapper asks for one permission: write nutrition. It does not read your health data.")
                            Button(onClick = { permissionLauncher.launch(viewModel.requiredPermissions) }) {
                                Text("Allow access")
                            }
                            if (state.permissionDenied) {
                                Body(
                                    "Access was not given. If the dialog does not open again, " +
                                        "turn on Nutrition for Meal Mapper in Health Connect settings.",
                                )
                                TextButton(onClick = { open(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) }) {
                                    Text("Open Health Connect settings")
                                }
                            }
                        }
                    }
                }
            }

            StepCard(number = 2, title = "Write a test entry", done = state.lastWrittenAt != null) {
                val entry = SetupViewModel.TEST_ENTRY
                Body(entry.name)
                NutrientTable(
                    listOf(
                        "Energy" to "${entry.energyKcal.fmt()} kcal",
                        "Protein" to "${entry.proteinG.fmt()} g",
                        "Carbohydrate" to "${entry.carbsG.fmt()} g",
                        "of which sugar" to "${entry.sugarG!!.fmt()} g",
                        "Fat" to "${entry.fatG.fmt()} g",
                    ),
                )
                state.lastWrittenAt?.let {
                    Body("Written at ${TIME.format(it.atZone(ZoneId.systemDefault()))}.")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::writeTestEntry, enabled = hcReady && !state.busy) {
                        Text(if (state.lastWrittenAt == null) "Write test entry" else "Write again")
                    }
                    OutlinedButton(onClick = viewModel::removeTestEntry, enabled = hcReady && !state.busy) {
                        Text("Remove")
                    }
                }
            }

            StepCard(number = 3, title = "Check both apps", done = false) {
                Label("Google Health")
                Body(
                    "First, one time: in Health Connect, open App permissions → Google Health and " +
                        "allow it to read Nutrition. Then open the Health tab → Focus areas → Nutrition.",
                )
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Label("Samsung Health")
                Body(
                    "First, one time: in Health Connect, open App permissions → Samsung Health and " +
                        "allow it to read Nutrition. Then open the Food tracker. " +
                        "Samsung Health can take a few minutes to sync.",
                )
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Body("When you have checked, tap Remove above so the test does not count in your day.")
            }
        }
    }
}

@Composable
private fun StepCard(number: Int, title: String, done: Boolean, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepBadge(number, done)
                Spacer(Modifier.size(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

@Composable
private fun StepBadge(number: Int, done: Boolean) {
    val bg = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (done) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .size(28.dp)
            .background(bg, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (done) "✓" else "$number", color = fg, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun NutrientTable(rows: List<Pair<String, String>>) {
    Column(Modifier.fillMaxWidth()) {
        rows.forEach { (label, value) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(value, style = MaterialTheme.typography.bodyMedium.tabular(), fontWeight = FontWeight.Medium)
            }
        }
    }
    Spacer(Modifier.height(2.dp))
}

@Composable
private fun Body(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) =
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color)

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.labelLarge)

private val TIME = DateTimeFormatter.ofPattern("h:mm a")

private fun Double.fmt(): String = if (this % 1.0 == 0.0) toInt().toString() else "%.1f".format(this)
