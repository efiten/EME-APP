package be.on5gs.eme.cq

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveCqMergeTest {

    private val t = 1_800_000_000_000L - 1_800_000_000_000L % 60_000   // begin van een minuut

    private fun spot(
        call: String, src: LiveCqSource, time: Long = t, spotter: String = "PA3DRK",
        grid: String = "", dt: String = "", app: String = "", f: Double = 1296.065,
    ) = LiveCqSpot(time, call, grid, f, Band.forFrequency(f), "Q65-60C", "-20", spotter, "JO21", setOf(src), dt, app)

    @Test
    fun sameDecodeOnBothSitesBecomesOneSpot() {
        val w = spot("OK1DFC", LiveCqSource.W3SZ, grid = "JO70FD", app = "MAP65")
        val n = spot("OK1DFC", LiveCqSource.N6NU, time = t + 30_000, dt = "2.5", app = "WSJT-X")
        val merged = LiveCqParser.merge(listOf(w), listOf(n))
        assertEquals(1, merged.size)
        val m = merged.single()
        assertEquals(setOf(LiveCqSource.W3SZ, LiveCqSource.N6NU), m.sources)
        assertEquals("W3SZ+N6NU", m.sourceLabel)
        assertEquals("JO70FD", m.grid)      // van W3SZ
        assertEquals("2.5", m.dt)           // van N6NU
        assertEquals("MAP65", m.app)        // W3SZ eerst
    }

    @Test
    fun otherSpotterBandOrMinuteStaysSeparate() {
        val w = spot("OK1DFC", LiveCqSource.W3SZ)
        val merged = LiveCqParser.merge(
            listOf(w),
            listOf(
                spot("OK1DFC", LiveCqSource.N6NU, spotter = "W3TI"),
                spot("OK1DFC", LiveCqSource.N6NU, f = 432.065),
                spot("OK1DFC", LiveCqSource.N6NU, time = t + 60_000),
            ),
        )
        assertEquals(4, merged.size)
    }

    @Test
    fun selectShowsAllSourcesAndKeepsALocator() {
        val older = spot("K3SK", LiveCqSource.W3SZ, grid = "FM07", spotter = "W3TI")
        val newer = spot("K3SK", LiveCqSource.N6NU, time = t + 120_000, grid = "")
        val shown = LiveCqParser.select(
            LiveCqParser.merge(listOf(older), listOf(newer)), setOf(Band.B23CM), t + 180_000, 60,
        )
        assertEquals(1, shown.size)
        val s = shown.single()
        assertEquals(t + 120_000, s.utcMillis)                                   // de nieuwste
        assertEquals("FM07", s.grid)                                             // locator uit de oudere
        assertEquals(setOf(LiveCqSource.W3SZ, LiveCqSource.N6NU), s.sources)     // gezien op beide sites
    }

    @Test
    fun w3szCsvHasDtAndProgram() {
        val csv = javaClass.classLoader!!.getResource("livecq_sample.csv")!!.readText()
        val first = LiveCqParser.parse(csv).first()
        assertEquals("2.7", first.dt)
        assertEquals("MAP65", first.app)
        assertEquals(setOf(LiveCqSource.W3SZ), first.sources)
    }
}
