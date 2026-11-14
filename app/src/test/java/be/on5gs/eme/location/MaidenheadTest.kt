package be.on5gs.eme.location

import be.on5gs.eme.ExpectedCsv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaidenheadTest {

    private val eps = 1e-6
    private val expected = ExpectedCsv.read("maidenhead").associateBy { it.getValue("locator") }
    private fun lat(locator: String) = expected.getValue(locator).getValue("lat").toDouble()
    private fun lon(locator: String) = expected.getValue(locator).getValue("lon").toDouble()

    @Test
    fun sixCharacterLocator_givesCenterOfSubsquare() {
        val p = Maidenhead.toLatLon("JO21SC")!!
        assertEquals(lat("JO21SC"), p.latDeg, eps)   // 51.104167 N
        assertEquals(lon("JO21SC"), p.lonDeg, eps)     // 5.541667 O
    }

    @Test
    fun fourCharacterLocator_givesCenterOfSquare() {
        val p = Maidenhead.toLatLon("JO21")!!
        assertEquals(lat("JO21"), p.latDeg, eps)
        assertEquals(lon("JO21"), p.lonDeg, eps)
    }

    @Test
    fun westAndSouth_areNegative() {
        val fn20 = Maidenhead.toLatLon("FN20")!!     // oostkust VS
        assertEquals(lat("FN20"), fn20.latDeg, eps)
        assertEquals(lon("FN20"), fn20.lonDeg, eps)
        val qf22 = Maidenhead.toLatLon("QF22")!!     // Melbourne
        assertEquals(lat("QF22"), qf22.latDeg, eps)
        assertEquals(lon("QF22"), qf22.lonDeg, eps)
    }

    @Test
    fun corners() {
        val aa00aa = Maidenhead.toLatLon("AA00aa")!!
        assertEquals(lat("AA00aa"), aa00aa.latDeg, eps)
        assertEquals(lon("AA00aa"), aa00aa.lonDeg, eps)
        val rr99xx = Maidenhead.toLatLon("RR99XX")!!
        assertEquals(lat("RR99XX"), rr99xx.latDeg, eps)
        assertEquals(lon("RR99XX"), rr99xx.lonDeg, eps)
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
