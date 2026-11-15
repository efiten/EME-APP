package be.on5gs.eme.horizon

import be.on5gs.eme.moon.MoonCalculator

/** Een aaneengesloten stuk tijd met hetzelfde zicht op de maan; [endMillis] hoort er niet meer bij. */
data class MoonWindow(val startMillis: Long, val endMillis: Long, val visibility: Visibility)

/**
 * Wanneer is de maan vrij van de eigen horizon? Minuut per minuut (zoals de maanopkomst in de app)
 * van [startMillis] tot [endMillis].
 */
object MoonWindows {

    private const val STEP_MS = 60_000L

    /** Alle stukken tussen start en eind, elk met zijn zicht (vrij, gedeeltelijk, geblokkeerd). */
    fun windows(
        horizon: Horizon,
        latDeg: Double,
        lonDeg: Double,
        startMillis: Long,
        endMillis: Long,
        marginDeg: Double = 0.0,
    ): List<MoonWindow> {
        val out = mutableListOf<MoonWindow>()
        var segStart = startMillis
        var current: Visibility? = null
        var t = startMillis
        while (t < endMillis) {
            val p = MoonCalculator.position(latDeg, lonDeg, t)
            val v = horizon.visibility(p.azimuthDeg, p.elevationDeg, marginDeg)
            if (v != current) {
                if (current != null) out += MoonWindow(segStart, t, current)
                segStart = t
                current = v
            }
            t += STEP_MS
        }
        if (current != null) out += MoonWindow(segStart, endMillis, current)
        return out
    }

    /**
     * De stukken waarin EME kan: vrij of gedeeltelijk vrij, aaneensluitende stukken samengevoegd.
     * Een venster is gedeeltelijk als er ergens in een hindernis met verlies zit.
     */
    fun usable(
        horizon: Horizon,
        latDeg: Double,
        lonDeg: Double,
        startMillis: Long,
        endMillis: Long,
        marginDeg: Double = 0.0,
    ): List<MoonWindow> {
        val out = mutableListOf<MoonWindow>()
        for (w in windows(horizon, latDeg, lonDeg, startMillis, endMillis, marginDeg)) {
            if (w.visibility == Visibility.BLOCKED) continue
            val last = out.lastOrNull()
            if (last != null && last.endMillis == w.startMillis) {
                val v = if (last.visibility == Visibility.PARTIAL || w.visibility == Visibility.PARTIAL) Visibility.PARTIAL else Visibility.CLEAR
                out[out.size - 1] = MoonWindow(last.startMillis, w.endMillis, v)
            } else {
                out += w
            }
        }
        return out
    }
}
