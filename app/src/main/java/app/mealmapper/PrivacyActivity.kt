package app.mealmapper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.mealmapper.ui.theme.MealMapperTheme

/** Health Connect opens this screen when the user asks why Meal Mapper wants access. */
class PrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MealMapperTheme {
                Scaffold { padding ->
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("How Meal Mapper uses your data", style = MaterialTheme.typography.headlineSmall)
                        POINTS.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
                        TextButton(onClick = ::finish) { Text("Close") }
                    }
                }
            }
        }
    }

    private companion object {
        val POINTS = listOf(
            "Meal Mapper writes the food you confirm to Health Connect: calories, protein, carbs, fat, " +
                "sugar, fibre and sodium.",
            "It reads nutrition for today only, to show calories eaten and left. It reads no other health data.",
            "Your food history and settings (age, weight, calorie cap) stay on this phone.",
            "When you scan a barcode, the barcode number goes to Open Food Facts to look up the product.",
            "When you use a photo or the web lookup, the photo, your note and the product name go to Groq " +
                "to read the label or search the web. Nothing else is sent.",
            "Your Groq API key is encrypted on this phone and sent only to Groq.",
            "No account, no ads, no analytics.",
        )
    }
}
