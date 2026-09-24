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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.mealmapper.ui.home.HomeScreen
import app.mealmapper.ui.home.HomeViewModel
import app.mealmapper.ui.review.ReviewScreen
import app.mealmapper.ui.review.ReviewViewModel
import app.mealmapper.ui.scan.ScanScreen
import app.mealmapper.ui.settings.SettingsScreen
import app.mealmapper.ui.setup.SetupScreen
import app.mealmapper.ui.setup.SetupViewModel
import app.mealmapper.ui.theme.MealMapperTheme

private object Routes {
    const val HOME = "home"
    const val SCAN = "scan"
    const val SETUP = "setup"
    const val SETTINGS = "settings"
    const val REVIEW = "review/{barcode}?note={note}"
    fun review(barcode: String, note: String) = "review/$barcode?note=${Uri.encode(note)}"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as MealMapperApp).container
        setContent {
            MealMapperTheme {
                val nav = rememberNavController()
                var savedMessage by rememberSaveable { mutableStateOf<String?>(null) }

                NavHost(navController = nav, startDestination = Routes.HOME) {
                    composable(Routes.HOME) {
                        val vm: HomeViewModel = viewModel(
                            factory = HomeViewModel.factory(container.healthConnect, container.profile),
                        )
                        HomeScreen(
                            viewModel = vm,
                            savedMessage = savedMessage,
                            onMessageShown = { savedMessage = null },
                            onBarcode = { nav.navigate(Routes.SCAN) },
                            onSettings = { nav.navigate(Routes.SETTINGS) },
                            onHealthCheck = { nav.navigate(Routes.SETUP) },
                        )
                    }
                    composable(Routes.SETTINGS) {
                        SettingsScreen(
                            store = container.profile,
                            onBack = { nav.popBackStack() },
                            onHealthCheck = { nav.navigate(Routes.SETUP) },
                        )
                    }
                    composable(Routes.SCAN) {
                        ScanScreen(
                            onBack = { nav.popBackStack() },
                            onBarcode = { code, note -> nav.navigate(Routes.review(code, note)) { launchSingleTop = true } },
                        )
                    }
                    composable(
                        Routes.REVIEW,
                        arguments = listOf(
                            navArgument("barcode") { type = NavType.StringType },
                            navArgument("note") {
                                type = NavType.StringType
                                defaultValue = ""
                            },
                        ),
                    ) { entry ->
                        val barcode = entry.arguments?.getString("barcode").orEmpty()
                        val note = entry.arguments?.getString("note").orEmpty()
                        val vm: ReviewViewModel = viewModel(
                            factory = ReviewViewModel.factory(barcode, note, container.openFoodFacts, container.healthConnect),
                        )
                        ReviewScreen(
                            viewModel = vm,
                            onBack = { nav.popBackStack() },
                            onSaved = { message ->
                                savedMessage = message
                                nav.popBackStack(Routes.HOME, inclusive = false)
                            },
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
