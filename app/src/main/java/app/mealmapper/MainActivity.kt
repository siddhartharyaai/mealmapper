package app.mealmapper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import app.mealmapper.ui.setup.SetupScreen
import app.mealmapper.ui.setup.SetupViewModel
import app.mealmapper.ui.theme.MealMapperTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as MealMapperApp).container
        setContent {
            MealMapperTheme {
                val vm: SetupViewModel = viewModel(factory = SetupViewModel.factory(container.healthConnect))
                SetupScreen(vm, container.healthConnect)
            }
        }
    }
}
