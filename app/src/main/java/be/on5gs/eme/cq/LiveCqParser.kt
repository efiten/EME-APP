package be.on5gs.eme.cq

import be.on5gs.eme.moon.MoonCalculator
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Leest de CSV-export van W3SZ Live CQ (knop "Download LiveCQ Table as CSV").
 * Kolommen: skedfreq,rxfreq,rpol,dt,dB,msgtype,callsign,grid,mode,utcdatetime,
 *           spotter,spottergrid,txpol,apptype,band,uploadtime
 */
object LiveCqParser {

    private val TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    // Dezelfde controles als de W3SZ-pagina zelf doet
    internal val CALL_REGEX = Regex("^(?:[A-Z0-9]{1,3}/)?[A-Z0-9]{1,3}[0-9][A-Z]{1,4}$")
    internal val GRID_REGEX = Regex("^[A-R]{2}[0-9]{2}([A-X]{2}([0-9]{2}([A-X]{2})?)?)?$", RegexOption.IGNORE_CASE)

    /** Alle geldige CQ-meldingen uit de CSV-tekst. Onleesbare regels worden overgeslagen. */
    fun parse(csv: String): List<LiveCqSpot> {
        val lines = csv.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return emptyList()
        val header = splitCsvLine(lines.first()).map { it.trim().lowercase() }
        val idx = { name: String -> header.indexOf(name) }
        val iFreq = idx("skedfreq"); val iCall = idx("callsign"); val iGrid = idx("grid")
        val iMode = idx("mode"); val iTime = idx("utcdatetime"); val iDb = idx("db")
        val iSpotter = idx("spotter"); val iSpGrid = idx("spottergrid"); val iType = idx("msgtype")
        val iUpload = idx("uploadtime"); val iDt = idx("dt"); val iApp = idx("apptype")
        require(iFreq >= 0 && iCall >= 0 && iTime >= 0) { "Onverwacht formaat van de Live CQ-gegevens" }

        return lines.drop(1).mapNotNull { line ->
            val f = splitCsvLine(line)
            fun col(i: Int) = if (i in f.indices) f[i].trim() else ""
            val call = col(iCall).uppercase()
            if (!CALL_REGEX.matches(call)) return@mapNotNull null
            if (iType >= 0 && col(iType).isNotEmpty() && !col(iType).equals("CQ", true)) return@mapNotNull null
            val freq = col(iFreq).toDoubleOrNull() ?: return@mapNotNull null
            // Sommige spotters (bv. EME65) sturen "0000-00-00 00:00:00": neem dan het uploadtijdstip
            val time = parseTime(col(iTime)) ?: parseTime(col(iUpload)) ?: return@mapNotNull null
            val grid = col(iGrid).takeIf { GRID_REGEX.matches(it) } ?: ""
            LiveCqSpot(
                utcMillis = time,
                callsign = call,
                grid = grid,
                freqMhz = freq,
                band = Band.forFrequency(freq),
                mode = col(iMode),
                db = col(iDb),
                spotter = col(iSpotter).uppercase(),
                spotterGrid = col(iSpGrid),
                sources = setOf(LiveCqSource.W3SZ),
                dt = col(iDt),
                app = col(iApp),
            )
        }
    }

    /**
     * Voegt de meldingen van W3SZ en N6NU samen. Dezelfde decode (zelfde station, band,
     * spotter en minuut) die op beide sites staat, wordt één melding met beide bronnen;
     * wat bij de ene ontbreekt (locator, DT, programma) wordt aangevuld met de andere.
     */
    fun merge(w3sz: List<LiveCqSpot>, n6nu: List<LiveCqSpot>): List<LiveCqSpot> {
        val out = LinkedHashMap<List<Any?>, LiveCqSpot>()
        for (spot in w3sz + n6nu) {
            val key = listOf(spot.callsign, spot.band, spot.spotter, spot.utcMillis / 60_000)
            val prev = out[key]
            out[key] = if (prev == null) spot else combine(prev, spot)
        }
        return out.values.toList()
    }

    private fun combine(a: LiveCqSpot, b: LiveCqSpot) = a.copy(
        grid = a.grid.ifEmpty { b.grid },
        db = a.db.ifEmpty { b.db },
        spotterGrid = a.spotterGrid.ifEmpty { b.spotterGrid },
        sources = a.sources + b.sources,
        dt = a.dt.ifEmpty { b.dt },
        app = a.app.ifEmpty { b.app },
    )

    /**
     * Wat de app toont: alleen de gekozen banden, niet ouder dan [maxAgeMinutes],
     * zonder meldingen waarbij spotter = station zelf (zoals de standaard op W3SZ),
     * en per callsign + band alleen de nieuwste melding. Nieuwste bovenaan.
     * Die melding krijgt alle bronnen van de groep, en een locator uit een oudere
     * melding als de nieuwste er geen heeft (N6NU geeft vaak "--").
     * Stations waar de maan nu onder de horizon staat, vallen weg (zie [moonMayBeUp]).
     */
    fun select(
        spots: List<LiveCqSpot>,
        bands: Set<Band>,
        nowMillis: Long,
        maxAgeMinutes: Int,
    ): List<LiveCqSpot> {
        val oldest = nowMillis - maxAgeMinutes * 60_000L
        return spots
            .filter { it.band in bands && it.utcMillis >= oldest && it.spotter != it.callsign }
            .groupBy { it.callsign to it.band }
            .map { (_, group) ->
                val newest = group.sortedByDescending { it.utcMillis }
                newest.first().copy(
                    grid = newest.firstOrNull { it.grid.isNotEmpty() }?.grid ?: "",
                    sources = group.flatMapTo(mutableSetOf()) { it.sources },
                )
            }
            .filter { moonMayBeUp(it, nowMillis) }
            .sortedByDescending { it.utcMillis }
    }

    /**
     * Staat de maan nu op bij dit station? Zo niet, dan kan het niet via EME gewerkt worden en wordt
     * de melding niet getoond, in de lijst en op de kaart (#105). Zonder bruikbare locator valt dat
     * niet te berekenen: dan blijft de melding staan. "Op" = boven 0° (hun eigen horizon kennen we niet).
     */
    internal fun moonMayBeUp(spot: LiveCqSpot, nowMillis: Long): Boolean {
        val pos = stationLatLon(spot.grid) ?: return true
        return MoonCalculator.position(pos.latDeg, pos.lonDeg, nowMillis).isAboveHorizon
    }

    private fun parseTime(text: String): Long? = runCatching {
        LocalDateTime.parse(text, TIME_FORMAT).toInstant(ZoneOffset.UTC).toEpochMilli()
    }.getOrNull()

    /** Splitst één CSV-regel; houdt rekening met velden tussen "aanhalingstekens". */
    internal fun splitCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { out.add(sb.toString()); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }
}
