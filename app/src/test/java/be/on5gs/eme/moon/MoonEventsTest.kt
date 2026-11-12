package be.on5gs.eme.moon

import be.on5gs.eme.location.Maidenhead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

class MoonEventsTest {

    private fun millis(iso: String) = Instant.parse(iso).toEpochMilli()
    private val jo21sc = Maidenhead.toLatLon("JO21SC")!!

    private fun assertTime(expectedIso: String, actual: Long?, toleranceSec: Long) {
        assertNotNull(actual)
        val diff = abs(actual!! - millis(expectedIso)) / 1000
        assertTrue("verschil $diff s met $expectedIso (was ${Instant.ofEpochMilli(actual)})", diff <= toleranceSec)
    }

    /** Referentie: astropy 8, topocentrisch, midden van de maan op 0°, zonder refractie. */
    @Test
    fun moonDown_nextPass() {
        val ev = MoonEvents.compute(jo21sc.latDeg, jo21sc.lonDeg, millis("2026-11-06T20:00:00Z"))
        assertFalse(ev.isUp)
        assertTime("2026-11-07T04:48:02Z", ev.nextRise?.utcMillis, 60)
        assertTime("2026-11-07T14:41:27Z", ev.nextSet?.utcMillis, 60)
        assertTime("2026-11-07T09:46:00Z", ev.culmination?.utcMillis, 180)
        assertEquals(25.16, ev.culmination!!.elevationDeg, 0.05)
        assertEquals(110.2, ev.nextRise!!.azimuthDeg, 0.2)
        assertEquals(246.1, ev.nextSet!!.azimuthDeg, 0.2)
    }

    @Test
    fun moonUp_showsNextRise() {
        val ev = MoonEvents.compute(jo21sc.latDeg, jo21sc.lonDeg, millis("2026-11-07T10:00:00Z"))
        assertTrue(ev.isUp)
        assertTime("2026-11-07T14:41:27Z", ev.nextSet?.utcMillis, 60)
        assertTime("2026-11-08T06:04:49Z", ev.nextRise?.utcMillis, 60)      // de VOLGENDE opkomst
        assertTime("2026-11-07T09:46:00Z", ev.culmination?.utcMillis, 180)  // culminatie van de huidige doorgang
    }

    @Test
    fun subLunarPoint_hasMoonInZenith() {
        for (iso in listOf("2026-11-07T10:00:00Z", "2026-01-01T00:00:00Z", "2025-06-15T18:30:00Z")) {
            val t = millis(iso)
            val sub = MoonCalculator.subLunarPoint(t)
            val el = MoonCalculator.position(sub.latDeg, sub.lonDeg, t).elevationDeg
            assertTrue("EL bij sub-lunair punt $iso was $el", el > 89.5)
        }
    }

    @Test
    fun quickElevation_matchesFullCalculation() {
        val t = millis("2026-11-07T14:00:00Z")
        val sub = MoonCalculator.subLunarPoint(t)
        for ((lat, lon) in listOf(51.1 to 5.5, 40.5 to -75.0, -37.5 to 145.0, 64.0 to -20.0)) {
            val full = MoonCalculator.position(lat, lon, t).elevationDeg
            assertEquals("lat $lat lon $lon", full, MoonCalculator.quickElevationDeg(sub, lat, lon), 0.1)
        }
    }

    @Test
    fun footprint_coversAboutHalfTheEarth() {
        val sub = MoonCalculator.subLunarPoint(millis("2026-11-07T14:00:00Z"))
        val runs = footprintRuns(sub)
        // Oppervlakte (gewogen met cos(breedte)) van het maangebied: iets minder dan de helft van de aarde
        var area = 0.0
        for (r in runs) area += (r.lonTo - r.lonFrom) * 1.5 * Math.cos(Math.toRadians(r.latTop - 0.75))
        val total = 360.0 * 180.0 * 2 / Math.PI
        assertEquals(0.49, area / total, 0.02)
        assertTrue(horizonEdge(sub).isNotEmpty())
    }

    @Test
    fun landShapesAsset() {
        val land = LandShapes.parse(File("src/main/assets/land110m.txt").readLines().asSequence())
        assertEquals(129, land.size)
        assertTrue(land.all { it.size >= 8 && it.size % 2 == 0 })
        assertTrue(land.all { ring -> (1 until ring.size step 2).all { ring[it] in -90f..90f } })
    }

    @Test
    fun localTimeLabels() {
        val zone = ZoneId.of("Europe/Brussels")
        val now = millis("2026-11-07T10:00:00Z")                         // 11:00 lokaal
        val nl = java.util.Locale("nl")
        assertEquals("21:45", localTime(millis("2026-11-07T20:45:00Z"), now, zone, "morgen", "gisteren", nl))
        assertEquals("morgen 07:04", localTime(millis("2026-11-08T06:04:49Z"), now, zone, "morgen", "gisteren", nl))
        assertEquals("gisteren 23:10", localTime(millis("2026-11-06T22:10:00Z"), now, zone, "morgen", "gisteren", nl))
        assertEquals("tomorrow 07:04", localTime(millis("2026-11-08T06:04:49Z"), now, zone, "tomorrow", "yesterday", java.util.Locale.ENGLISH))
    }
}
