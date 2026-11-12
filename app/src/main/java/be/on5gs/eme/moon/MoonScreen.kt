package be.on5gs.eme.moon

import androidx.compose.ui.platform.LocalConfiguration
import be.on5gs.eme.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import be.on5gs.eme.cq.Band
import be.on5gs.eme.cq.LiveCqParser
import be.on5gs.eme.cq.color
import be.on5gs.eme.cq.LiveCqStore
import be.on5gs.eme.cq.stationLatLon
import be.on5gs.eme.location.Maidenhead
import be.on5gs.eme.settings.SettingsRepository
import be.on5gs.eme.ui.theme.FixedFontScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private const val REFRESH_MS = 2_000L
/** CQ-stations op de kaart: zelfde selectie als de CQ-lijst (laatste uur). */
private const val CQ_MAX_AGE_MINUTES = 60

private val HHMM = DateTimeFormatter.ofPattern("HH:mm")
private val GREEN = Color(0xFF66BB6A)

@Composable
fun MoonScreen(settings: SettingsRepository, callsign: String, locator: String) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = System.currentTimeMillis()
                delay(REFRESH_MS)
            }
        }
    }
    // Live CQ ophalen voor de kaart (gedeeld met het CQ-tabblad, max. 1x per 2 minuten)
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                LiveCqStore.refreshIfOlderThan(LiveCqStore.AUTO_REFRESH_MS)
                delay(15_000)
            }
        }
    }

    val zone = ZoneId.systemDefault()
    // Taal van de app (gekozen in de instellingen) voor dagnamen en tijdzonenamen
    val locale = LocalConfiguration.current.locales[0]
    val dateTime = remember(locale) { DateTimeFormatter.ofPattern("EEE dd-MM-yyyy  HH:mm:ss", locale) }
    val pos = Maidenhead.toLatLon(locator)

    // Opkomst/culminatie/ondergang: elke minuut herberekenen, buiten de UI-thread
    var events by remember { mutableStateOf<MoonEvents.Events?>(null) }
    val minute = now / 60_000
    LaunchedEffect(locator, minute) {
        if (pos != null) {
            events = withContext(Dispatchers.Default) { MoonEvents.compute(pos.latDeg, pos.lonDeg, now) }
        }
    }

    val cq by LiveCqStore.state.collectAsState()
    val bands = remember { settings.cqBands }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Datum en tijd: lokaal en UTC
        val instant = Instant.ofEpochMilli(now)
        val zoneName = zone.getDisplayName(TextStyle.SHORT, locale)
        TimeLines(
            stringResource(R.string.time_local) to (dateTime.format(instant.atZone(zone)) to zoneName),
            "UTC" to (dateTime.format(instant.atZone(ZoneOffset.UTC)) to ""),
        )
        Spacer(Modifier.height(8.dp))

        if (pos == null) {
            Text(stringResource(R.string.no_locator), color = MaterialTheme.colorScheme.error)
            return@Column
        }

        val sub = MoonCalculator.subLunarPoint(now)
        // Eén stip per station en band (zelfde selectie als het tabblad Live CQ)
        val stations = LiveCqParser.select(cq.spots, bands, now, CQ_MAX_AGE_MINUTES)
            .mapNotNull { spot ->
                val band = spot.band ?: return@mapNotNull null
                stationLatLon(spot.grid)?.let { MapStation(spot.callsign, it.latDeg, it.lonDeg, band.color) }
            }
        // Kaartlabels altijd even klein, ook bij een grote systeem-lettergrootte
        val phase = remember(now / 600_000) { MoonPhase.at(now) }   // elke 10 minuten herberekenen
        FixedFontScale { WorldMap(sub, pos.latDeg to pos.lonDeg, callsign, stations, phase) }
        BandLegend(bands)
        Text(
            stringResource(R.string.moon_phase, phaseName(phase.nameIndex), Math.round(phase.illuminated * 100).toInt()),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        Text("$callsign  ·  $locator", style = MaterialTheme.typography.titleMedium)
        val moon = MoonCalculator.position(pos.latDeg, pos.lonDeg, now)
        Row(Modifier.fillMaxWidth()) {
            BigValue("AZ", moon.azimuthDeg, Modifier.weight(1f))
            BigValue("EL", moon.elevationDeg, Modifier.weight(1f))
        }
        Text(
            stringResource(R.string.distance, String.format(locale, "%,.0f", moon.distanceKm)),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))

        events?.let { ev -> EventsCard(ev, now, zone, locale) }
    }
}

@Composable
private fun phaseName(index: Int): String = stringResource(
    when (index) {
        0 -> R.string.phase_0
        1 -> R.string.phase_1
        2 -> R.string.phase_2
        3 -> R.string.phase_3
        4 -> R.string.phase_4
        5 -> R.string.phase_5
        6 -> R.string.phase_6
        else -> R.string.phase_7
    },
)

/** "Live CQ spots:" gevolgd door de aangevinkte banden, elk in de kleur die op de kaart gebruikt wordt. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BandLegend(bands: Set<Band>) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.livecq_spots), style = MaterialTheme.typography.bodySmall)
        if (bands.isEmpty()) Text("—", style = MaterialTheme.typography.bodySmall)
        Band.entries.filter { it in bands }.forEach { band ->
            Text(band.label, color = band.color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Datum/tijd-regels in twee kolommen; de labelkolom past zich aan de langste vertaling aan. */
@Composable
private fun TimeLines(vararg lines: Pair<String, Pair<String, String>>) = FixedFontScale {
    Row(verticalAlignment = Alignment.Bottom) {
        Column(horizontalAlignment = Alignment.End) {
            lines.forEach { (label, _) ->
                Text(label, color = MaterialTheme.colorScheme.secondary, fontSize = 17.sp, maxLines = 1, softWrap = false)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column {
            lines.forEach { (_, value) ->
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(value.first, fontFamily = FontFamily.Monospace, fontSize = 17.sp, maxLines = 1, softWrap = false)
                    if (value.second.isNotEmpty()) Text("  ${value.second}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun BigValue(label: String, value: Double, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 20.sp, color = MaterialTheme.colorScheme.secondary)
        Text(
            String.format(Locale.US, "%.1f°", value),
            fontSize = 46.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/**
 * Staat de maan op: culminatie (huidige doorgang), ondergang en VOLGENDE opkomst.
 * Staat ze onder: opkomst, culminatie en ondergang van de volgende doorgang.
 */
@Composable
private fun EventsCard(ev: MoonEvents.Events, now: Long, zone: ZoneId, locale: Locale) {
    val tomorrow = stringResource(R.string.tomorrow)
    val yesterday = stringResource(R.string.yesterday)
    val fmt = { t: Long -> localTime(t, now, zone, tomorrow, yesterday, locale) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.times_local, zone.getDisplayName(TextStyle.SHORT, locale)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            if (ev.isUp) {
                Text(stringResource(R.string.moon_up_now), color = GREEN, fontWeight = FontWeight.Bold)
                EventLine(stringResource(R.string.culmination), ev.culmination, now, fmt) { String.format(Locale.US, "EL %.1f°", it.elevationDeg) }
                EventLine(stringResource(R.string.moonset), ev.nextSet, now, fmt) { String.format(Locale.US, "AZ %.0f°", it.azimuthDeg) }
                EventLine(stringResource(R.string.next_moonrise), ev.nextRise, now, fmt) { String.format(Locale.US, "AZ %.0f°", it.azimuthDeg) }
            } else {
                EventLine(stringResource(R.string.moonrise), ev.nextRise, now, fmt) { String.format(Locale.US, "AZ %.0f°", it.azimuthDeg) }
                EventLine(stringResource(R.string.culmination), ev.culmination, now, fmt) { String.format(Locale.US, "EL %.1f°", it.elevationDeg) }
                EventLine(stringResource(R.string.moonset), ev.nextSet, now, fmt) { String.format(Locale.US, "AZ %.0f°", it.azimuthDeg) }
            }
        }
    }
}

@Composable
private fun EventLine(
    label: String,
    event: MoonEvents.Event?,
    now: Long,
    format: (Long) -> String,
    extra: (MoonEvents.Event) -> String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        if (event == null) {
            Text(stringResource(R.string.not_within_48h), style = MaterialTheme.typography.bodySmall)
        } else {
            Text(
                format(event.utcMillis),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = if (event.utcMillis < now) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f) else Color.Unspecified,
            )
            Text(extra(event), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 10.dp).width(64.dp))
        }
    }
}

/** "21:45", of "morgen 06:05" / "gisteren 23:10" / "ma 06:05" als het niet vandaag is. */
internal fun localTime(
    utcMillis: Long,
    now: Long,
    zone: ZoneId,
    tomorrow: String,
    yesterday: String,
    locale: Locale,
): String {
    val t = Instant.ofEpochMilli(utcMillis).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val day: LocalDate = t.toLocalDate()
    val prefix = when (day) {
        today -> ""
        today.plusDays(1) -> "$tomorrow "
        today.minusDays(1) -> "$yesterday "
        else -> t.dayOfWeek.getDisplayName(TextStyle.SHORT, locale) + " "
    }
    return prefix + HHMM.format(t)
}
