package app.weathertrack.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Amber "take a jacket" accent from the mockups. */
val JacketAmber = Color(0xFFF2A900)
val JacketAmberContainer = Color(0xFFFFE3A3)
val OnJacketAmberContainer = Color(0xFF3D2A00)
val ClearBlueContainer = Color(0xFFD6E4FF)
val OnClearBlueContainer = Color(0xFF0A2A5C)

private val Light = lightColorScheme(
    primary = Color(0xFF1E5AA8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E4FF),
    onPrimaryContainer = Color(0xFF0A2A5C),
    secondary = Color(0xFF7A5900),
    secondaryContainer = JacketAmberContainer,
    onSecondaryContainer = OnJacketAmberContainer,
    background = Color(0xFFF8F9FF),
    surface = Color(0xFFF8F9FF),
    surfaceVariant = Color(0xFFE0E2EC),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFAAC7FF),
    onPrimary = Color(0xFF002F65),
    primaryContainer = Color(0xFF0F4586),
    onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = Color(0xFFF2C14E),
    secondaryContainer = Color(0xFF5C4300),
    onSecondaryContainer = Color(0xFFFFE3A3),
    background = Color(0xFF111318),
    surface = Color(0xFF111318),
)

@Composable
fun WeatherTrackTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
