package be.on5gs.eme.horizon

import be.on5gs.eme.ExpectedCsv
import be.on5gs.eme.location.Maidenhead
import be.on5gs.eme.moon.MoonCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs

class MoonWindowsTest {

    private data class W(val startMin: Int, val endMin: Int, val v: Char)

    private fun parse(text: String): List<W> = text.split(" ").filter { it.isNotEmpty() }.map { w ->
        val (range, v) = w.split("/")
        val (a, b) = range.split("-")
        fun min(hm: String) = hm.split(":").let { it[0].toInt() * 60 + it[1].toInt() }
        W(min(a), min(b), v.single())
    }

    /** Zelfde tabel als de webtests; elke grens tot op 1 minuut. */
    @Test
    fun windowsAsInTheSharedTable() {
        ExpectedCsv.read("horizon_windows").forEach { r ->
            val h = Horizon.decode(r.getValue("horizon"))!!
            val start = LocalDate.parse(r.getValue("date")).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
            val lat = r.getValue("lat").toDouble(); val lon = r.getValue("lon").toDouble(); val m = r.getValue("margin").toDouble()
            val got = if (r.getValue("kind") == "all") MoonWindows.windows(h, lat, lon, start, start + DAY, m)
                      else MoonWindows.usable(h, lat, lon, start, start + DAY, m)
            val want = parse(r.getValue("windows"))
            assertEquals("$r", want.size, got.size)
            want.zip(got).forEach { (w, g) ->
                assertTrue("$r start", abs(w.startMin - ((g.startMillis - start) / 60_000).toInt()) <= 1)
                assertTrue("$r end", abs(w.endMin - ((g.endMillis - start) / 60_000).toInt()) <= 1)
                assertEquals("$r", w.v, g.visibility.name.first())
            }
        }
    }

    /** Zonder eigen horizon: het venster loopt van opkomst tot ondergang volgens astropy (expected/moon_events.csv). */
    @Test
    fun withoutOwnHorizonTheWindowIsMoonriseToMoonsetOfAstropy() {
        val pos = Maidenhead.toLatLon("JO21SC")!!
        val start = Instant.parse("2026-11-07T00:00:00Z").toEpochMilli()
        val w = MoonWindows.usable(Horizon(), pos.latDeg, pos.lonDeg, start, start + DAY).single()
        assertTrue(abs(w.startMillis - Instant.parse("2026-11-07T04:48:02Z").toEpochMilli()) <= 61_000)
        assertTrue(abs(w.endMillis - Instant.parse("2026-11-07T14:41:27Z").toEpochMilli()) <= 61_000)
    }

    /** Elk stuk heeft over zijn hele lengte het zicht dat erbij staat, en de stukken sluiten aan. */
    @Test
    fun everyMinuteOfAWindowHasItsVisibility() {
        val h = Horizon.decode("1;5;B 60 110 25;P 180 200 20")!!
        val start = Instant.parse("2027-01-04T00:00:00Z").toEpochMilli()
        val all = MoonWindows.windows(h, 51.1, 4.3, start, start + DAY)
        assertEquals(start, all.first().startMillis)
        assertEquals(start + DAY, all.last().endMillis)
        all.zipWithNext().forEach { (a, b) -> assertEquals(a.endMillis, b.startMillis) }
        all.forEach { w ->
            var t = w.startMillis
            while (t < w.endMillis) {
                val p = MoonCalculator.position(51.1, 4.3, t)
                assertEquals(w.visibility, h.visibility(p.azimuthDeg, p.elevationDeg))
                t += 60_000
            }
        }
    }

    /** Het venster waarin de maan nu staat, of het volgende (zelfde tabel als de webtests). */
    @Test
    fun currentOrNextAsInTheSharedTable() {
        ExpectedCsv.read("horizon_next").forEach { r ->
            val h = Horizon.decode(r.getValue("horizon"))!!
            val w = MoonWindows.currentOrNext(h, 51.1, 4.3, Instant.parse(r.getValue("now_utc")).toEpochMilli(), r.getValue("margin").toDouble())
            if (r.getValue("start_utc").isEmpty()) {
                assertEquals("$r", null, w)
            } else {
                assertTrue("$r start", abs(w!!.startMillis - Instant.parse(r.getValue("start_utc")).toEpochMilli()) <= 61_000)
                assertTrue("$r end", abs(w.endMillis - Instant.parse(r.getValue("end_utc")).toEpochMilli()) <= 61_000)
                assertEquals("$r", Visibility.valueOf(r.getValue("visibility")), w.visibility)
            }
        }
    }

    @Test
    fun marginOfADish() {
        assertEquals(1.349, beamMarginDeg(6.0, 1296.0), 0.001)
        assertEquals(0.0, beamMarginDeg(0.0, 1296.0), 0.0)        // geen schotel: geen marge
    }

    @Test
    fun defaultHorizonIsRecognised() {
        assertTrue(Horizon().isDefault)
        assertTrue(Horizon.decode("1;0")!!.isDefault)
        assertTrue(!Horizon.decode("1;5")!!.isDefault)
        assertTrue(!Horizon.decode("1;0;P 180 200 20")!!.isDefault)
    }

    /** Een maan die de hele dag achter een hindernis blijft, geeft geen venster. */
    @Test
    fun moonBehindTheTreeAllDayGivesNoWindow() {
        val start = Instant.parse("2026-11-20T00:00:00Z").toEpochMilli()
        assertTrue(MoonWindows.usable(Horizon(minElDeg = 89.0), 51.1, 4.3, start, start + DAY).isEmpty())
    }

    private companion object { const val DAY = 86_400_000L }
}
