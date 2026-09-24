package app.mealmapper

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
import app.mealmapper.ui.review.ReviewScreen
import app.mealmapper.ui.review.ReviewViewModel
import app.mealmapper.ui.scan.ScanScreen
import app.mealmapper.ui.setup.SetupScreen
import app.mealmapper.ui.setup.SetupViewModel
import app.mealmapper.ui.theme.MealMapperTheme

private object Routes {
    const val HOME = "home"
    const val SCAN = "scan"
    const val SETUP = "setup"
    const val REVIEW = "review/{barcode}"
    fun review(barcode: String) = "review/$barcode"
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
                        HomeScreen(
                            healthConnect = container.healthConnect,
                            savedMessage = savedMessage,
                            onMessageShown = { savedMessage = null },
                            onBarcode = { nav.navigate(Routes.SCAN) },
                            onSetup = { nav.navigate(Routes.SETUP) },
                        )
                    }
                    composable(Routes.SCAN) {
                        ScanScreen(
                            onBack = { nav.popBackStack() },
                            onBarcode = { code -> nav.navigate(Routes.review(code)) { launchSingleTop = true } },
                        )
                    }
                    composable(
                        Routes.REVIEW,
                        arguments = listOf(navArgument("barcode") { type = NavType.StringType }),
                    ) { entry ->
                        val barcode = entry.arguments?.getString("barcode").orEmpty()
                        val vm: ReviewViewModel = viewModel(
                            factory = ReviewViewModel.factory(barcode, container.openFoodFacts, container.healthConnect),
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
