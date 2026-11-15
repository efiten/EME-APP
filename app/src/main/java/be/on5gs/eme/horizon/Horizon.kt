package be.on5gs.eme.horizon

import kotlin.math.roundToLong

/** Wat een hindernis doet: helemaal dicht, of signaal komt er met verlies door (bv. dunne bomen). */
enum class ObstacleKind(val code: String) {
    BLOCKED("B"),
    PARTIAL("P"),
}

/**
 * Een hindernis van azimut [fromAzDeg] tot [toAzDeg] (met de klok mee, beide inbegrepen), tot
 * elevatie [maxElDeg]. Over het noorden heen kan ook: van 350 tot 10.
 */
data class Obstacle(
    val fromAzDeg: Double,
    val toAzDeg: Double,
    val maxElDeg: Double,
    val kind: ObstacleKind,
) {
    fun containsAz(azDeg: Double): Boolean {
        if (toAzDeg - fromAzDeg >= 360.0) return true   // de hele cirkel, bv. van 0 tot 360
        val az = normalizeAz(azDeg)
        val from = normalizeAz(fromAzDeg)
        val to = normalizeAz(toAzDeg)
        return if (from <= to) az in from..to else az >= from || az <= to
    }
}

/** Is de maan vrij, gedeeltelijk vrij (met verlies) of geblokkeerd? */
enum class Visibility { CLEAR, PARTIAL, BLOCKED }

/**
 * De eigen horizon van een station: een ondergrens [minElDeg] in alle richtingen (huizen, grond)
 * en een lijst hindernissen. De standaard (0°, geen hindernissen) gedraagt zich zoals de app
 * altijd deed: de maan is bruikbaar zodra ze boven de horizon staat.
 */
data class Horizon(
    val minElDeg: Double = 0.0,
    val obstacles: List<Obstacle> = emptyList(),
) {
    /** Geen eigen horizon ingesteld: geen ondergrens en geen hindernissen (zoals een nieuwe installatie). */
    val isDefault: Boolean get() = minElDeg == 0.0 && obstacles.isEmpty()

    /**
     * Zicht op een punt aan de hemel. [marginDeg] is een extra marge in elevatie, bv. een halve
     * bundelbreedte: een schotel is pas vrij als ook de rand van de bundel over de hindernis komt.
     * Precies op de rand (elevatie = grens) telt als vrij. Geblokkeerd wint van gedeeltelijk.
     */
    fun visibility(azDeg: Double, elDeg: Double, marginDeg: Double = 0.0): Visibility {
        val el = elDeg - marginDeg
        if (elDeg <= 0.0 || el < minElDeg) return Visibility.BLOCKED
        var partial = false
        for (o in obstacles) {
            if (el < o.maxElDeg && o.containsAz(azDeg)) {
                if (o.kind == ObstacleKind.BLOCKED) return Visibility.BLOCKED
                partial = true
            }
        }
        return if (partial) Visibility.PARTIAL else Visibility.CLEAR
    }

    /**
     * Tekstvorm om te bewaren, zelfde formaat als de webversie:
     * `1;<ondergrens>;<soort> <van> <tot> <elevatie>;...`, bv. `1;5;B 60 110 25;P 180 200 20`.
     * Het eerste veld is de versie van het formaat.
     */
    fun encode(): String =
        (listOf(FORMAT_VERSION, fmt(minElDeg)) + obstacles.map {
            "${it.kind.code} ${fmt(it.fromAzDeg)} ${fmt(it.toAzDeg)} ${fmt(it.maxElDeg)}"
        }).joinToString(";")

    companion object {
        const val FORMAT_VERSION = "1"

        /** Leest [encode] terug; null bij een onbekende versie of onzin (de app valt dan terug op de standaard). */
        fun decode(text: String?): Horizon? {
            val parts = text?.trim()?.split(";") ?: return null
            if (parts.size < 2 || parts[0] != FORMAT_VERSION) return null
            val min = num(parts[1].trim(), 90.0) ?: return null
            val obstacles = parts.drop(2).map { p ->
                val f = p.trim().split(" ").filter { it.isNotEmpty() }
                if (f.size != 4) return null
                val kind = ObstacleKind.entries.firstOrNull { it.code == f[0] } ?: return null
                val from = num(f[1], 360.0) ?: return null
                val to = num(f[2], 360.0) ?: return null
                val el = num(f[3], 90.0) ?: return null
                Obstacle(from, to, el, kind)
            }
            return Horizon(min, obstacles)
        }

        private val NUMBER = Regex("""^-?\d+(\.\d+)?$""")

        /** Gewoon decimaal getal (geen "1e2", geen "NaN") tussen 0 en [max], zoals in de webversie. */
        private fun num(text: String, max: Double): Double? =
            if (NUMBER.matches(text)) text.toDouble().takeIf { it in 0.0..max } else null

        /** Getal met hoogstens 2 decimalen, zonder overbodige ".0" (zoals String(x) in JavaScript). */
        private fun fmt(v: Double): String {
            val r = (v * 100).roundToLong() / 100.0
            return if (r == Math.floor(r)) r.toLong().toString() else r.toString()
        }
    }
}

/**
 * Een hoek uit een invulveld: een gewoon getal van 0 tot en met [max], met punt of komma als
 * decimaalteken ("25,5"). Null als het veld leeg of fout is.
 */
fun parseAngle(text: String, max: Double): Double? {
    val t = text.trim().replace(',', '.')
    if (!Regex("""^\d+(\.\d+)?$""").matches(t)) return null
    return t.toDouble().takeIf { it <= max }
}

/** Azimut in [0, 360). */
fun normalizeAz(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0

/**
 * Halve bundelbreedte (−3 dB) van een schotel in graden, met de vuistregel 70·λ/D voor de
 * volle breedte. 6 m op 1296 MHz: ongeveer 1,35°; op 432 MHz ongeveer 4°.
 */
fun halfBeamwidthDeg(freqMhz: Double, dishDiameterM: Double): Double {
    val lambdaM = 299.792458 / freqMhz
    return 35.0 * lambdaM / dishDiameterM
}
