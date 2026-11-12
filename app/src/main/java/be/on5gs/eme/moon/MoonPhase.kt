package be.on5gs.eme.moon

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Maanfase (Meeus hfst. 25 voor de zon, hfst. 48 voor de verlichte fractie).
 */
object MoonPhase {

    private const val AU_KM = 149_597_870.7

    data class Phase(
        /** Verlichte fractie 0..1 (0 = nieuwe maan, 1 = volle maan). */
        val illuminated: Double,
        /** Leeftijd als hoek 0..360: 0 = nieuw, 90 = eerste kwartier, 180 = vol, 270 = laatste kwartier. */
        val ageDeg: Double,
    ) {
        /** Wassend (van nieuwe naar volle maan): verlichte kant rechts, gezien vanaf het noordelijk halfrond. */
        val waxing: Boolean get() = ageDeg < 180.0

        /** 0 nieuw, 1 wassende sikkel, 2 eerste kwartier, 3 wassend, 4 vol, 5 afnemend, 6 laatste kwartier, 7 afnemende sikkel. */
        val nameIndex: Int get() = (Math.round(ageDeg / 45.0).toInt()) % 8
    }

    /** Ware ecliptische lengte van de zon (graden) en afstand (km), Meeus hfst. 25 (lage nauwkeurigheid). */
    fun sun(jde: Double): Pair<Double, Double> {
        val t = (jde - 2_451_545.0) / 36_525.0
        val l0 = 280.46646 + 36000.76983 * t + 0.0003032 * t * t
        val m = 357.52911 + 35999.05029 * t - 0.0001537 * t * t
        val e = 0.016708634 - 0.000042037 * t
        val c = (1.914602 - 0.004817 * t - 0.000014 * t * t) * sinD(m) +
            (0.019993 - 0.000101 * t) * sinD(2 * m) + 0.000289 * sinD(3 * m)
        val lon = norm(l0 + c)
        val v = m + c
        val r = 1.000001018 * (1 - e * e) / (1 + e * cosD(v))
        return lon to r * AU_KM
    }

    fun at(epochMillis: Long): Phase {
        val jde = MoonCalculator.julianDay(epochMillis) + 69.2 / 86_400.0
        val moon = MoonCalculator.eclipticPosition(jde)
        val (sunLon, sunDist) = sun(jde)
        val age = norm(moon.lonDeg - sunLon)
        // Elongatie en fasehoek (Meeus 48.2 en 48.3)
        val cosPsi = cosD(moon.latDeg) * cosD(moon.lonDeg - sunLon)
        val psi = Math.acos(cosPsi.coerceIn(-1.0, 1.0))
        val i = atan2(sunDist * sin(psi), moon.distanceKm - sunDist * cos(psi))
        return Phase(illuminated = (1 + cos(i)) / 2, ageDeg = age)
    }

    private fun norm(d: Double): Double { val r = d % 360.0; return if (r < 0) r + 360.0 else r }
    private fun sinD(d: Double) = sin(d * PI / 180.0)
    private fun cosD(d: Double) = cos(d * PI / 180.0)
}
