package be.on5gs.eme.cq

/** De bandknoppen zoals op de W3SZ Live CQ-site. Frequenties in MHz. */
enum class Band(val label: String, val minMhz: Double, val maxMhz: Double) {
    B50("50", 50.0, 54.0),
    B144("144", 144.0, 148.0),
    B222("222", 219.0, 225.0),
    B432("432", 420.0, 450.0),
    B902("902", 902.0, 928.0),
    B23CM("23cm", 1240.0, 1300.0),
    B13CM("13cm", 2300.0, 2450.0),
    B9CM("9cm", 3300.0, 3500.0),
    B6CM("6cm", 5650.0, 5925.0),
    B3CM("3cm", 10000.0, 10500.0),
    B1CM("1.2cm/up", 24000.0, Double.MAX_VALUE);

    companion object {
        fun forFrequency(mhz: Double): Band? = entries.firstOrNull { mhz >= it.minMhz && mhz <= it.maxMhz }
    }
}

/** Eén CQ-melding uit de Live CQ-lijst. */
data class LiveCqSpot(
    val utcMillis: Long,        // tijdstip van de decode, UTC
    val callsign: String,
    val grid: String,           // locator van het station, of "" als onbekend
    val freqMhz: Double,        // skedfreq
    val band: Band?,
    val mode: String,
    val db: String,             // signaalsterkte zoals gemeld
    val spotter: String,
    val spotterGrid: String,
)
