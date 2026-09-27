package app.mealmapper

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.mealmapper.data.chat.Notifier
import app.mealmapper.ui.chat.ChatScreen
import kotlinx.coroutines.flow.MutableStateFlow
import app.mealmapper.ui.chat.ChatViewModel
import app.mealmapper.ui.history.HistoryScreen
import app.mealmapper.ui.history.HistoryViewModel
import app.mealmapper.ui.scan.ScanScreen
import app.mealmapper.ui.settings.SettingsScreen
import app.mealmapper.ui.setup.SetupScreen
import app.mealmapper.ui.setup.SetupViewModel
import app.mealmapper.ui.theme.MealMapperTheme

private object Routes {
    const val CHAT = "chat"
    const val SCAN = "scan"
    const val SETUP = "setup"
    const val SETTINGS = "settings"
    const val HISTORY = "history"
    const val BARCODE_KEY = "barcode"
}

/** Four screens: the chat (everything is logged there), History, Settings, and the barcode scanner. */
class MainActivity : ComponentActivity() {
    /** "speak" or "type" from the widget or an app shortcut, until the chat has acted on it. */
    private val launchAction = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) launchAction.value = actionOf(intent)
        val container = (application as MealMapperApp).container
        setContent {
            MealMapperTheme {
                val nav = rememberNavController()
                val action by launchAction.collectAsStateWithLifecycle()
                // Widget tapped while another screen is open: go back to the chat.
                LaunchedEffect(action) { if (action != null) runCatching { nav.popBackStack(Routes.CHAT, inclusive = false) } }
                NavHost(navController = nav, startDestination = Routes.CHAT) {
                    composable(Routes.CHAT) { entry ->
                        val vm: ChatViewModel = viewModel(factory = ChatViewModel.factory(applicationContext, container))
                        val barcode by entry.savedStateHandle.getStateFlow<String?>(Routes.BARCODE_KEY, null).collectAsStateWithLifecycle()
                        ChatScreen(
                            vm = vm,
                            prefs = container.prefs,
                            scannedBarcode = barcode,
                            onBarcodeConsumed = { entry.savedStateHandle[Routes.BARCODE_KEY] = null },
                            launchAction = action,
                            onLaunchConsumed = { launchAction.value = null },
                            onScan = { nav.navigate(Routes.SCAN) },
                            onHistory = { nav.navigate(Routes.HISTORY) },
                            onSettings = { nav.navigate(Routes.SETTINGS) },
                            onHealthCheck = { nav.navigate(Routes.SETUP) },
                        )
                    }
                    composable(Routes.SCAN) {
                        ScanScreen(
                            onBack = { nav.popBackStack() },
                            onBarcode = { code ->
                                nav.previousBackStackEntry?.savedStateHandle?.set(Routes.BARCODE_KEY, code)
                                nav.popBackStack()
                            },
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
                            memory = container.memory,
                            webCache = container.webCache,
                            prefs = container.prefs,
                            onBack = { nav.popBackStack() },
                            onHealthCheck = { nav.navigate(Routes.SETUP) },
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        actionOf(intent)?.let { launchAction.value = it }
    }

    override fun onStart() {
        super.onStart()
        Notifier.appVisible = true
    }

    override fun onStop() {
        super.onStop()
        Notifier.appVisible = false
    }

    private fun actionOf(intent: Intent?): String? = when (intent?.action) {
        ACTION_SPEAK -> "speak"
        ACTION_TYPE -> "type"
        else -> null
    }

    companion object {
        const val ACTION_SPEAK = "app.mealmapper.SPEAK"
        const val ACTION_TYPE = "app.mealmapper.TYPE"
    }
}
