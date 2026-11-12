package be.on5gs.eme.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Donker thema, rustig voor de ogen in de shack. */
private val ShackColors = darkColorScheme(
    primary = Color(0xFFFFB74D),       // warm oranje
    secondary = Color(0xFF90CAF9),     // zacht blauw
    // Donkerblauwe achtergrond, kaarten en balken iets lichter blauw
    background = Color(0xFF0B1A33),
    surface = Color(0xFF0B1A33),
    surfaceVariant = Color(0xFF1A2E50),
    surfaceContainerLowest = Color(0xFF081429),
    surfaceContainerLow = Color(0xFF0F2140),
    surfaceContainer = Color(0xFF132646),
    surfaceContainerHigh = Color(0xFF172B4D),
    surfaceContainerHighest = Color(0xFF1B3054),
    onBackground = Color(0xFFE6E6E6),
    onSurface = Color(0xFFE6E6E6),
)

@Composable
fun EmeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ShackColors, content = content)
}
