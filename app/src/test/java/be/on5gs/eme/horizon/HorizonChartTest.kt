package be.on5gs.eme.horizon

import be.on5gs.eme.ExpectedCsv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Rekenwerk achter het scherm "Mijn horizon": invoer, schaal van de grafiek en de maanbaan. */
class HorizonChartTest {

    @Test
    fun anglesTypedInAFieldAsInTheSharedTable() {
        ExpectedCsv.read("horizon_input").forEach { r ->
            val got = parseAngle(r.getValue("text"), r.getValue("max").toDouble())
            val want = r.getValue("expected")
            if (want.isEmpty()) assertNull("'${r["text"]}'", got) else assertEquals("'${r["text"]}'", want.toDouble(), got!!, 0.0)
        }
    }

    @Test
    fun commaAsDecimalSeparator() {
        assertEquals(25.5, parseAngle("25,5", 90.0)!!, 0.0)
        assertNull(parseAngle("25,5,1", 90.0))
    }

    @Test
    fun chartTopIs40UnlessSomethingIsHigher() {
        assertEquals(40.0, chartTopDeg(Horizon()), 0.0)
        assertEquals(40.0, chartTopDeg(Horizon.decode("1;5;B 60 110 25;P 180 200 20")!!), 0.0)
        assertEquals(50.0, chartTopDeg(Horizon.decode("1;0;B 60 110 38")!!), 0.0)
        assertEquals(90.0, chartTopDeg(Horizon.decode("1;0;B 60 110 90")!!), 0.0)
        // De maan komt hoger: de schaal groeit mee, zodat de maanbaan niet afgesneden wordt
        assertEquals(60.0, chartTopDeg(Horizon.decode("1;5;B 60 110 25")!!, moonMaxElDeg = 55.3), 0.0)
        assertEquals(90.0, chartTopDeg(Horizon(), moonMaxElDeg = 88.0), 0.0)
    }

    @Test
    fun obstacleAcrossNorthIsDrawnInTwoPieces() {
        assertEquals(listOf(60.0 to 110.0), chartSpans(Obstacle(60.0, 110.0, 25.0, ObstacleKind.BLOCKED)))
        assertEquals(listOf(350.0 to 360.0, 0.0 to 10.0), chartSpans(Obstacle(350.0, 10.0, 15.0, ObstacleKind.BLOCKED)))
        assertEquals(listOf(0.0 to 360.0), chartSpans(Obstacle(0.0, 360.0, 5.0, ObstacleKind.BLOCKED)))
    }

    /** De maanbaan wordt alleen boven de horizon getekend, en opnieuw begonnen waar ze door het noorden gaat. */
    @Test
    fun moonTrackInPieces() {
        val start = Instant.parse("2026-11-20T00:00:00Z").toEpochMilli()
        val track = MoonWindows.track(51.1, 4.3, start, start + 86_400_000L)
        assertEquals(145, track.size)                                   // elke 10 minuten, begin en einde inbegrepen
        val segs = MoonWindows.trackSegments(track)
        assertTrue(segs.isNotEmpty())
        segs.flatten().forEach { assertTrue(it.elDeg > 0) }
        segs.forEach { seg -> seg.zipWithNext().forEach { (a, b) -> assertTrue(kotlin.math.abs(a.azDeg - b.azDeg) <= 180) } }
        // Op 20 november: onder om 01:36 en weer op om 13:39, dus twee stukken (en de baan van 's avonds gaat door het zuiden)
        assertEquals(2, segs.size)
        // Synthetisch: door het noorden heen geeft een nieuw stuk
        val p = { az: Double -> TrackPoint(0, az, 10.0) }
        assertEquals(2, MoonWindows.trackSegments(listOf(p(350.0), p(355.0), p(2.0), p(8.0))).size)
    }
}
