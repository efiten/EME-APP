package be.on5gs.eme.degradation

import be.on5gs.eme.moon.MoonPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class DegradationTest {

    /**
     * Referentie: MMMonVHF eme.php (waarnemer 48N 11E, 12:00 UTC, 144 MHz, Tsys 80 K), bekeken op 10 november 2026.
     * "Todays conditions" komt uit hetzelfde script als deze berekening: moet exact kloppen.
     */
    @Test
    fun matchesMmmonvhfToday() {
        val r = Degradation.daily(LocalDate.parse("2026-11-10"), 1, 48.0, 11.0, EmeBand.B144, 80.0).single().second
        assertEquals(4.2, r.degDb, 0.05)
        assertEquals(Condition.POOR, r.condition)
    }

    /**
     * De lijst "Next 30 days lowest degradation" maakt de MMMonVHF-server met een eigen (niet zichtbare)
     * berekening; die wijkt tot ~0,3 dB af van het script op de pagina. Hier: binnen 0,35 dB.
     */
    @Test
    fun closeToMmmonvhfServerList() {
        val expected = mapOf("2026-11-27" to 2.1, "2026-11-28" to 1.7, "2026-11-29" to 1.6, "2026-11-30" to 1.7, "2026-12-01" to 1.9)
        val days = Degradation.daily(LocalDate.parse("2026-11-27"), 5, 48.0, 11.0, EmeBand.B144, 80.0).toMap()
        for ((date, deg) in expected) {
            assertEquals(date, deg, days.getValue(LocalDate.parse(date)).degDb, 0.35)
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
        assertEquals(0.0023, at("2026-11-09T12:00:00Z").illuminated, 0.005)   // nieuwe maan (9 nov 07 UTC)
        assertEquals(0.5022, at("2026-11-17T12:00:00Z").illuminated, 0.01)    // eerste kwartier
        assertEquals(0.9979, at("2026-11-24T12:00:00Z").illuminated, 0.005)   // volle maan (24 nov 14 UTC)
        assertEquals(0.4748, at("2026-12-01T12:00:00Z").illuminated, 0.01)    // laatste kwartier
        assertEquals(0, at("2026-11-09T12:00:00Z").nameIndex)
        assertEquals(2, at("2026-11-17T12:00:00Z").nameIndex)
        assertEquals(4, at("2026-11-24T12:00:00Z").nameIndex)
        assertEquals(6, at("2026-12-01T12:00:00Z").nameIndex)
        assertTrue(at("2026-11-17T12:00:00Z").waxing)
        assertTrue(!at("2026-12-01T12:00:00Z").waxing)
    }
}
