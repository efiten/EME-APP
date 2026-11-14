package be.on5gs.eme.cq

import org.json.JSONObject
import java.time.Instant

/**
 * Leest de JSON van N6NU Live CQ (https://livecq.n6nu.org/api/spots).
 * Per melding o.a.: utc, callsign, grid, mode, msgtype, dB, dt, skedfreq,
 * spotter, spottergrid, apptype. Alleen de CQ's worden gehouden, geen gerichte
 * berichten (msgtype "DIR").
 */
object N6nuParser {

    /** Alle geldige CQ-meldingen. Onleesbare meldingen worden overgeslagen. */
    fun parse(json: String): List<LiveCqSpot> {
        val spots = JSONObject(json).optJSONArray("spots") ?: return emptyList()
        return (0 until spots.length()).mapNotNull { i ->
            val o = spots.optJSONObject(i) ?: return@mapNotNull null
            fun text(name: String) = if (o.isNull(name)) "" else o.opt(name)?.toString()?.trim().orEmpty()

            if (!text("msgtype").equals("CQ", ignoreCase = true)) return@mapNotNull null
            val call = text("callsign").uppercase()
            if (!LiveCqParser.CALL_REGEX.matches(call)) return@mapNotNull null
            val freq = text("skedfreq").toDoubleOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val time = parseTime(text("utc")) ?: parseTime(text("createdAt")) ?: return@mapNotNull null
            LiveCqSpot(
                utcMillis = time,
                callsign = call,
                grid = text("grid").takeIf { LiveCqParser.GRID_REGEX.matches(it) } ?: "",
                freqMhz = freq,
                band = Band.forFrequency(freq),
                mode = text("mode"),
                db = text("dB"),
                spotter = text("spotter").uppercase(),
                spotterGrid = text("spottergrid").takeIf { LiveCqParser.GRID_REGEX.matches(it) } ?: "",
                sources = setOf(LiveCqSource.N6NU),
                dt = text("dt"),
                app = text("apptype"),
            )
        }
    }

    private fun parseTime(text: String): Long? =
        runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
}
