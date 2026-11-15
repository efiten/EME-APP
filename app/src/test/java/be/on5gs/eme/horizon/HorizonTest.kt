package be.on5gs.eme.horizon

import be.on5gs.eme.ExpectedCsv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** De eigen horizon: zelfde referentiewaarden als de webversie (expected/horizon_*.csv). */
class HorizonTest {

    @Test
    fun visibilityAsInTheSharedTable() {
        val rows = ExpectedCsv.read("horizon_visibility")
        rows.forEach { r ->
            val h = Horizon.decode(r.getValue("horizon"))!!
            val got = h.visibility(r.getValue("az").toDouble(), r.getValue("el").toDouble(), r.getValue("margin").toDouble())
            assertEquals("$r", Visibility.valueOf(r.getValue("expected")), got)
        }
        assert(rows.size >= 25)
    }

    @Test
    fun storedTextFormatAsInTheSharedTable() {
        ExpectedCsv.read("horizon_codec").forEach { r ->
            val decoded = Horizon.decode(r.getValue("text"))
            val canonical = r.getValue("canonical")
            if (canonical.isEmpty()) assertNull("${r["text"]} must be rejected", decoded)
            else assertEquals("${r["text"]}", canonical, decoded!!.encode())
        }
    }

    @Test
    fun encodeAndDecodeKeepEverything() {
        val h = Horizon(
            minElDeg = 4.5,
            obstacles = listOf(
                Obstacle(350.0, 10.0, 15.25, ObstacleKind.BLOCKED),
                Obstacle(180.0, 200.0, 20.0, ObstacleKind.PARTIAL),
            ),
        )
        assertEquals("1;4.5;B 350 10 15.25;P 180 200 20", h.encode())
        assertEquals(h, Horizon.decode(h.encode()))
        assertEquals("1;0", Horizon().encode())
    }

    @Test
    fun halfBeamwidthOfASixMetreDish() {
        assertEquals(1.35, halfBeamwidthDeg(1296.0, 6.0), 0.01)
        assertEquals(4.05, halfBeamwidthDeg(432.0, 6.0), 0.01)
    }
}
