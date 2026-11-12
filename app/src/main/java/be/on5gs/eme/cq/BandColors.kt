package be.on5gs.eme.cq

import androidx.compose.ui.graphics.Color

/**
 * Kleur per band, gebruikt op de wereldkaart en in de legende.
 * Gekozen om goed leesbaar te zijn op donkerblauw én op het gele maangebied.
 */
val Band.color: Color
    get() = when (this) {
        Band.B50 -> Color(0xFFE040FB)     // paars
        Band.B144 -> Color(0xFFFF5252)    // rood
        Band.B222 -> Color(0xFFFFAB40)    // oranje
        Band.B432 -> Color(0xFF40C4FF)    // lichtblauw
        Band.B902 -> Color(0xFFB2FF59)    // limoen
        Band.B23CM -> Color(0xFF00E676)   // groen
        Band.B13CM -> Color(0xFFFF80AB)   // roze
        Band.B9CM -> Color(0xFF18FFFF)    // cyaan
        Band.B6CM -> Color(0xFFFFD180)    // perzik
        Band.B3CM -> Color(0xFFB388FF)    // lila
        Band.B1CM -> Color(0xFFFFFFFF)    // wit
    }
