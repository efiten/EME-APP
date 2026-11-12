package be.on5gs.eme.moon

/**
 * Plaatst de namen (callsigns) van stations op de kaart zonder dat ze elkaar
 * of andere stipjes overlappen.
 *
 * Werkwijze: voor elk label worden eerst 8 plaatsen vlak naast het stipje geprobeerd
 * (rechts, links, schuin boven/onder, boven, onder). Is alles bezet, dan worden dezelfde
 * richtingen stap voor stap verder weg geprobeerd; zo'n label krijgt dan een dun lijntje
 * naar zijn stipje.
 * Lukt ook dat niet, dan wordt de plaats met de minste overlap gekozen.
 */
object LabelPlacer {

    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun overlap(o: Box): Float {
            val w = minOf(right, o.right) - maxOf(left, o.left)
            val h = minOf(bottom, o.bottom) - maxOf(top, o.top)
            return if (w > 0 && h > 0) w * h else 0f
        }
        fun inside(width: Float, height: Float) = left >= 0 && top >= 0 && right <= width && bottom <= height
    }

    /** @param priority labels met voorrang (bv. de eigen roepnaam) worden eerst geplaatst. */
    data class Label(val x: Float, val y: Float, val width: Float, val height: Float, val priority: Boolean = false)

    data class Placement(val box: Box, val needsLeader: Boolean)

    /**
     * @param labels   ankerpunt (het stipje) en afmetingen van elk label
     * @param dotRadius straal van de stipjes (die mogen ook niet bedekt worden)
     * @param gap      ruimte tussen stipje en label
     * @param obstacles vaste vlakken die vrij moeten blijven (bv. eigen QTH, de maan)
     */
    fun place(
        labels: List<Label>,
        mapWidth: Float,
        mapHeight: Float,
        dotRadius: Float,
        gap: Float,
        obstacles: List<Box> = emptyList(),
    ): List<Placement> {
        val dots = labels.map { Box(it.x - dotRadius, it.y - dotRadius, it.x + dotRadius, it.y + dotRadius) }
        val taken = ArrayList<Box>(obstacles)
        val result = arrayOfNulls<Placement>(labels.size)

        // Van links naar rechts, dan van boven naar onder: geeft een rustig beeld
        val order = labels.indices.sortedWith(compareBy({ !labels[it].priority }, { labels[it].x }, { labels[it].y }))
        for (i in order) {
            val l = labels[i]
            val otherDots = dots.filterIndexed { j, _ -> j != i }
            var best: Placement? = null
            var bestOverlap = Float.MAX_VALUE
            // Eerst vlak naast het stipje, dan stapsgewijs verder weg (met lijntje)
            val rings = listOf(0f, 1.3f, 2.4f, 3.6f, 5f).map { dotRadius + gap + it * l.height }
            loop@ for ((ring, dist) in rings.withIndex()) {
                for (box in candidates(l, dist)) {
                    if (!box.inside(mapWidth, mapHeight)) continue
                    val ov = taken.sumOf { it.overlap(box).toDouble() }.toFloat() +
                        otherDots.sumOf { it.overlap(box).toDouble() }.toFloat()
                    if (ov < bestOverlap) {
                        bestOverlap = ov
                        best = Placement(box, needsLeader = ring > 0)
                    }
                    if (ov == 0f) break@loop
                }
            }
            val chosen = best ?: Placement(candidates(l, dotRadius + gap).first(), false)
            result[i] = chosen
            taken += chosen.box
        }
        return result.map { it!! }
    }

    /** De 8 kandidaat-plaatsen op afstand [d] van het stipje. */
    private fun candidates(l: Label, d: Float): List<Box> {
        val w = l.width
        val h = l.height
        val diag = d * 0.75f
        return listOf(
            Box(l.x + d, l.y - h / 2, l.x + d + w, l.y + h / 2),                 // rechts
            Box(l.x - d - w, l.y - h / 2, l.x - d, l.y + h / 2),                 // links
            Box(l.x + diag, l.y - diag - h, l.x + diag + w, l.y - diag),         // rechtsboven
            Box(l.x + diag, l.y + diag, l.x + diag + w, l.y + diag + h),         // rechtsonder
            Box(l.x - diag - w, l.y - diag - h, l.x - diag, l.y - diag),         // linksboven
            Box(l.x - diag - w, l.y + diag, l.x - diag, l.y + diag + h),         // linksonder
            Box(l.x - w / 2, l.y - d - h, l.x + w / 2, l.y - d),                 // boven
            Box(l.x - w / 2, l.y + d, l.x + w / 2, l.y + d + h),                 // onder
        )
    }
}
