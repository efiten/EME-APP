package be.on5gs.eme.moon

/**
 * Maanopkomst, culminatie (hoogste stand) en maanondergang voor een waarnemer.
 *
 * "Opkomst" en "ondergang" = het moment waarop het MIDDEN van de maan door elevatie 0°
 * gaat, zonder atmosferische refractie. Dat past bij de AZ/EL op het scherm: de maan
 * "staat op" wanneer EL van negatief naar positief gaat. (Almanakken rekenen met de
 * bovenrand en refractie; hun tijden liggen daarom enkele minuten vroeger/later.)
 */
object MoonEvents {

    data class Event(val utcMillis: Long, val azimuthDeg: Double, val elevationDeg: Double)

    data class Events(
        val isUp: Boolean,
        val nextRise: Event?,       // eerstvolgende opkomst na "nu"
        val culmination: Event?,    // hoogste stand van de huidige (of anders de volgende) doorgang
        val nextSet: Event?,        // eerstvolgende ondergang na "nu"
    )

    private const val STEP_MS = 10 * 60_000L
    private const val HOUR_MS = 3_600_000L
    private const val SEARCH_AHEAD_MS = 48 * HOUR_MS
    private const val SEARCH_BACK_MS = 30 * HOUR_MS

    fun compute(latDeg: Double, lonDeg: Double, nowMillis: Long): Events {
        fun el(t: Long) = MoonCalculator.position(latDeg, lonDeg, t).elevationDeg
        fun event(t: Long) = MoonCalculator.position(latDeg, lonDeg, t).let { Event(t, it.azimuthDeg, it.elevationDeg) }

        /** Zoekt vooruit het eerste moment waarop EL door 0 gaat (stijgend of dalend). */
        fun crossingAfter(from: Long, rising: Boolean): Long? {
            var t0 = from
            var e0 = el(t0)
            while (t0 < from + SEARCH_AHEAD_MS) {
                val t1 = t0 + STEP_MS
                val e1 = el(t1)
                if (rising && e0 <= 0 && e1 > 0) return bisect(t0, t1, rising, ::el)
                if (!rising && e0 > 0 && e1 <= 0) return bisect(t0, t1, rising, ::el)
                t0 = t1; e0 = e1
            }
            return null
        }

        /** Zoekt achteruit de laatste opkomst vóór [from]. */
        fun riseBefore(from: Long): Long? {
            var t1 = from
            var e1 = el(t1)
            while (t1 > from - SEARCH_BACK_MS) {
                val t0 = t1 - STEP_MS
                val e0 = el(t0)
                if (e0 <= 0 && e1 > 0) return bisect(t0, t1, true, ::el)
                t1 = t0; e1 = e0
            }
            return null
        }

        val isUp = el(nowMillis) > 0
        val nextRise = crossingAfter(nowMillis, rising = true)
        val nextSet = crossingAfter(nowMillis, rising = false)

        // De doorgang waarvan we de culminatie tonen
        val passStart = if (isUp) riseBefore(nowMillis) else nextRise
        val passEnd = if (isUp) nextSet else nextRise?.let { crossingAfter(it, rising = false) }
        val culmination = if (passStart != null && passEnd != null) {
            event(maxBetween(passStart, passEnd, ::el))
        } else null

        return Events(isUp, nextRise?.let(::event), culmination, nextSet?.let(::event))
    }

    /** Verfijnt een nulpunt tussen t0 en t1 tot op ongeveer een seconde. */
    private fun bisect(t0: Long, t1: Long, rising: Boolean, el: (Long) -> Double): Long {
        var lo = t0
        var hi = t1
        while (hi - lo > 1_000) {
            val mid = (lo + hi) / 2
            val above = el(mid) > 0
            if (above == rising) hi = mid else lo = mid
        }
        return (lo + hi) / 2
    }

    /** Tijdstip van de hoogste elevatie tussen a en b. */
    private fun maxBetween(a: Long, b: Long, el: (Long) -> Double): Long {
        // Grof zoeken per 10 minuten, daarna verfijnen (gulden snede)
        var best = a
        var bestEl = el(a)
        var t = a
        while (t <= b) {
            val e = el(t)
            if (e > bestEl) { best = t; bestEl = e }
            t += STEP_MS
        }
        var lo = (best - STEP_MS).coerceAtLeast(a).toDouble()
        var hi = (best + STEP_MS).coerceAtMost(b).toDouble()
        val g = 0.6180339887
        while (hi - lo > 1_000) {
            val m1 = hi - g * (hi - lo)
            val m2 = lo + g * (hi - lo)
            if (el(m1.toLong()) < el(m2.toLong())) lo = m1 else hi = m2
        }
        return ((lo + hi) / 2).toLong()
    }
}
