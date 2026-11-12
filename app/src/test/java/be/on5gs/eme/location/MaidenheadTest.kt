package be.on5gs.eme.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaidenheadTest {

    private val eps = 1e-6

    @Test
    fun sixCharacterLocator_givesCenterOfSubsquare() {
        val p = Maidenhead.toLatLon("JO21SC")!!
        assertEquals(51.0 + 2 * 2.5 / 60 + 1.25 / 60, p.latDeg, eps)   // 51.104167 N
        assertEquals(4.0 + 18 * 5.0 / 60 + 2.5 / 60, p.lonDeg, eps)     // 5.541667 O
    }

    @Test
    fun fourCharacterLocator_givesCenterOfSquare() {
        val p = Maidenhead.toLatLon("JO21")!!
        assertEquals(51.5, p.latDeg, eps)
        assertEquals(5.0, p.lonDeg, eps)
    }

    @Test
    fun westAndSouth_areNegative() {
        val fn20 = Maidenhead.toLatLon("FN20")!!     // oostkust VS
        assertEquals(40.5, fn20.latDeg, eps)
        assertEquals(-75.0, fn20.lonDeg, eps)
        val qf22 = Maidenhead.toLatLon("QF22")!!     // Melbourne
        assertEquals(-37.5, qf22.latDeg, eps)
        assertEquals(145.0, qf22.lonDeg, eps)
    }

    @Test
    fun corners() {
        val aa00aa = Maidenhead.toLatLon("AA00aa")!!
        assertEquals(-90.0 + 1.25 / 60, aa00aa.latDeg, eps)
        assertEquals(-180.0 + 2.5 / 60, aa00aa.lonDeg, eps)
        val rr99xx = Maidenhead.toLatLon("RR99XX")!!
        assertEquals(90.0 - 1.25 / 60, rr99xx.latDeg, eps)
        assertEquals(180.0 - 2.5 / 60, rr99xx.lonDeg, eps)
    }

    @Test
    fun lowercaseAndSpaces_areAccepted() {
        assertTrue(Maidenhead.isValid(" jo21sc "))
        assertEquals("JO21sc", Maidenhead.normalize(" jo21SC "))
        assertEquals("JO21", Maidenhead.normalize("jo21"))
    }

    @Test
    fun invalidLocators_areRejected() {
        listOf("", "J", "JO2", "JO21S", "JO21SCX", "SO21", "JS21", "JO2A", "JO21YA", "1234", "JO21 SC")
            .forEach {
                assertFalse("'$it' zou ongeldig moeten zijn", Maidenhead.isValid(it))
                assertNull(Maidenhead.toLatLon(it))
            }
    }
}
