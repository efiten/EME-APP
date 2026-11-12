package be.on5gs.eme.cq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class LiveCqParserTest {

    /** Echte export van W3SZ (4-5 november 2026), door ON5GS aangeleverd. */
    private val sample: String =
        javaClass.classLoader!!.getResource("livecq_sample.csv")!!.readText()

    private fun millis(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun parsesRealExport() {
        val spots = LiveCqParser.parse(sample)
        assertEquals(584, spots.size)
        val first = spots.first()
        assertEquals("KA1EME", first.callsign)
        assertEquals("FN31", first.grid)
        assertEquals(144.112, first.freqMhz, 1e-9)
        assertEquals(Band.B144, first.band)
        assertEquals("JT65B", first.mode)
        assertEquals("W9IP", first.spotter)
        assertEquals(millis("2026-11-04T13:39:00Z"), first.utcMillis)
    }

    @Test
    fun bandsFromFrequency() {
        val byBand = LiveCqParser.parse(sample).groupingBy { it.band }.eachCount()
        assertEquals(239, byBand[Band.B144])
        assertEquals(284, byBand[Band.B432])
        assertEquals(61, byBand[Band.B23CM])   // in de CSV staat "1290" voor 23cm
        assertEquals(Band.B23CM, Band.forFrequency(1296.065))
        assertEquals(Band.B3CM, Band.forFrequency(10368.1))
        assertNull(Band.forFrequency(700.0))
    }

    @Test
    fun selectFiltersBandAgeSelfSpotsAndDuplicates() {
        val spots = LiveCqParser.parse(sample)
        val now = millis("2026-11-05T13:30:00Z")
        val shown = LiveCqParser.select(spots, setOf(Band.B23CM), now, maxAgeMinutes = 24 * 60)
        assertTrue(shown.isNotEmpty())
        assertTrue(shown.all { it.band == Band.B23CM })
        assertTrue(shown.all { it.spotter != it.callsign })
        assertEquals(shown.size, shown.map { it.callsign }.toSet().size)               // elk station 1x
        assertEquals(shown.sortedByDescending { it.utcMillis }, shown)                // nieuwste eerst

        // Een uur na de laatste melding is er niets meer te zien
        val later = LiveCqParser.select(spots, setOf(Band.B23CM), millis("2026-11-05T15:00:00Z"), 60)
        assertTrue(later.isEmpty())
        // Geen banden gekozen: lege lijst
        assertTrue(LiveCqParser.select(spots, emptySet(), now, 24 * 60).isEmpty())
    }

    @Test
    fun skipsInvalidLinesAndHtml() {
        val csv = """
            skedfreq,rxfreq,rpol,dt,dB,msgtype,callsign,grid,mode,utcdatetime,spotter,spottergrid,txpol,apptype,band,uploadtime
            1296.070,0,0,0,-20,CQ,SP5GDM,KO02,Q65-60C,"2026-11-06 20:01:00",OK1DFC,JN79,/,MAP65,1290,"2026-11-06 20:02:00"
            1296.070,0,0,0,-20,CQ,NOT A CALL,KO02,Q65-60C,"2026-11-06 20:01:00",OK1DFC,JN79,/,MAP65,1290,x
            abc,0,0,0,-20,CQ,DL7APV,JO62,Q65-60C,"2026-11-06 20:01:00",OK1DFC,JN79,/,MAP65,1290,x
            1296.070,0,0,0,-20,CQ,DL7APV,JO62,Q65-60C,geen-datum,OK1DFC,JN79,/,MAP65,1290,x
            1296.080,0,0,0,-21,CQ,G4CCH,XX99,Q65-60C,"2026-11-06 20:02:00",OK1DFC,JN79,/,MAP65,1290,x
        """.trimIndent()
        val spots = LiveCqParser.parse(csv)
        assertEquals(listOf("SP5GDM", "G4CCH"), spots.map { it.callsign })
        assertEquals("", spots[1].grid)     // ongeldige locator → leeg
    }

    @Test
    fun zeroTimestamp_usesUploadTime() {
        val spot = LiveCqParser.parse(sample).first { it.callsign == "SM2BYC" && it.spotter == "S52LM" }
        assertTrue(spot.utcMillis >= millis("2026-11-04T00:00:00Z"))
    }

    @Test
    fun quotedFields() {
        assertEquals(listOf("a", "b,c", "d\"e", ""), LiveCqParser.splitCsvLine("a,\"b,c\",\"d\"\"e\","))
    }

    @Test
    fun stationGridWith8Characters() {
        val p = stationLatLon("JO21sc45")!!
        val q = be.on5gs.eme.location.Maidenhead.toLatLon("JO21SC")!!
        assertEquals(q.latDeg, p.latDeg, 1e-9)
        assertNull(stationLatLon(""))
    }
}
