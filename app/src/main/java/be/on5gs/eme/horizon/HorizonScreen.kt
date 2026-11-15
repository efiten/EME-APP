package be.on5gs.eme.horizon

import be.on5gs.eme.degradation.EmeBand
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import be.on5gs.eme.AppClock
import be.on5gs.eme.R
import be.on5gs.eme.location.Maidenhead
import be.on5gs.eme.settings.SettingsRepository
import java.util.Locale

/** Kleuren van de horizongrafiek, zoals in de webversie. */
internal val BLOCKED_RED = Color(0xFFEF5350)
internal val PARTIAL_ORANGE = Color(0xFFFFA726)
private val MOON_YELLOW = Color(0xFFFFEB3B)
private val GRID = Color(0x33FFFFFF)

/** Blok "Mijn horizon" in de instellingen: korte samenvatting en een knop om te bewerken. */
@Composable
fun HorizonSection(horizon: Horizon, onEdit: () -> Unit) {
    Text(stringResource(R.string.horizon_title), style = MaterialTheme.typography.titleLarge)
    Text(
        stringResource(R.string.horizon_summary, fmt(horizon.minElDeg), horizon.obstacles.size),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag("horizon-summary"),
    )
    OutlinedButton(onClick = onEdit) { Text(stringResource(R.string.horizon_edit)) }
}

/** Eén hindernis zoals ze getypt wordt: tekst per veld, pas bij opslaan omgezet. */
private class ObstacleDraft(from: String, to: String, el: String, kind: ObstacleKind) {
    var from by mutableStateOf(from)
    var to by mutableStateOf(to)
    var el by mutableStateOf(el)
    var kind by mutableStateOf(kind)

    fun toObstacle(): Obstacle? {
        val f = parseAngle(from, 360.0) ?: return null
        val t = parseAngle(to, 360.0) ?: return null
        val e = parseAngle(el, 90.0) ?: return null
        return Obstacle(f, t, e, kind)
    }
}

/**
 * Scherm "Mijn horizon": ondergrens, hindernissen, diameter en band van de schotel en een grafiek met
 * de maanbaan van vandaag. [onSaved] krijgt de nieuwe horizon, de diameter (0 = geen schotel) en de
 * band van de schotel; het bewaren doet de aanroeper.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HorizonScreen(
    saved: Horizon,
    locator: String,
    savedDishM: Double = 0.0,
    savedDishBand: EmeBand = EmeBand.B1296,
    onSaved: (Horizon, Double, EmeBand) -> Unit,
) {
    var dishBand by remember { mutableStateOf(savedDishBand) }
    val bandMhz = dishBand.mhz
    var minText by remember { mutableStateOf(fmt(saved.minElDeg)) }
    var dishText by remember { mutableStateOf(if (savedDishM > 0.0) fmt(savedDishM) else "") }
    val rows = remember {
        mutableStateListOf<ObstacleDraft>().apply {
            saved.obstacles.forEach { add(ObstacleDraft(fmt(it.fromAzDeg), fmt(it.toAzDeg), fmt(it.maxElDeg), it.kind)) }
        }
    }
    val min = parseAngle(minText, 90.0)
    val obstacles = rows.map { it.toObstacle() }
    // Leeg = geen schotel; anders een diameter tot 100 m
    val dish = if (dishText.isBlank()) 0.0 else parseAngle(dishText, 100.0)
    val valid = min != null && obstacles.all { it != null } && dish != null
    // De grafiek toont wat al geldig getypt is, zodat je meteen ziet wat een wijziging doet
    val preview = Horizon(min ?: saved.minElDeg, obstacles.filterNotNull())

    Column(
        modifier = Modifier.padding(16.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.horizon_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.horizon_info), style = MaterialTheme.typography.bodySmall)

        HorizonChart(preview, locator)

        AngleField(minText, { minText = it }, R.string.horizon_min_el, 90, Modifier.fillMaxWidth())

        rows.forEachIndexed { i, r ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AngleField(r.from, { r.from = it }, R.string.horizon_from, 360, Modifier.weight(1f))
                        AngleField(r.to, { r.to = it }, R.string.horizon_to, 360, Modifier.weight(1f))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AngleField(r.el, { r.el = it }, R.string.horizon_up_to, 90, Modifier.weight(1f))
                        IconButton(onClick = { rows.removeAt(i) }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.horizon_delete))
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = r.kind == ObstacleKind.BLOCKED,
                            onClick = { r.kind = ObstacleKind.BLOCKED },
                            label = { Text(stringResource(R.string.horizon_blocked)) },
                        )
                        FilterChip(
                            selected = r.kind == ObstacleKind.PARTIAL,
                            onClick = { r.kind = ObstacleKind.PARTIAL },
                            label = { Text(stringResource(R.string.horizon_partial)) },
                        )
                    }
                }
            }
        }

        OutlinedButton(onClick = { rows.add(ObstacleDraft("", "", "", ObstacleKind.BLOCKED)) }) {
            Text(stringResource(R.string.horizon_add))
        }

        // Diameter van de schotel: marge van een halve bundelbreedte boven de horizon
        OutlinedTextField(
            value = dishText,
            onValueChange = { dishText = it.take(6) },
            label = { Text(stringResource(R.string.horizon_dish), maxLines = 1) },
            isError = dish == null,
            supportingText = {
                Text(
                    when {
                        dish == null -> stringResource(R.string.horizon_invalid, 100)
                        dish > 0.0 -> stringResource(R.string.horizon_margin, fmt(bandMhz), String.format(Locale.US, "%.2f", beamMarginDeg(dish, bandMhz)))
                        else -> stringResource(R.string.horizon_dish_info)
                    },
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().testTag("horizon-dish"),
        )
        // Band waarop de schotel gebruikt wordt (los van de Deg-tab), voor de marge
        Text(stringResource(R.string.horizon_dish_band), style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            EmeBand.entries.forEach { b ->
                FilterChip(selected = b == dishBand, onClick = { dishBand = b }, label = { Text(b.label) })
            }
        }

        Button(
            onClick = { onSaved(Horizon(min!!, obstacles.map { it!! }), dish!!, dishBand) },
            enabled = valid,
        ) { Text(stringResource(R.string.save)) }
    }
}

@Composable
private fun AngleField(value: String, onChange: (String) -> Unit, label: Int, max: Int, modifier: Modifier) {
    val ok = parseAngle(value, max.toDouble()) != null
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(6)) },
        label = { Text(stringResource(label), maxLines = 1) },
        isError = !ok,
        supportingText = if (ok) null else ({ Text(stringResource(R.string.horizon_invalid, max)) }),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

/**
 * Azimut 0–360° van links naar rechts, elevatie van 0 tot [yMax] van onder naar boven: de ondergrens
 * en de hindernissen als blokken (rood geblokkeerd, oranje gedeeltelijk), en de maanbaan van vandaag (UTC).
 */
@Composable
fun HorizonChart(horizon: Horizon, locator: String) {
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurface
    val pos = Maidenhead.toLatLon(locator)
    val dayStart = AppClock.now().let { it - it % 86_400_000L }
    val segments = remember(locator, dayStart) {
        if (pos == null) emptyList()
        else MoonWindows.trackSegments(MoonWindows.track(pos.latDeg, pos.lonDeg, dayStart, dayStart + 86_400_000L))
    }
    val yMax = chartTopDeg(horizon, segments.flatten().maxOfOrNull { it.elDeg } ?: 0.0)

    Column {
        Canvas(Modifier.fillMaxWidth().height(170.dp).testTag("horizon-chart")) {
            val left = 34.dp.toPx(); val right = size.width - 6.dp.toPx()
            val top = 6.dp.toPx(); val bottom = size.height - 18.dp.toPx()
            fun x(az: Double) = (left + (right - left) * az / 360.0).toFloat()
            fun y(el: Double) = (bottom - (bottom - top) * (el.coerceIn(0.0, yMax) / yMax)).toFloat()
            val style = TextStyle(color = labelColor, fontSize = 10.sp)

            // Raster en schaal
            for (az in 0..360 step 90) {
                drawLine(GRID, Offset(x(az.toDouble()), top), Offset(x(az.toDouble()), bottom))
                val t = measurer.measure("$az°", style)
                drawText(t, topLeft = Offset((x(az.toDouble()) - t.size.width / 2f).coerceIn(0f, size.width - t.size.width), bottom + 2.dp.toPx()))
            }
            for (el in 0..yMax.toInt() step 10) {
                drawLine(GRID, Offset(left, y(el.toDouble())), Offset(right, y(el.toDouble())))
                val t = measurer.measure("$el°", style)
                drawText(t, topLeft = Offset(left - t.size.width - 4.dp.toPx(), y(el.toDouble()) - t.size.height / 2f))
            }

            // Ondergrens over de hele breedte
            if (horizon.minElDeg > 0) {
                drawRect(BLOCKED_RED.copy(alpha = 0.45f), Offset(left, y(horizon.minElDeg)), Size(right - left, bottom - y(horizon.minElDeg)))
            }
            // Hindernissen; over het noorden heen in twee stukken
            horizon.obstacles.forEach { o ->
                val color = (if (o.kind == ObstacleKind.BLOCKED) BLOCKED_RED else PARTIAL_ORANGE).copy(alpha = 0.6f)
                chartSpans(o).forEach { (a, b) ->
                    drawRect(color, Offset(x(a), y(o.maxElDeg)), Size(x(b) - x(a), bottom - y(o.maxElDeg)))
                }
            }
            // Maanbaan van vandaag
            segments.forEach { seg ->
                val path = Path()
                seg.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.azDeg), y(p.elDeg)) else path.lineTo(x(p.azDeg), y(p.elDeg)) }
                drawPath(path, MOON_YELLOW, style = Stroke(width = 2.dp.toPx()))
            }
        }
        Text("— " + stringResource(R.string.horizon_moon_today), color = MOON_YELLOW, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Bovenkant van de grafiek: 40°, of hoger (per 10°, tot 90°) als een hindernis, de ondergrens of
 * de maan ([moonMaxElDeg], de hoogste stand vandaag) hoger komt. Zo wordt de maanbaan nooit afgesneden.
 */
internal fun chartTopDeg(h: Horizon, moonMaxElDeg: Double = 0.0): Double {
    val highest = maxOf((h.obstacles.map { it.maxElDeg } + h.minElDeg).max() + 5.0, moonMaxElDeg)
    return maxOf(40.0, minOf(90.0, Math.ceil(highest / 10.0) * 10.0))
}

/** De azimutstukken van een hindernis om te tekenen: één, of twee als ze over het noorden gaat. */
internal fun chartSpans(o: Obstacle): List<Pair<Double, Double>> {
    if (o.toAzDeg - o.fromAzDeg >= 360.0) return listOf(0.0 to 360.0)
    val from = normalizeAz(o.fromAzDeg)
    val to = normalizeAz(o.toAzDeg)
    return if (from <= to) listOf(from to to) else listOf(from to 360.0, 0.0 to to)
}

private fun fmt(v: Double): String {
    val r = Math.round(v * 100) / 100.0
    return if (r == Math.floor(r)) r.toLong().toString() else String.format(Locale.US, "%s", r)
}
