package be.on5gs.eme.cq

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant

/** org.json komt van Android, daarom met Robolectric. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class N6nuParserTest {

    /** Echt antwoord van livecq.n6nu.org/api/spots (EME, 50 meldingen: 28 CQ, 22 gericht). */
    private val json = javaClass.classLoader!!.getResource("n6nu_spots.json")!!.readText()

    @Test
    fun onlyValidCqCalls() {
        val spots = N6nuParser.parse(json)
        assertEquals(27, spots.size)                      // 28 CQ, waarvan 1 met een ongeldige call
        assertTrue(spots.none { it.callsign == "KB1HY" }) // alleen een gericht bericht (DIR)
        assertTrue(spots.all { it.sources == setOf(LiveCqSource.N6NU) })
        assertEquals(22, spots.count { it.band == Band.B23CM })
        assertEquals(5, spots.count { it.band == Band.B50 })
    }

    @Test
    fun fieldsOfOneSpot() {
        val k3sk = N6nuParser.parse(json).first()
        assertEquals("K3SK", k3sk.callsign)
        assertEquals(Instant.parse("2026-11-13T21:08:00Z").toEpochMilli(), k3sk.utcMillis)
        assertEquals("FM07", k3sk.grid)
        assertEquals(1296.09, k3sk.freqMhz, 1e-9)
        assertEquals("Q65-60C", k3sk.mode)
        assertEquals("-24", k3sk.db)
        assertEquals("2.8", k3sk.dt)
        assertEquals("W3TI", k3sk.spotter)
        assertEquals("FN20", k3sk.spotterGrid)
        assertEquals("WSJT-X", k3sk.app)
    }

    @Test
    fun missingLocatorAndBadData() {
        val text = """{"spots":[
            {"utc":"2026-11-14T10:00:00.000Z","callsign":"OK1DFC","grid":"--","mode":"Q65-60A","msgtype":"CQ","dB":-20,"dt":1,"skedfreq":432.065,"spotter":"PA3DRK","spottergrid":"JO21","apptype":"WSJT-X"},
            {"utc":"2026-11-14T10:00:00.000Z","callsign":"DL7APV","grid":"JO62","mode":"Q65","msgtype":"DIR","skedfreq":144.12,"spotter":"PA3DRK"},
            {"utc":"bad","callsign":"SP5GDM","msgtype":"CQ","skedfreq":1296.06,"spotter":"PA3DRK"},
            {"utc":"2026-11-14T10:00:00.000Z","callsign":"SP5GDM","msgtype":"CQ","skedfreq":null,"spotter":"PA3DRK"}
        ]}"""
        val spots = N6nuParser.parse(text)
        assertEquals(listOf("OK1DFC"), spots.map { it.callsign })
        assertEquals("", spots.single().grid)            // "--" wordt "geen locator"
        assertTrue(N6nuParser.parse("""{"total":0}""").isEmpty())
    }
}
