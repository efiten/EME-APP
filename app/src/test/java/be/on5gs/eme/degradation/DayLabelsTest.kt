package be.on5gs.eme.degradation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class DayLabelsTest {

    /** 30 dagen met de gekozen dag op plaats 15 (halve maand ervoor en erna). */
    private fun labels(center: String, today: String): Map<Int, String> {
        val c = LocalDate.parse(center)
        val dates = (0 until 30).map { c.minusDays(15).plusDays(it.toLong()) }
        return dayLabels(dates, LocalDate.parse(today), 15).toMap()
    }

    @Test
    fun centeredOnToday() {
        // 26 okt ... 24 nov, vandaag = 10 nov in het midden
        val l = labels("2026-11-10", "2026-11-10")
        assertEquals("26 oct", l[0])      // eerste aanduiding met de maand
        assertEquals("1 nov", l[6])       // nieuwe maand
        assertNull(l[5])                  // "31" vlak naast "1 nov" vervalt
        assertEquals("5", l[10])
        assertEquals("today", l[15])      // midden
        assertEquals("15", l[20])
        assertEquals("20", l[25])
    }

    @Test
    fun chosenDayInMiddleTodayAtLeft() {
        // 9 nov ... 8 dec, gekozen dag 24 nov in het midden, vandaag 10 nov
        val l = labels("2026-11-24", "2026-11-10")
        assertEquals("today", l[1])
        assertNull(l[0])                  // naast "today"
        assertEquals("14 nov", l[5])      // eerste dagnummer na "today" krijgt de maand
        assertEquals("19", l[10])
        assertEquals("24 nov", l[15])     // gekozen dag
        assertNull(l[20])                 // naast "1 dec"
        assertEquals("1 dec", l[22])
        assertEquals("4", l[25])
    }

    @Test
    fun todayOutsideWindow() {
        val l = labels("2027-03-15", "2026-11-10")
        assertEquals(null, l.values.firstOrNull { it == "today" })
        assertEquals("15 mar", l[15])
    }
}
