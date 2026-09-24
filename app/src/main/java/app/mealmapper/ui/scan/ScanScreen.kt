package app.mealmapper.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.mealmapper.domain.isValidBarcode
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode

@Composable
fun ScanScreen(onBack: () -> Unit, onBarcode: (String) -> Unit) {
    val context = LocalContext.current
    var cameraAllowed by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var asked by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        cameraAllowed = it
        asked = true
    }
    LaunchedEffect(Unit) { if (!cameraAllowed) permission.launch(Manifest.permission.CAMERA) }

    var typed by remember { mutableStateOf("") }
    var typedError by remember { mutableStateOf<String?>(null) }
    var torchOn by remember { mutableStateOf(false) }

    fun submitTyped() {
        val code = typed.filter(Char::isDigit)
        if (isValidBarcode(code)) onBarcode(code) else typedError = "That number does not look right. Check the digits under the barcode."
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
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("Back") }
                Text("Scan barcode", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                if (cameraAllowed) {
                    FilledTonalButton(onClick = { torchOn = !torchOn }) { Text(if (torchOn) "Light off" else "Light on") }
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                if (cameraAllowed) {
                    BarcodeCamera(torchOn = torchOn, onBarcode = onBarcode)
                } else {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Meal Mapper needs the camera to read barcodes. Photos are not saved.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (asked) {
                            Button(onClick = { permission.launch(Manifest.permission.CAMERA) }, Modifier.padding(top = 12.dp)) {
                                Text("Allow camera")
                            }
                        }
                    }
                }
            }
            Text(
                "Hold the barcode flat, about a hand's length away. It scans on its own.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text("Or type the number", style = MaterialTheme.typography.titleMedium)
            val error = typedError
            OutlinedTextField(
                value = typed,
                onValueChange = {
                    typed = it.filter(Char::isDigit).take(14)
                    typedError = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Barcode number") },
                placeholder = { Text("890…") },
                singleLine = true,
                isError = error != null,
                supportingText = if (error != null) {
                    { Text(error) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submitTyped() }),
            )
            Button(onClick = ::submitTyped, enabled = typed.length >= 8, modifier = Modifier.fillMaxWidth()) {
                Text("Look up")
            }
        }
    }
}

@Composable
private fun BarcodeCamera(torchOn: Boolean, onBarcode: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val controller = remember { LifecycleCameraController(context) }

    DisposableEffect(lifecycleOwner) {
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A)
                .build(),
        )
        val executor = ContextCompat.getMainExecutor(context)
        var handled = false
        controller.setImageAnalysisAnalyzer(
            executor,
            MlKitAnalyzer(listOf(scanner), ImageAnalysis.COORDINATE_SYSTEM_ORIGINAL, executor) { result ->
                if (handled) return@MlKitAnalyzer
                val code = result.getValue(scanner)
                    ?.firstNotNullOfOrNull { it.rawValue?.takeIf(::isValidBarcode) }
                    ?: return@MlKitAnalyzer
                handled = true
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onBarcode(code)
            },
        )
        controller.bindToLifecycle(lifecycleOwner)
        onDispose {
            controller.unbind()
            scanner.close()
        }
    }

    LaunchedEffect(torchOn) { controller.enableTorch(torchOn) }

    AndroidView(
        factory = { PreviewView(it).apply { this.controller = controller } },
        modifier = Modifier.fillMaxSize(),
    )
}
