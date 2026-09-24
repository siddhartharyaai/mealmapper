package app.mealmapper.ui.photo

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class PhotoMode { CAMERA, UPLOAD }

/** What the photo shows. */
enum class PhotoKind(val label: String, val hint: String) {
    MEAL("Meal", "A plate, thali or drink. Gemini estimates each item; you can fix the grams."),
    LABEL("Nutrition label", "The table on the back of the pack. Most accurate."),
    PACK("Pack front", "Finds the product's nutrition online."),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhotoScreen(
    mode: PhotoMode,
    initialKind: PhotoKind,
    hasAiKey: Boolean,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onAnalyse: (kind: PhotoKind, photo: Uri?, note: String, restaurant: Boolean) -> Unit,
) {
    val context = LocalContext.current
    var kind by rememberSaveable { mutableStateOf(initialKind) }
    var note by rememberSaveable { mutableStateOf("") }
    var restaurant by rememberSaveable { mutableStateOf(false) }
    var photo by rememberSaveable { mutableStateOf<Uri?>(null) }
    // Saveable: the camera app can push Meal Mapper out of memory while the photo is being taken.
    var pending by rememberSaveable { mutableStateOf<Uri?>(null) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) photo = pending
    }
    fun launchCamera() {
        val dir = File(context.cacheDir, "photos").apply { mkdirs() }
        val file = File(dir, "photo-${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        pending = uri
        takePicture.launch(uri)
    }
    // The app declares CAMERA (for barcodes), so Android requires it to be granted for the camera app too.
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else error = "Camera access is needed to take a photo. Or use Upload."
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) photo = uri
    }
    fun getPhoto() {
        error = null
        when (mode) {
            PhotoMode.UPLOAD -> pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            PhotoMode.CAMERA ->
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    launchCamera()
                } else {
                    cameraPermission.launch(Manifest.permission.CAMERA)
                }
        }
    }

    LaunchedEffect(photo) {
        val uri = photo ?: return@LaunchedEffect
        preview = runCatching {
            withContext(Dispatchers.IO) { ImageTools.loadBitmap(context, uri, maxSide = 900).asImageBitmap() }
        }.getOrElse {
            error = "Could not open that photo. Try another one."
            null
        }
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
                Text(if (mode == PhotoMode.CAMERA) "Take a photo" else "Upload a photo", style = MaterialTheme.typography.titleLarge)
            }

            if (!hasAiKey) {
                Text(
                    "Photos are read by Gemini. Add your Gemini API key in Settings first.",
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(onClick = onSettings) { Text("Open Settings") }
            }

            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(if (kind == PhotoKind.MEAL) 200 else 80) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("What and how much (optional)") },
                placeholder = {
                    Text(if (kind == PhotoKind.MEAL) "2 phulka, 1 katori dal, bhindi · no ghee" else "half pack · 2 biscuits = 1 serving · 150 g")
                },
                supportingText = {
                    Text(if (kind == PhotoKind.MEAL) "Your counts and sizes beat the photo." else "Amounts in g, ml, pack or servings are used directly.")
                },
                singleLine = kind != PhotoKind.MEAL,
                maxLines = if (kind == PhotoKind.MEAL) 3 else 1,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )

            Text("The photo shows", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PhotoKind.entries.forEach { k ->
                    FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.label) })
                }
            }
            Text(kind.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (kind == PhotoKind.MEAL) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !restaurant, onClick = { restaurant = false }, label = { Text("Home food") })
                    FilterChip(selected = restaurant, onClick = { restaurant = true }, label = { Text("Restaurant / order-in") })
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                val image = preview
                if (image != null) {
                    Image(image, contentDescription = "Your photo", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                } else {
                    Text(
                        when (kind) {
                            PhotoKind.MEAL -> "Shoot from above, whole plate in view."
                            PhotoKind.LABEL -> "Hold the label flat, fill the frame, good light."
                            PhotoKind.PACK -> "Show the brand, variant and pack size."
                        },
                        Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            val current = photo
            if (current == null) {
                Button(onClick = ::getPhoto, modifier = Modifier.fillMaxWidth()) {
                    Text(if (mode == PhotoMode.CAMERA) "Take photo" else "Choose photo")
                }
                if (kind == PhotoKind.MEAL) {
                    OutlinedButton(
                        onClick = { onAnalyse(kind, null, note.trim(), restaurant) },
                        enabled = hasAiKey && note.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("No photo: estimate from my words") }
                }
            } else {
                Button(
                    onClick = { onAnalyse(kind, current, note.trim(), restaurant) },
                    enabled = hasAiKey && preview != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        when (kind) {
                            PhotoKind.MEAL -> "Estimate this meal"
                            PhotoKind.LABEL -> "Read the label"
                            PhotoKind.PACK -> "Find it online"
                        },
                    )
                }
                OutlinedButton(onClick = ::getPhoto, modifier = Modifier.fillMaxWidth()) {
                    Text(if (mode == PhotoMode.CAMERA) "Retake" else "Choose another")
                }
            }
        }
    }
}
