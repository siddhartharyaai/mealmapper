package app.mealmapper.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle

// Fallback palette for phones without dynamic color (Android 11 and lower).
private val Light = lightColorScheme(
    primary = Color(0xFF1E4636),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC9E8D6),
    onPrimaryContainer = Color(0xFF0A2016),
    secondary = Color(0xFF8A5A00),
    secondaryContainer = Color(0xFFFFDDA6),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9FD2B5),
    onPrimary = Color(0xFF0A2016),
    primaryContainer = Color(0xFF2E5A47),
    onPrimaryContainer = Color(0xFFC9E8D6),
    secondary = Color(0xFFF2B84B),
    secondaryContainer = Color(0xFF5A3D00),
)

@Composable
fun MealMapperTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Same-width digits so numbers line up in columns. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")
