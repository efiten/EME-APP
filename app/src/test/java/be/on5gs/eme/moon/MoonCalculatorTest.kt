package be.on5gs.eme.moon

import be.on5gs.eme.location.Maidenhead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.abs

class MoonCalculatorTest {

    /** Kleinste hoekverschil, rekening houdend met 0/360. */
    private fun angleDiff(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return if (d > 180) 360 - d else d
    }

    private fun millis(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun julianDay_ofJ2000() {
        assertEquals(2_451_545.0, MoonCalculator.julianDay(millis("2000-01-01T12:00:00Z")), 1e-9)
    }

    /** Meeus, voorbeeld 47.a: 1992 april 12, 0h TD. */
    @Test
    fun meeusExample47a_ecliptic() {
        val e = MoonCalculator.eclipticPosition(2_448_724.5)
        assertEquals(133.162655, e.lonDeg, 1e-5)
        assertEquals(-3.229126, e.latDeg, 1e-5)
        assertEquals(368_409.7, e.distanceKm, 0.1)
    }

    @Test
    fun meeusExample47a_equatorial() {
        val q = MoonCalculator.equatorialPosition(2_448_724.5)
        // Meeus: α = 134.688470°, δ = 13.768368° (met volledige nutatie; wij gebruiken de verkorte)
        assertEquals(134.688470, q.raDeg, 0.001)
        assertEquals(13.768368, q.decDeg, 0.001)
    }

    /** Meeus, voorbeeld 12.b: schijnbare sterrentijd 1987 april 10, 19h21m UT. */
    @Test
    fun siderealTime_meeusExample12b() {
        val jd = MoonCalculator.julianDay(millis("1987-04-10T19:21:00Z"))
        val expected = (8 + 34 / 60.0 + 56.853 / 3600.0) * 15.0 // 8h34m56.853s, schijnbaar
        assertEquals(expected, MoonCalculator.apparentSiderealTimeDeg(jd), 0.0005)
    }

    /**
     * Referentiewaarden berekend met astropy 8 (topocentrisch, zonder refractie).
     * Toegelaten verschil 0,05 graad: ruim onder de 0,1 graad die op het scherm staat.
     */
    @Test
    fun azEl_matchesAstropyReference() {
        data class Case(val loc: String, val utc: String, val az: Double, val el: Double)
        val cases = listOf(
            Case("JO21SC", "2026-11-07T10:00:00Z", 182.374, 25.084),
            Case("JO21SC", "2026-11-07T14:00:00Z", 238.193, 5.706),
            Case("JO21SC", "2026-11-06T20:00:00Z", 329.238, -45.671),
            Case("JO21SC", "2026-11-07T03:30:00Z", 95.439, -11.308),
            Case("JO21SC", "2025-03-14T12:00:00Z", 1.358, -38.133),
            Case("FN20", "2026-11-06T23:00:00Z", 283.229, -31.427),
            Case("QF22", "2026-01-01T10:00:00Z", 32.039, 16.426),
        )
        for (c in cases) {
            val p = Maidenhead.toLatLon(c.loc)!!
            val m = MoonCalculator.position(p.latDeg, p.lonDeg, millis(c.utc))
            val msg = "${c.loc} ${c.utc}: AZ ${m.azimuthDeg} / EL ${m.elevationDeg}"
            assertTrue("AZ $msg", angleDiff(c.az, m.azimuthDeg) < 0.05)
            assertEquals("EL $msg", c.el, m.elevationDeg, 0.05)
        }
    }

    @Test
    fun aboveHorizonFlag() {
        val p = Maidenhead.toLatLon("JO21SC")!!
        assertTrue(MoonCalculator.position(p.latDeg, p.lonDeg, millis("2026-11-07T10:00:00Z")).isAboveHorizon)
        assertFalse(MoonCalculator.position(p.latDeg, p.lonDeg, millis("2026-11-06T20:00:00Z")).isAboveHorizon)
    }
}
