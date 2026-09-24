package app.mealmapper.ui.photo

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
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
import app.mealmapper.ui.common.VoiceInput
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** TYPE: no photo, the user describes the meal in words. */
enum class PhotoMode { CAMERA, UPLOAD, TYPE }

private const val MAX_PHOTOS = 6

/** What the photo shows. */
enum class PhotoKind(val label: String, val hint: String) {
    MEAL("Meal", "A plate, thali or drink. Gemini estimates each item; you can fix the grams."),
    LABEL("Nutrition label", "The table on the back of the pack. Most accurate."),
    PACK("Pack front", "Finds the product's nutrition online."),
    MENU("Menu", "Top 3 eggetarian picks for your calories left today."),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhotoScreen(
    mode: PhotoMode,
    initialKind: PhotoKind,
    hasAiKey: Boolean,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onAnalyse: (kind: PhotoKind, photos: List<Uri>, note: String, restaurant: Boolean, restaurantName: String) -> Unit,
) {
    val context = LocalContext.current
    var kind by rememberSaveable { mutableStateOf(if (mode == PhotoMode.TYPE) PhotoKind.MEAL else initialKind) }
    val typing = mode == PhotoMode.TYPE
    var note by rememberSaveable { mutableStateOf("") }
    var restaurant by rememberSaveable { mutableStateOf(false) }
    var restaurantName by rememberSaveable { mutableStateOf("") }
    // Uris kept as strings so the list survives the camera app pushing Meal Mapper out of memory.
    var photoStrings by rememberSaveable { mutableStateOf(ArrayList<String>()) }
    val photos = photoStrings.map(Uri::parse)
    // Meals and menus can span several photos (one per dish or menu page). Labels and packs use one.
    val multi = kind == PhotoKind.MEAL || kind == PhotoKind.MENU
    fun addPhotos(new: List<Uri>) {
        val merged = if (multi) (photoStrings + new.map(Uri::toString)).distinct().take(MAX_PHOTOS) else new.take(1).map(Uri::toString)
        photoStrings = ArrayList(merged)
    }
    var pending by rememberSaveable { mutableStateOf<Uri?>(null) }
    var previews by remember { mutableStateOf<List<ImageBitmap>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(kind) {
        if (!multi && photoStrings.size > 1) photoStrings = ArrayList(photoStrings.take(1))
    }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) pending?.let { addPhotos(listOf(it)) }
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
        if (uri != null) addPhotos(listOf(uri))
    }
    val pickMany = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PHOTOS)) { uris ->
        if (uris.isNotEmpty()) addPhotos(uris)
    }
    fun getPhoto() {
        error = null
        when (mode) {
            PhotoMode.UPLOAD -> {
                val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                if (multi) pickMany.launch(request) else pick.launch(request)
            }
            PhotoMode.TYPE -> Unit
            PhotoMode.CAMERA ->
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    launchCamera()
                } else {
                    cameraPermission.launch(Manifest.permission.CAMERA)
                }
        }
    }

    LaunchedEffect(photoStrings) {
        val loaded = withContext(Dispatchers.IO) {
            photos.mapNotNull { uri -> runCatching { ImageTools.loadBitmap(context, uri, maxSide = 900).asImageBitmap() }.getOrNull() }
        }
        if (loaded.size < photos.size) error = "Could not open one of the photos. Remove it or try another."
        previews = loaded
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
                Text(
                    when (mode) {
                        PhotoMode.CAMERA -> "Take a photo"
                        PhotoMode.UPLOAD -> "Upload a photo"
                        PhotoMode.TYPE -> "Say or type what you ate"
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
            }

            if (!hasAiKey) {
                Text(
                    "This uses Gemini. Add your Gemini API key in Settings first.",
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

            if (kind == PhotoKind.MEAL) {
                VoiceInput(
                    onText = { spoken -> note = (if (note.isBlank()) spoken else "$note, $spoken").take(200) },
                    onUnavailable = { error = it },
                    prompt = "Speak your meal",
                )
            }

            if (!typing) {
                Text("The photo shows", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PhotoKind.entries.forEach { k ->
                        FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.label) })
                    }
                }
                Text(kind.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (kind == PhotoKind.MEAL) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !restaurant, onClick = { restaurant = false }, label = { Text("Home food") })
                    FilterChip(selected = restaurant, onClick = { restaurant = true }, label = { Text("Restaurant / order-in") })
                }
            }
            if ((kind == PhotoKind.MEAL && restaurant) || kind == PhotoKind.MENU) {
                OutlinedTextField(
                    value = restaurantName,
                    onValueChange = { restaurantName = it.take(60) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Restaurant name (optional)") },
                    placeholder = { Text("McDonald's, Theobroma, Swati Snacks…") },
                    supportingText = {
                        Text(if (kind == PhotoKind.MENU) "Helps with portion sizes." else "Chains' published values are used when found.")
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
            }

            if (typing) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(
                    onClick = { onAnalyse(PhotoKind.MEAL, emptyList(), note.trim(), restaurant, restaurantName.trim()) },
                    enabled = hasAiKey && note.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Estimate") }
                return@Column
            }
            when {
                previews.size == 1 -> Image(
                    previews[0],
                    contentDescription = "Your photo",
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)),
                    contentScale = ContentScale.FillWidth,
                )
                previews.size > 1 -> Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    previews.forEachIndexed { i, image ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Image(
                                image,
                                contentDescription = "Photo ${i + 1}",
                                Modifier.size(150.dp).clip(RoundedCornerShape(16.dp)),
                                contentScale = ContentScale.Crop,
                            )
                            TextButton(onClick = { photoStrings = ArrayList(photoStrings.filterIndexed { j, _ -> j != i }) }) {
                                Text("Remove")
                            }
                        }
                    }
                }
                else -> Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        when (kind) {
                            PhotoKind.MEAL -> "One photo of the whole plate, or one photo per dish (up to $MAX_PHOTOS)."
                            PhotoKind.MENU -> "One photo per menu page (up to $MAX_PHOTOS), flat, text readable."
                            PhotoKind.LABEL -> "Hold the label flat, fill the frame, good light."
                            PhotoKind.PACK -> "Show the brand, variant and pack size."
                        },
                        Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            if (photos.isEmpty()) {
                Button(onClick = ::getPhoto, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        when {
                            mode == PhotoMode.CAMERA -> "Take photo"
                            multi -> "Choose photos"
                            else -> "Choose photo"
                        },
                    )
                }
                if (kind == PhotoKind.MEAL) {
                    OutlinedButton(
                        onClick = { onAnalyse(kind, emptyList(), note.trim(), restaurant, restaurantName.trim()) },
                        enabled = hasAiKey && note.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("No photo: estimate from my words") }
                }
            } else {
                val count = if (photos.size > 1) " (${photos.size} photos)" else ""
                Button(
                    onClick = { onAnalyse(kind, photos, note.trim(), restaurant, restaurantName.trim()) },
                    enabled = hasAiKey && previews.size == photos.size,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        when (kind) {
                            PhotoKind.MEAL -> "Estimate this meal$count"
                            PhotoKind.MENU -> "Suggest what to order$count"
                            PhotoKind.LABEL -> "Read the label"
                            PhotoKind.PACK -> "Find it online"
                        },
                    )
                }
                if (multi && photos.size < MAX_PHOTOS) {
                    OutlinedButton(onClick = ::getPhoto, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            when {
                                kind == PhotoKind.MENU -> "Add another menu page"
                                mode == PhotoMode.CAMERA -> "Take another dish"
                                else -> "Add more photos"
                            },
                        )
                    }
                }
                if (!multi) {
                    OutlinedButton(onClick = ::getPhoto, modifier = Modifier.fillMaxWidth()) {
                        Text(if (mode == PhotoMode.CAMERA) "Retake" else "Choose another")
                    }
                } else {
                    TextButton(onClick = { photoStrings = ArrayList() }) { Text("Start again") }
                }
            }
        }
    }
}
