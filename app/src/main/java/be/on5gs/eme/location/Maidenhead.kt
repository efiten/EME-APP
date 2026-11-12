package be.on5gs.eme.location

/**
 * Omzetting van een Maidenhead grid locator (4 of 6 tekens) naar breedte/lengte.
 * Het resultaat is het MIDDEN van het vak.
 */
object Maidenhead {

    data class LatLon(val latDeg: Double, val lonDeg: Double)

    private val PATTERN_4 = Regex("^[A-R]{2}[0-9]{2}$")
    private val PATTERN_6 = Regex("^[A-R]{2}[0-9]{2}[A-X]{2}$")

    /** Zet invoer om naar de standaardnotatie: eerste 2 letters hoofdletters, laatste 2 kleine letters. */
    fun normalize(input: String): String {
        val s = input.trim()
        return if (s.length == 6) s.substring(0, 4).uppercase() + s.substring(4).lowercase() else s.uppercase()
    }

    fun isValid(input: String): Boolean {
        val s = input.trim().uppercase()
        return PATTERN_4.matches(s) || PATTERN_6.matches(s)
    }

    /** Geeft null terug als de locator ongeldig is. Lengte: oost positief. */
    fun toLatLon(input: String): LatLon? {
        if (!isValid(input)) return null
        val s = input.trim().uppercase()

        var lon = (s[0] - 'A') * 20.0 - 180.0 + (s[2] - '0') * 2.0
        var lat = (s[1] - 'A') * 10.0 - 90.0 + (s[3] - '0') * 1.0

        if (s.length == 6) {
            lon += (s[4] - 'A') * (5.0 / 60.0) + (2.5 / 60.0)     // subvak 5' breed, + halve breedte
            lat += (s[5] - 'A') * (2.5 / 60.0) + (1.25 / 60.0)    // subvak 2,5' hoog, + halve hoogte
        } else {
            lon += 1.0      // midden van een vak van 2 graden
            lat += 0.5      // midden van een vak van 1 graad
        }
        return LatLon(lat, lon)
    }
}
