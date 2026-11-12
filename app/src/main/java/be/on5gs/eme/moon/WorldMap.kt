package be.on5gs.eme.moon

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp

/** Een station op de kaart (bv. een CQ uit Live CQ), getekend in de kleur van zijn band. */
data class MapStation(val callsign: String, val latDeg: Double, val lonDeg: Double, val color: Color)

// Kleuren van de kaart
private val OCEAN = Color(0xFF061226)
private val LAND = Color(0xFF2B4466)
private val LAND_EDGE = Color(0xFF3F5E86)
private val GRID = Color(0x33FFFFFF)
private val FOOTPRINT = Color(0x38FFE082)       // maan boven de horizon: doorschijnend geel
private val FOOTPRINT_EDGE = Color(0xCCFFE082)
private val MOON = Color(0xFFFFE9A8)
private val MOON_DARK = Color(0xFF55607A)
private const val MOON_RADIUS_DP = 11f
private val ME = Color(0xFFFF9800)
private val LEADER = Color(0x99FFFFFF)

private val TEXT_SHADOW = Shadow(Color.Black, Offset(0f, 0f), blurRadius = 3f)

/** Rastergrootte (graden) voor het berekenen van het maangebied. */
private const val CELL_DEG = 1.5

/**
 * Wereldkaart (equirectangulaire projectie) met:
 *  - in geel het gebied waar de maan nu boven de horizon staat (EL > 0)
 *  - de maan boven het sub-lunaire punt
 *  - de eigen positie (oranje) en CQ-stations (groen = maan op, grijs = maan onder)
 */
@Composable
fun WorldMap(
    sub: MoonCalculator.SubLunarPoint,
    me: Pair<Double, Double>?,          // lat, lon
    myCallsign: String,
    stations: List<MapStation>,
    phase: MoonPhase.Phase? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val land = remember { LandShapes.load(context) }
    val textMeasurer = rememberTextMeasurer()
    // Het maangebied verandert traag: per 0,1° van het sub-lunaire punt opnieuw berekenen
    val footprint = remember(Math.round(sub.latDeg * 10), Math.round(sub.lonDeg * 10)) { footprintRuns(sub) }
    val edge = remember(Math.round(sub.latDeg * 10), Math.round(sub.lonDeg * 10)) { horizonEdge(sub) }

    Canvas(modifier.fillMaxWidth().aspectRatio(2f)) {
        clipRect {
            drawRect(OCEAN)
            drawLand(land)
            drawGrid()

            // Maangebied: horizontale stroken per rasterrij
            for (run in footprint) {
                // Afgerond op hele pixels, zodat rijen precies op elkaar aansluiten (geen strepen)
                val x0 = Math.round(lonToX(run.lonFrom, size)).toFloat()
                val x1 = Math.round(lonToX(run.lonTo, size)).toFloat()
                val y0 = Math.round(latToY(run.latTop, size)).toFloat()
                val y1 = Math.round(latToY(run.latTop - CELL_DEG, size)).toFloat()
                drawRect(FOOTPRINT, Offset(x0, y0), Size(x1 - x0, y1 - y0))
            }
            for ((lat, lon) in edge) {
                drawCircle(FOOTPRINT_EDGE, radius = 0.9f * density, center = Offset(lonToX(lon, size), latToY(lat, size)))
            }

            // Vaste vlakken die vrij moeten blijven: de maan, het eigen stipje en zijn label
            val moonC = Offset(lonToX(sub.lonDeg, size), latToY(sub.latDeg, size))
            val moonR = (MOON_RADIUS_DP + 1f) * density
            val obstacles = mutableListOf(LabelPlacer.Box(moonC.x - moonR, moonC.y - moonR, moonC.x + moonR, moonC.y + moonR))
            val meStyle = TextStyle(color = ME, fontSize = 9.sp, fontWeight = FontWeight.Bold, shadow = TEXT_SHADOW)
            val meC = me?.let { (lat, lon) -> Offset(lonToX(lon, size), latToY(lat, size)) }
            val meText = textMeasurer.measure(myCallsign, meStyle)
            val meR = 4f * density
            meC?.let { c -> obstacles += LabelPlacer.Box(c.x - meR, c.y - meR, c.x + meR, c.y + meR) }

            // CQ-stations: stipje + callsign in de kleur van de band, zonder overlap
            val dotR = 2.5f * density
            val measured = stations.map { st ->
                textMeasurer.measure(st.callsign, TextStyle(color = st.color, fontSize = 8.sp, fontWeight = FontWeight.Bold, shadow = TEXT_SHADOW))
            }
            val anchors = stations.map { Offset(lonToX(it.lonDeg, size), latToY(it.latDeg, size)) }
            // Alle labels samen plaatsen; de eigen roepnaam eerst (voorrang), dan de CQ-stations
            val labels = buildList {
                stations.indices.forEach {
                    add(LabelPlacer.Label(anchors[it].x, anchors[it].y, measured[it].size.width.toFloat(), measured[it].size.height.toFloat()))
                }
                if (meC != null) add(LabelPlacer.Label(meC.x, meC.y, meText.size.width.toFloat(), meText.size.height.toFloat(), priority = true))
            }
            val allPlacements = LabelPlacer.place(
                labels = labels,
                mapWidth = size.width,
                mapHeight = size.height,
                dotRadius = dotR,
                gap = 1.5f * density,
                obstacles = obstacles,
            )
            val placements = allPlacements.take(stations.size)
            val mePlacement = if (meC != null) allPlacements.last() else null
            for (i in stations.indices) {
                val p = placements[i]
                if (p.needsLeader) {
                    val target = Offset(
                        anchors[i].x.coerceIn(p.box.left, p.box.right),
                        anchors[i].y.coerceIn(p.box.top, p.box.bottom),
                    )
                    drawLine(LEADER, anchors[i], target, strokeWidth = 0.7f * density)
                }
            }
            for (i in stations.indices) {
                drawCircle(Color.Black, radius = dotR + 1f * density, center = anchors[i])
                drawCircle(stations[i].color, radius = dotR, center = anchors[i])
            }
            for (i in stations.indices) {
                drawText(measured[i], topLeft = Offset(placements[i].box.left, placements[i].box.top))
            }

            // Eigen QTH
            if (meC != null && mePlacement != null) {
                if (mePlacement.needsLeader) {
                    val b = mePlacement.box
                    drawLine(ME, meC, Offset(meC.x.coerceIn(b.left, b.right), meC.y.coerceIn(b.top, b.bottom)), strokeWidth = 0.7f * density)
                }
                drawCircle(Color.Black, radius = meR + 1f * density, center = meC)
                drawCircle(ME, radius = meR, center = meC)
                drawText(meText, topLeft = Offset(mePlacement.box.left, mePlacement.box.top))
            }

            // De maan boven het sub-lunaire punt
            drawMoonPhase(moonC, MOON_RADIUS_DP * density, phase)
        }
    }
}

/**
 * Tekent de maan met haar fase (zoals gezien vanaf het noordelijk halfrond):
 * donkere schijf, met daarop het verlichte deel begrensd door de rand en de terminator (halve ellips).
 */
internal fun DrawScope.drawMoonPhase(c: Offset, r: Float, phase: MoonPhase.Phase?) {
    drawCircle(Color.Black, radius = r + 1.2f * density, center = c)
    drawCircle(MOON_DARK, radius = r, center = c)
    val k = phase?.illuminated ?: 1.0
    when {
        k >= 0.99 -> drawCircle(MOON, radius = r, center = c)
        k > 0.01 -> {
            val waxing = phase?.waxing ?: true
            val a = (r * kotlin.math.abs(1 - 2 * k)).toFloat()     // halve breedte van de terminator-ellips
            val circle = androidx.compose.ui.geometry.Rect(c.x - r, c.y - r, c.x + r, c.y + r)
            val ellipse = androidx.compose.ui.geometry.Rect(c.x - a, c.y - r, c.x + a, c.y + r)
            val crescent = k < 0.5
            val path = Path().apply {
                moveTo(c.x, c.y - r)
                // Verlichte rand: rechts bij wassende maan, links bij afnemende maan
                arcTo(circle, -90f, if (waxing) 180f else -180f, false)
                // Terminator van onder terug naar boven
                val viaRight = if (waxing) crescent else !crescent
                arcTo(ellipse, 90f, if (viaRight) -180f else 180f, false)
                close()
            }
            drawPath(path, MOON)
        }
    }
    drawCircle(Color(0x55000000), radius = r, center = c, style = Stroke(width = 0.8f * density))
}

// ---------- Projectie ----------

private fun lonToX(lon: Double, size: Size) = ((lon + 180.0) / 360.0 * size.width).toFloat()
private fun latToY(lat: Double, size: Size) = ((90.0 - lat) / 180.0 * size.height).toFloat()

private fun DrawScope.drawLand(land: List<FloatArray>) {
    for (ring in land) {
        val path = Path()
        var i = 0
        while (i < ring.size) {
            val x = ((ring[i] + 180f) / 360f) * size.width
            val y = ((90f - ring[i + 1]) / 180f) * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            i += 2
        }
        path.close()
        drawPath(path, LAND)
        drawPath(path, LAND_EDGE, style = Stroke(width = 0.6f * density))
    }
}

private fun DrawScope.drawGrid() {
    for (lon in -150..150 step 30) {
        val x = lonToX(lon.toDouble(), size)
        drawLine(GRID, Offset(x, 0f), Offset(x, size.height), strokeWidth = 0.6f * density)
    }
    for (lat in -60..60 step 30) {
        val y = latToY(lat.toDouble(), size)
        drawLine(GRID, Offset(0f, y), Offset(size.width, y), strokeWidth = if (lat == 0) 1f * density else 0.6f * density)
    }
}

/** Rand van het maangebied (EL = 0): per lengtegraad de breedte(s) waar de elevatie van teken wisselt. */
internal fun horizonEdge(sub: MoonCalculator.SubLunarPoint): List<Pair<Double, Double>> {
    val pts = ArrayList<Pair<Double, Double>>()
    var lon = -180.0
    while (lon <= 180.0) {
        var lat = -90.0
        var prev = MoonCalculator.quickElevationDeg(sub, lat, lon)
        while (lat < 90.0) {
            val next = lat + 0.5
            val e = MoonCalculator.quickElevationDeg(sub, next, lon)
            if ((prev > 0) != (e > 0)) pts += next to lon
            prev = e
            lat = next
        }
        lon += 1.0
    }
    return pts
}

// ---------- Maangebied ----------

internal data class FootprintRun(val latTop: Double, val lonFrom: Double, val lonTo: Double)

/** Per rij van [CELL_DEG] graden: de stukken waar de maan boven de horizon staat. */
internal fun footprintRuns(sub: MoonCalculator.SubLunarPoint): List<FootprintRun> {
    val runs = ArrayList<FootprintRun>()
    var latTop = 90.0
    while (latTop > -90.0) {
        val latMid = latTop - CELL_DEG / 2
        var runStart: Double? = null
        var lon = -180.0
        while (lon < 180.0) {
            val up = MoonCalculator.quickElevationDeg(sub, latMid, lon + CELL_DEG / 2) > 0
            if (up && runStart == null) runStart = lon
            if (!up && runStart != null) {
                runs += FootprintRun(latTop, runStart, lon)
                runStart = null
            }
            lon += CELL_DEG
        }
        if (runStart != null) runs += FootprintRun(latTop, runStart, 180.0)
        latTop -= CELL_DEG
    }
    return runs
}

// ---------- Landcontouren (Natural Earth 1:110m, uit assets/land110m.txt) ----------

object LandShapes {
    @Volatile private var cache: List<FloatArray>? = null

    fun load(context: Context): List<FloatArray> = cache ?: synchronized(this) {
        cache ?: context.assets.open("land110m.txt").bufferedReader().useLines { lines ->
            parse(lines)
        }.also { cache = it }
    }

    /** Eén ring per regel: "lon,lat lon,lat ...". Regels met # zijn commentaar. */
    internal fun parse(lines: Sequence<String>): List<FloatArray> =
        lines.filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
            val pts = line.trim().split(' ')
            val arr = FloatArray(pts.size * 2)
            pts.forEachIndexed { i, p ->
                val comma = p.indexOf(',')
                arr[2 * i] = p.substring(0, comma).toFloat()
                arr[2 * i + 1] = p.substring(comma + 1).toFloat()
            }
            arr
        }.toList()
}
