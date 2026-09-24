package app.mealmapper

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.mealmapper.ui.history.HistoryScreen
import app.mealmapper.ui.history.HistoryViewModel
import app.mealmapper.domain.MealSlot
import java.time.LocalDate
import app.mealmapper.ui.home.HomeScreen
import app.mealmapper.ui.home.HomeViewModel
import app.mealmapper.ui.photo.PhotoKind
import app.mealmapper.ui.photo.PhotoMode
import app.mealmapper.ui.photo.PhotoScreen
import app.mealmapper.ui.review.ReviewRequest
import app.mealmapper.ui.review.ReviewScreen
import app.mealmapper.ui.review.ReviewViewModel
import app.mealmapper.ui.scan.ScanScreen
import app.mealmapper.ui.search.SearchScreen
import app.mealmapper.ui.settings.SettingsScreen
import app.mealmapper.ui.setup.SetupScreen
import app.mealmapper.ui.setup.SetupViewModel
import app.mealmapper.ui.theme.MealMapperTheme

private object Routes {
    const val HOME = "home"
    const val SCAN = "scan"
    const val SETUP = "setup"
    const val SETTINGS = "settings"
    const val HISTORY = "history"
    const val SEARCH = "search"
    const val PHOTO = "photo?mode={mode}&kind={kind}&code={code}&name={name}"
    const val REVIEW = "review?kind={kind}&code={code}&note={note}&photo={photo}&name={name}&place={place}&slot={slot}&day={day}"

    fun photo(mode: PhotoMode, kind: PhotoKind, code: String? = null, name: String? = null) =
        "photo?mode=${mode.name}&kind=${kind.name}&code=${enc(code)}&name=${enc(name)}"

    /** Several photos travel as one argument, one Uri per line. */
    fun review(
        kind: String, code: String? = null, note: String = "", photos: List<Uri> = emptyList(), name: String? = null,
        place: String = "", slot: MealSlot? = null, day: LocalDate? = null,
    ) = "review?kind=$kind&code=${enc(code)}&note=${enc(note)}&photo=${enc(photos.joinToString("\n"))}&name=${enc(name)}" +
        "&place=$place&slot=${slot?.name.orEmpty()}&day=${day?.toString().orEmpty()}"

    private fun enc(v: String?) = Uri.encode(v.orEmpty())

    val photoArgs = listOf("mode", "kind", "code", "name").map { navArgument(it) { type = NavType.StringType; defaultValue = "" } }
    val reviewArgs = listOf("kind", "code", "note", "photo", "name", "place", "slot", "day").map { navArgument(it) { type = NavType.StringType; defaultValue = "" } }
}

private fun NavBackStackEntry.arg(name: String): String? = arguments?.getString(name)?.takeIf { it.isNotEmpty() }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as MealMapperApp).container
        setContent {
            MealMapperTheme {
                val nav = rememberNavController()
                var savedMessage by rememberSaveable { mutableStateOf<String?>(null) }
                val hasAiKey by container.aiSettings.hasKey.collectAsStateWithLifecycle()

                NavHost(navController = nav, startDestination = Routes.HOME) {
                    composable(Routes.HOME) {
                        val vm: HomeViewModel = viewModel(
                            factory = HomeViewModel.factory(container),
                        )
                        HomeScreen(
                            viewModel = vm,
                            savedMessage = savedMessage,
                            onMessageShown = { savedMessage = null },
                            onBarcode = { nav.navigate(Routes.SCAN) },
                            onCamera = { nav.navigate(Routes.photo(PhotoMode.CAMERA, PhotoKind.MEAL)) },
                            onUpload = { nav.navigate(Routes.photo(PhotoMode.UPLOAD, PhotoKind.MEAL)) },
                            onType = { nav.navigate(Routes.photo(PhotoMode.TYPE, PhotoKind.MEAL)) },
                            onSearch = { nav.navigate(Routes.SEARCH) },
                            onMenu = { nav.navigate(Routes.photo(PhotoMode.CAMERA, PhotoKind.MENU)) },
                            onSettings = { nav.navigate(Routes.SETTINGS) },
                            onHistory = { nav.navigate(Routes.HISTORY) },
                            onHealthCheck = { nav.navigate(Routes.SETUP) },
                        )
                    }
                    composable(Routes.SEARCH) {
                        SearchScreen(
                            db = container.foodDb,
                            onBack = { nav.popBackStack() },
                            onPick = { id, note, slot, day -> nav.navigate(Routes.review("food", code = id, note = note, slot = slot, day = day)) },
                        )
                    }
                    composable(Routes.HISTORY) {
                        val vm: HistoryViewModel = viewModel(factory = HistoryViewModel.factory(container))
                        HistoryScreen(vm, onBack = { nav.popBackStack() })
                    }
                    composable(Routes.SETTINGS) {
                        SettingsScreen(
                            store = container.profile,
                            ai = container.aiSettings,
                            gemini = container.gemini,
                            voice = container.deepgramSettings,
                            deepgram = container.deepgram,
                            onBack = { nav.popBackStack() },
                            onHealthCheck = { nav.navigate(Routes.SETUP) },
                        )
                    }
                    composable(Routes.SCAN) {
                        ScanScreen(
                            onBack = { nav.popBackStack() },
                            onBarcode = { code, note, slot, day ->
                                nav.navigate(Routes.review("barcode", code = code, note = note, slot = slot, day = day)) { launchSingleTop = true }
                            },
                        )
                    }
                    composable(Routes.PHOTO, arguments = Routes.photoArgs) { entry ->
                        val code = entry.arg("code")
                        val name = entry.arg("name")
                        PhotoScreen(
                            mode = PhotoMode.valueOf(entry.arg("mode") ?: PhotoMode.CAMERA.name),
                            initialKind = PhotoKind.valueOf(entry.arg("kind") ?: PhotoKind.LABEL.name),
                            hasAiKey = hasAiKey,
                            onBack = { nav.popBackStack() },
                            onSettings = { nav.navigate(Routes.SETTINGS) },
                            onAnalyse = { kind, photos, note, restaurant, restaurantName, slot, day ->
                                val reviewKind = when (kind) {
                                    PhotoKind.MEAL -> "meal"
                                    PhotoKind.MENU -> "menu"
                                    PhotoKind.LABEL -> "label"
                                    PhotoKind.PACK -> "web"
                                }
                                val place = if (restaurant) "restaurant" else "home"
                                // For meals and menus the name argument carries the restaurant name.
                                val reviewName = when {
                                    kind == PhotoKind.MENU || (kind == PhotoKind.MEAL && restaurant) -> restaurantName.ifBlank { null }
                                    kind == PhotoKind.MEAL -> null
                                    else -> name
                                }
                                nav.navigate(Routes.review(reviewKind, code = code, note = note, photos = photos, name = reviewName, place = place, slot = slot, day = day))
                            },
                        )
                    }
                    composable(Routes.REVIEW, arguments = Routes.reviewArgs) { entry ->
                        val note = entry.arg("note").orEmpty()
                        val code = entry.arg("code")
                        val name = entry.arg("name")
                        val photos = entry.arg("photo")?.split("\n")?.filter { it.isNotBlank() }?.map(Uri::parse).orEmpty()
                        val photo = photos.firstOrNull()
                        val request = when (entry.arg("kind")) {
                            "label" -> ReviewRequest.Label(photo!!, note, code, name)
                            "web" -> ReviewRequest.Web(photo, note, code, name)
                            "meal" -> ReviewRequest.Meal(photos, note, restaurant = entry.arg("place") == "restaurant", restaurantName = name)
                            "menu" -> ReviewRequest.Menu(photos, note, restaurantName = name)
                            "food" -> ReviewRequest.Food(code.orEmpty(), note)
                            else -> ReviewRequest.Barcode(code.orEmpty(), note)
                        }
                        val vm: ReviewViewModel = viewModel(
                            factory = ReviewViewModel.factory(
                                request,
                                entry.arg("slot")?.let { runCatching { MealSlot.valueOf(it) }.getOrNull() },
                                entry.arg("day")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                                applicationContext,
                                container,
                            ),
                        )
                        ReviewScreen(
                            viewModel = vm,
                            onBack = { nav.popBackStack() },
                            onSaved = { message ->
                                savedMessage = message
                                nav.popBackStack(Routes.HOME, inclusive = false)
                            },
                            onPhotograph = { kind, barcode, productName ->
                                nav.navigate(Routes.photo(PhotoMode.CAMERA, kind, barcode, productName))
                            },
                            onSettings = { nav.navigate(Routes.SETTINGS) },
                        )
                    }
                    composable(Routes.SETUP) {
                        val vm: SetupViewModel = viewModel(factory = SetupViewModel.factory(container.healthConnect))
                        SetupScreen(vm, container.healthConnect, onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }
}
