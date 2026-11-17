package be.on5gs.eme.cq

import be.on5gs.eme.ExpectedCsv
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/** #105: geen meldingen van stations waar de maan onder de horizon staat; zelfde tabel als de webtests. */
class LiveCqMoonDownTest {

    private fun spot(call: String, grid: String, t: Long, src: LiveCqSource = LiveCqSource.W3SZ) =
        LiveCqSpot(t - 60_000, call, grid, 1296.065, Band.B23CM, "Q65-60C", "-20", "PA3DRK", "JO21", setOf(src))

    @Test
    fun onlyStationsWithTheMoonUpOrWithoutLocator() {
        val rows = ExpectedCsv.read("livecq_moon_down")
        val now = Instant.parse(rows.first().getValue("now_utc")).toEpochMilli()
        val shown = LiveCqParser.select(rows.map { spot(it.getValue("callsign"), it.getValue("grid"), now) }, setOf(Band.B23CM), now, 60)
            .map { it.callsign }.toSet()
        rows.forEach { r -> assertEquals("${r["callsign"]} ${r["grid"]}", r.getValue("shown").toBoolean(), r.getValue("callsign") in shown) }
    }

    /** De locator uit een oudere melding van hetzelfde station telt ook: N6NU zonder locator, W3SZ met. */
    @Test
    fun locatorFromAnOlderSpotDecides() {
        val now = Instant.parse("2026-11-20T18:00:00Z").toEpochMilli()
        val spots = listOf(
            spot("W2AA", "", now, LiveCqSource.N6NU),                 // nieuwste, zonder locator
            spot("W2AA", "FN20", now - 10 * 60_000, LiveCqSource.W3SZ), // ouder, met locator: maan onder
        )
        assertEquals(emptyList<String>(), LiveCqParser.select(spots, setOf(Band.B23CM), now, 60).map { it.callsign })
    }

    /** Een station waarvan de maan sinds zijn CQ ondergegaan is, valt ook weg: "nu" telt, niet het tijdstip van de melding. */
    @Test
    fun theMoonNowCountsNotAtTheTimeOfTheSpot() {
        // JO21SC, 20 november 2026: de maan gaat onder rond 02:54 UTC op 21 november
        val spotTime = Instant.parse("2026-11-21T02:30:00Z").toEpochMilli()
        val s = spot("ON4AA", "JO21SC", spotTime + 60_000)
        assertEquals(listOf("ON4AA"), LiveCqParser.select(listOf(s), setOf(Band.B23CM), spotTime + 5 * 60_000, 60).map { it.callsign })
        assertEquals(emptyList<String>(), LiveCqParser.select(listOf(s), setOf(Band.B23CM), spotTime + 50 * 60_000, 60).map { it.callsign })
    }
}
