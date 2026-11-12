package be.on5gs.eme.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * Negeert een vergrote systeem-lettergrootte (Instellingen > Weergave > Lettergrootte) voor
 * onderdelen die op een vaste breedte moeten passen, zoals de tabbalk en de Chat CQ-tabel.
 * Kleiner dan normaal blijft wel toegestaan.
 */
@Composable
fun FixedFontScale(content: @Composable () -> Unit) {
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density, minOf(d.fontScale, 1f))) {
        content()
    }
}
