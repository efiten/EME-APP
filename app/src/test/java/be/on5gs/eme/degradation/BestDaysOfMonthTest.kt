package be.on5gs.eme.degradation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

/** De groene cirkels in de kalender: de 4 dagen met de laagste degradatie van de maand. */
class BestDaysOfMonthTest {

    private val lat = 51.15   // JO21
    private val lon = 4.29

    @Test
    fun fourLowestDaysOfJanuary2030() {
        val month = YearMonth.of(2030, 1)
        val best = Degradation.bestDaysOfMonth(month, lat, lon, EmeBand.B144, 80.0)
        val all = Degradation.daily(month.atDay(1), 31, lat, lon, EmeBand.B144, 80.0)

        assertEquals(4, best.size)
        assertTrue(best.all { YearMonth.from(it) == month })
        // Geen enkele dag buiten de cirkels is beter dan een dag met een cirkel
        val worstChosen = all.filter { it.first in best }.maxOf { it.second.degDb }
        assertTrue(all.filter { it.first !in best }.all { it.second.degDb >= worstChosen })
        // Bij een gelijke waarde wint de vroegste dag
        val tiedLeftOut = all.filter { it.first !in best && it.second.degDb == worstChosen }.map { it.first }
        val tiedChosen = all.filter { it.first in best && it.second.degDb == worstChosen }.map { it.first }
        assertTrue(tiedLeftOut.all { left -> tiedChosen.all { it < left } })
    }

    @Test
    fun everyMonthLengthAndOtherCount() {
        listOf(YearMonth.of(2028, 2), YearMonth.of(2030, 2), YearMonth.of(2030, 4), YearMonth.of(2030, 12)).forEach { m ->
            assertEquals(4, Degradation.bestDaysOfMonth(m, lat, lon, EmeBand.B1296, 50.0).size)
        }
        assertEquals(6, Degradation.bestDaysOfMonth(YearMonth.of(2030, 1), lat, lon, EmeBand.B144, 80.0, count = 6).size)
    }
}
