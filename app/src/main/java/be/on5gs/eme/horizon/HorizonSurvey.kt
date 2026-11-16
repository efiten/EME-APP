package be.on5gs.eme.horizon

import org.json.JSONObject

/**
 * Leest een horizonopmeting van horizon-survey (https://horizon.dl6zg.de/, github.com/on6zg/horizon-survey):
 * `horizon_profile.json` (`{ "points": [{ "az", "el", "source"? }] }`) of de CSV
 * (`azimuth_deg,elevation_deg,timestamp,is_calibration,source`), en zet de punten om in hindernissen.
 * Zelfde regels als web/src/lib/horizon-survey.js.
 */
object HorizonSurvey {

    /** Een gat tussen twee gemeten punten dat groter is, wordt niet opgevuld (daar is niets gemeten). */
    const val MAX_GAP_DEG = 60.0

    sealed interface Result {
        /**
         * [points] gemeten punten (zonder ijkpunten), omgezet in [obstacles]. [gaps] zijn de stukken
         * (van, tot) groter dan [MAX_GAP_DEG] waar niets gemeten is: die blijven open (vrij).
         */
        data class Ok(val points: Int, val obstacles: List<Obstacle>, val gaps: List<Pair<Double, Double>> = emptyList()) : Result
        /** Geen opmeting van horizon-survey (ander bestand, of een formaat dat de app niet kent). */
        data object NotASurvey : Result
        /** Minder dan 2 bruikbare punten. */
        data object TooFewPoints : Result
    }

    fun parse(text: String): Result {
        val points = readPoints(text) ?: return Result.NotASurvey
        if (points.size < 2) return Result.TooFewPoints
        return Result.Ok(points.size, toObstacles(points), gaps(points))
    }

    /** De niet-gemeten stukken: tussen twee opeenvolgende punten (ook over het noorden) meer dan [MAX_GAP_DEG]. */
    internal fun gaps(points: List<Pair<Double, Double>>): List<Pair<Double, Double>> =
        points.indices.mapNotNull { i ->
            val a = points[i].first
            val b = points[(i + 1) % points.size].first
            val gap = ((b - a) % 360.0 + 360.0) % 360.0
            if (gap > MAX_GAP_DEG) a to b else null
        }

    /** De gemeten punten (azimut, elevatie), of null als dit geen opmeting is. */
    internal fun readPoints(text: String): List<Pair<Double, Double>>? {
        val t = text.trim().removePrefix("﻿")
        val raw = when {
            t.startsWith("{") -> readJson(t) ?: return null
            t.lowercase().startsWith("azimuth_deg,elevation_deg") -> readCsv(t)
            else -> return null
        }
        // Zelfde azimut (op 0,01° na) twee keer gemeten: de hoogste elevatie telt
        return raw.groupBy { Math.round(normalizeAz(it.first) * 100) }
            .map { (k, list) -> k / 100.0 to list.maxOf { it.second } }
            .map { (az, el) -> (if (az >= 360.0) 0.0 else az) to el.coerceIn(0.0, 90.0) }
            .sortedBy { it.first }
    }

    private fun readJson(t: String): List<Pair<Double, Double>>? {
        val obj = runCatching { JSONObject(t) }.getOrNull() ?: return null
        val arr = obj.optJSONArray("points") ?: return null
        return (0 until arr.length()).mapNotNull { i ->
            val p = arr.optJSONObject(i) ?: return@mapNotNull null
            // Alleen echte getallen, zoals horizon-survey ze schrijft ("10" als tekst telt niet; zoals de webversie)
            val az = (p.opt("az") as? Number)?.toDouble() ?: return@mapNotNull null
            val el = (p.opt("el") as? Number)?.toDouble() ?: return@mapNotNull null
            if (az.isFinite() && el.isFinite()) az to el else null
        }
    }

    private fun readCsv(t: String): List<Pair<Double, Double>> =
        t.lines().drop(1).mapNotNull { line ->
            val c = line.split(",").map { it.trim() }
            if (c.size < 2) return@mapNotNull null
            if (c.getOrNull(3) == "1") return@mapNotNull null          // ijkpunt: geen horizon
            val az = c[0].toDoubleOrNull() ?: return@mapNotNull null
            val el = c[1].toDoubleOrNull() ?: return@mapNotNull null
            if (az.isFinite() && el.isFinite()) az to el else null
        }

    /**
     * Tussen twee opeenvolgende punten (ook over het noorden heen) een geblokkeerde hindernis tot de
     * hoogste van de twee elevaties; gaten groter dan [MAX_GAP_DEG] en stukken op 0° blijven open.
     * Aansluitende stukken met dezelfde hoogte worden samengevoegd.
     */
    internal fun toObstacles(points: List<Pair<Double, Double>>): List<Obstacle> {
        // Stuk = van, tot, hoogte, en hoeveel graden het beslaat (om een volle cirkel te herkennen)
        data class Seg(val from: Double, val to: Double, val el: Double, val span: Double)
        val n = points.size
        val segs = mutableListOf<Seg>()
        for (i in 0 until n) {
            val (az1, el1) = points[i]
            val (az2, el2) = points[(i + 1) % n]
            val gap = ((az2 - az1) % 360.0 + 360.0) % 360.0
            if (gap == 0.0 || gap > MAX_GAP_DEG) continue
            val el = maxOf(el1, el2)
            if (el <= 0.0) continue
            segs += Seg(az1, az2, el, gap)
        }
        // Samenvoegen: aansluitend en even hoog
        val merged = mutableListOf<Seg>()
        for (s in segs) {
            val last = merged.lastOrNull()
            if (last != null && last.to == s.from && last.el == s.el) {
                merged[merged.size - 1] = last.copy(to = s.to, span = last.span + s.span)
            } else {
                merged += s
            }
        }
        // Over het noorden: laatste en eerste sluiten aan en zijn even hoog
        if (merged.size > 1) {
            val first = merged.first()
            val last = merged.last()
            if (last.to == first.from && last.el == first.el) {
                merged[merged.size - 1] = last.copy(to = first.to, span = last.span + first.span)
                merged.removeAt(0)
            }
        }
        return merged.map { s ->
            // Rondom even hoog gemeten: de hele cirkel (0 tot 360), niet "van 0 tot 0"
            if (s.span >= 360.0) Obstacle(0.0, 360.0, s.el, ObstacleKind.BLOCKED)
            else Obstacle(s.from, s.to, s.el, ObstacleKind.BLOCKED)
        }
    }
}
