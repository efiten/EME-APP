package be.on5gs.eme.degradation

import be.on5gs.eme.ExpectedCsv
import be.on5gs.eme.moon.MoonPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class DegradationTest {

    private fun dailyFor(row: Map<String, String>, days: Int) = Degradation.daily(
        LocalDate.parse(row.getValue("date")), days, row.getValue("lat").toDouble(), row.getValue("lon").toDouble(),
        EmeBand.values().first { it.label == row.getValue("band") }, row.getValue("tsys").toDouble(),
    )

    /**
     * Referentie: MMMonVHF eme.php (waarnemer 48N 11E, 12:00 UTC, 144 MHz, Tsys 80 K), bekeken op 10 november 2026.
     * "Todays conditions" komt uit hetzelfde script als deze berekening: moet exact kloppen.
     */
    @Test
    fun matchesMmmonvhfToday() {
        val row = ExpectedCsv.read("degradation_daily").single { it.getValue("condition").isNotEmpty() }
        val r = dailyFor(row, 1).single().second
        assertEquals(row.getValue("deg_db").toDouble(), r.degDb, row.getValue("tol").toDouble())
        assertEquals(Condition.valueOf(row.getValue("condition")), r.condition)
    }

    /**
     * De lijst "Next 30 days lowest degradation" maakt de MMMonVHF-server met een eigen (niet zichtbare)
     * berekening; die wijkt tot ~0,3 dB af van het script op de pagina. Hier: binnen 0,35 dB.
     */
    @Test
    fun closeToMmmonvhfServerList() {
        val rows = ExpectedCsv.read("degradation_daily").filter { it.getValue("condition").isEmpty() }
        val days = dailyFor(rows.first(), rows.size).toMap()
        for (row in rows) {
            val date = row.getValue("date")
            assertEquals(date, row.getValue("deg_db").toDouble(), days.getValue(LocalDate.parse(date)).degDb, row.getValue("tol").toDouble())
        }
    }

    @Test
    fun conditions() {
        assertEquals(Condition.EXCELLENT, Degradation.conditionOf(1.5))
        assertEquals(Condition.GOOD, Degradation.conditionOf(2.5))
        assertEquals(Condition.FAIR, Degradation.conditionOf(4.0))
        assertEquals(Condition.POOR, Degradation.conditionOf(4.2))
        assertEquals(Condition.VERY_POOR, Degradation.conditionOf(5.6))
    }

    @Test
    fun higherBandsHaveLessSkyNoise() {
        val t = Instant.parse("2026-11-10T12:00:00Z").toEpochMilli()
        val d144 = Degradation.compute(t, 51.1, 5.5, EmeBand.B144, 80.0)
        val d1296 = Degradation.compute(t, 51.1, 5.5, EmeBand.B1296, 50.0)
        assertTrue(d1296.tskyK < d144.tskyK / 50)
        assertEquals(d144.rangeFactorDb, d1296.rangeFactorDb, 1e-9)   // afstand speelt op elke band even sterk
        assertTrue(d1296.degDb <= d144.degDb)
    }

    /** Referentie: astropy (verlichte fractie) en de fasen van november 2026. */
    @Test
    fun moonPhase() {
        fun at(iso: String) = MoonPhase.at(Instant.parse(iso).toEpochMilli())
        for (row in ExpectedCsv.read("moon_phase")) {
            val m = at(row.getValue("utc"))
            assertEquals(row.getValue("illuminated").toDouble(), m.illuminated, row.getValue("tol").toDouble())
            assertEquals(row.getValue("name_index").toInt(), m.nameIndex)
        }
        assertTrue(at("2026-11-17T12:00:00Z").waxing)
        assertTrue(!at("2026-12-01T12:00:00Z").waxing)
    }
}
