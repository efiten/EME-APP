package be.on5gs.eme.n0uk

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import be.on5gs.eme.R
import be.on5gs.eme.chat.CQ_ORANGE
import be.on5gs.eme.chat.Cell
import be.on5gs.eme.chat.DIM
import be.on5gs.eme.chat.HEADER_BG
import be.on5gs.eme.chat.HeaderCell
import be.on5gs.eme.chat.ROW_ALT_BG
import be.on5gs.eme.cq.stationLatLon
import be.on5gs.eme.moon.MoonCalculator
import be.on5gs.eme.ui.theme.FixedFontScale
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Chat CQ van N0UK toont de CQ's van het laatste uur, zoals Live CQ en HB9Q. */
internal const val N0UK_CQ_WINDOW_MINUTES = 60

private val ERROR_RED = Color(0xFFEF5350)
private val GREEN = Color(0xFF66BB6A)
private val RED = Color(0xFFEF5350)
private val HHMM = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC)

/**
 * Tabblad N0UK (WSJT EME-chat op 144 MHz, www.chris.org), ALLEEN LEZEN.
 * Zelfde opbouw als HB9Q: Berichten, Deelnemers en Chat CQ. Geen login nodig.
 */
@Composable
fun N0ukScreen(myCallsign: String) {
    val st by N0ukStore.state.collectAsState()
    var subTab by remember { mutableIntStateOf(0) }       // 0 = berichten, 1 = deelnemers, 2 = Chat CQ
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Alleen ophalen zolang het tabblad op het scherm staat; N0ukStore houdt minstens 90 s tussen twee aanvragen
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                N0ukStore.refreshIfDue()
                now = System.currentTimeMillis()
                delay(5_000)
            }
        }
    }

    val writers = N0ukCqParser.recentWriters(st.messages, myCallsign)
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Text(stringResource(R.string.n0uk_info), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (st.loading) CircularProgressIndicator(Modifier.padding(start = 8.dp).width(18.dp), strokeWidth = 2.dp)
        }
        if (st.lastFetch != 0L) {
            Text(
                stringResource(R.string.updated, HHMM.format(Instant.ofEpochMilli(st.lastFetch))),
                style = MaterialTheme.typography.bodySmall,
                color = DIM,
            )
        }

        TabRow(selectedTabIndex = subTab) {
            Tab(selected = subTab == 0, onClick = { subTab = 0 }, text = { Text(stringResource(R.string.messages)) })
            Tab(selected = subTab == 1, onClick = { subTab = 1 }, text = { Text(stringResource(R.string.participants, writers.size)) })
            Tab(selected = subTab == 2, onClick = { subTab = 2 }, text = { Text(stringResource(R.string.tab_chatcq)) })
        }

        st.error?.let {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF3B1F1F)),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) { Text(stringResource(R.string.n0uk_error, it), color = ERROR_RED, modifier = Modifier.padding(10.dp)) }
        }

        Box(Modifier.weight(1f)) {
            when (subTab) {
                0 -> LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 6.dp)) {
                    items(st.messages, key = { "${it.utcMillis}-${it.callsign}-${it.text.hashCode()}" }) { MessageRow(it, myCallsign) }
                }
                1 -> WriterList(writers)
                else -> N0ukCqList(N0ukCqParser.recent(st.messages, now, N0UK_CQ_WINDOW_MINUTES), now)
            }
        }
    }
}

@Composable
private fun MessageRow(msg: N0ukMessage, myCallsign: String) {
    val kind = N0ukCqParser.kind(msg)
    val fromMe = msg.callsign.equals(myCallsign, ignoreCase = true)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${msg.date} ${msg.time}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(8.dp))
                Text(
                    msg.callsign,
                    fontWeight = FontWeight.Bold,
                    color = if (fromMe) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(6.dp))
                Text("${msg.name} ${msg.locator}".trim(), style = MaterialTheme.typography.bodySmall)
            }
            Text(
                msg.text,
                color = when (kind) {
                    N0ukKind.CQ -> CQ_ORANGE
                    N0ukKind.STOP -> DIM
                    N0ukKind.OTHER -> MaterialTheme.colorScheme.onSurface
                },
                fontWeight = if (kind == N0ukKind.CQ) FontWeight.Bold else null,
            )
        }
    }
}

/** "Schreven recent in de chat:" met tijd (UTC), callsign, naam en locator. */
@Composable
private fun WriterList(writers: List<N0ukMessage>) {
    LazyColumn(modifier = Modifier.padding(top = 6.dp)) {
        item {
            Text(
                stringResource(R.string.recent_in_chat),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
        }
        items(writers, key = { it.callsign }) { w ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                Text(w.time, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(52.dp))
                Text(w.callsign, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(96.dp))
                Text(w.name, modifier = Modifier.weight(1f))
                Text(w.locator, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// Kolombreedtes (dp); "call" krijgt de overblijvende ruimte
private val COL_UTC = 40.dp
private val COL_FREQ = 58.dp
private val COL_PERIOD = 36.dp
private val COL_MODE = 62.dp
private val COL_LOC = 54.dp
private val COL_MOON = 40.dp

/** Tabel met de N0UK-CQ's: UTC | freq | periode | call | mode | locator | maan. Gestopte CQ's zijn grijs. */
@Composable
internal fun N0ukCqList(cqs: List<N0ukCq>, now: Long) = FixedFontScale {
    val sub = remember(now / 60_000) { MoonCalculator.subLunarPoint(now) }
    Column {
        Text(
            stringResource(R.string.n0uk_cq_title, cqs.size),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        val h = stringResource(R.string.n0uk_cq_header).split(" - ")
        fun t(i: Int) = h.getOrElse(i) { "" }
        Row(Modifier.fillMaxWidth().background(HEADER_BG).padding(vertical = 6.dp, horizontal = 4.dp)) {
            HeaderCell(t(0), COL_UTC)
            HeaderCell(t(1), COL_FREQ)
            HeaderCell(t(2), COL_PERIOD)
            HeaderCell(t(3), null, Modifier.weight(1f))
            HeaderCell(t(4), COL_MODE)
            HeaderCell(t(5), COL_LOC)
            HeaderCell(t(6), COL_MOON, align = TextAlign.End)
        }
        if (cqs.isEmpty()) Text(stringResource(R.string.n0uk_cq_empty), modifier = Modifier.padding(top = 16.dp))
        LazyColumn {
            itemsIndexed(cqs, key = { _, c -> "${c.utcMillis}-${c.callsign}-${c.freq}" }) { i, c ->
                val dim = if (c.ended) DIM else Color.Unspecified
                val el = stationLatLon(c.locator)?.let { MoonCalculator.quickElevationDeg(sub, it.latDeg, it.lonDeg) }
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (i % 2 == 1) ROW_ALT_BG else Color.Transparent)
                        .padding(vertical = 7.dp, horizontal = 4.dp),
                ) {
                    Cell(c.time, COL_UTC, color = DIM)
                    Cell(c.freq, COL_FREQ, bold = !c.ended, color = dim)
                    Cell(c.period, COL_PERIOD, color = dim)
                    Text(
                        c.callsign,
                        modifier = Modifier.weight(1f).padding(end = 2.dp),
                        fontWeight = FontWeight.Bold,
                        color = if (c.ended) DIM else CQ_ORANGE,
                        textDecoration = if (c.ended) TextDecoration.LineThrough else null,
                        maxLines = 1,
                        fontSize = 11.5.sp,
                    )
                    Cell(c.mode, COL_MODE, color = dim)
                    Cell(c.locator.ifEmpty { "?" }, COL_LOC, color = dim)
                    Cell(
                        when {
                            el == null -> "?"
                            el > 0 -> String.format(Locale.US, "↑%.0f°", el)
                            else -> "↓"
                        },
                        COL_MOON,
                        color = when { el == null || c.ended -> DIM; el > 0 -> GREEN; else -> RED },
                        align = TextAlign.End,
                    )
                }
            }
        }
    }
}
