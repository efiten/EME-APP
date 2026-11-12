package be.on5gs.eme.moon

import be.on5gs.eme.location.Maidenhead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelPlacerTest {

    // Kaart zoals op een gsm van 411dp breed (xxhdpi, 3 pixels per dp)
    private val w = 1180f
    private val h = 590f
    private val density = 3f
    private val dotR = 2.5f * density
    private val labelH = 8f * density * 1.2f               // tekst van 8sp
    private fun labelW(call: String) = call.length * 8f * density * 0.62f

    private fun x(lon: Double) = ((lon + 180) / 360 * w).toFloat()
    private fun y(lat: Double) = ((90 - lat) / 180 * h).toFloat()

    private fun label(call: String, grid: String): LabelPlacer.Label {
        val p = Maidenhead.toLatLon(grid)!!
        return LabelPlacer.Label(x(p.lonDeg), y(p.latDeg), labelW(call), labelH)
    }

    private fun assertNoOverlap(labels: List<LabelPlacer.Label>, placed: List<LabelPlacer.Placement>) {
        for (i in placed.indices) {
            assertTrue("label $i valt buiten de kaart", placed[i].box.inside(w, h))
            for (j in placed.indices) {
                if (i != j) {
                    assertEquals("label $i overlapt label $j", 0f, placed[i].box.overlap(placed[j].box))
                    val dot = LabelPlacer.Box(labels[j].x - dotR, labels[j].y - dotR, labels[j].x + dotR, labels[j].y + dotR)
                    assertEquals("label $i bedekt stip $j", 0f, placed[i].box.overlap(dot))
                }
            }
        }
    }

    /** Het voorbeeld van ON5GS: twee stations op 2 graden van elkaar. */
    @Test
    fun ok1aaAndOk2bb_doNotOverlap() {
        val labels = listOf(label("OK1AA", "JO61AA"), label("OK2BB", "JO71AA"))
        val placed = LabelPlacer.place(labels, w, h, dotR, 1.5f * density)
        assertNoOverlap(labels, placed)
    }

    /** Drukke buurt: 8 stations in Midden-Europa, waarvan één op twee banden (zelfde plek). */
    @Test
    fun crowdedCentralEurope_doesNotOverlap() {
        val labels = listOf(
            label("OK1AA", "JO61AA"), label("OK2BB", "JO71AA"), label("OK1DFC", "JO70FD"),
            label("DL7APV", "JO62QM"), label("SP5GDM", "KO02MD"), label("OK1KIR", "JO70ED"),
            label("DF1NP", "JN58TD"), label("OK1AA", "JO61AA"),
        )
        val myQth = LabelPlacer.Box(x(5.54) - 15, y(51.1) - 15, x(5.54) + 80, y(51.1) + 15)
        val placed = LabelPlacer.place(labels, w, h, dotR, 1.5f * density, listOf(myQth))
        assertNoOverlap(labels, placed)
        placed.forEach { assertEquals(0f, it.box.overlap(myQth)) }
    }

    @Test
    fun labelNearMapEdge_staysInside() {
        val labels = listOf(LabelPlacer.Label(w - 3f, 100f, labelW("ZL1AAA"), labelH))
        val placed = LabelPlacer.place(labels, w, h, dotR, 1.5f * density)
        assertTrue(placed[0].box.inside(w, h))
    }
}
