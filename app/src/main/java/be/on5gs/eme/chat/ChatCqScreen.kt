package be.on5gs.eme.chat

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import be.on5gs.eme.R
import be.on5gs.eme.ui.theme.FixedFontScale

private val CQ_ORANGE = Color(0xFFFFB74D)
private val DIM = Color(0xFF9FB0C8)
private val ERROR_RED = Color(0xFFEF5350)

/** Kolombreedtes van de tabel (dp). De kolom "call" krijgt de overblijvende ruimte. */
private val COL_UTC = 40.dp
private val COL_FREQ = 62.dp
private val COL_PERIOD = 36.dp
private val COL_MODE = 60.dp
private val COL_CFOM = 40.dp
private val COL_HZ = 34.dp

private val HEADER_BG = Color(0xFF1B3054)
private val ROW_ALT_BG = Color(0xFF102544)

/** De tabel zelf (los van het ophalen, zodat ze ook getest kan worden). */
@Composable
internal fun ChatCqList(bandLabel: String, cqs: List<ChatCq>) = FixedFontScale {
    Column {
        Text(
            stringResource(R.string.chatcq_title, bandLabel, cqs.size),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 6.dp),
        )

        // Kopregel: de vertaalde verklaring "UTC - freq - periode - ..." opgesplitst in kolommen
        val titles = stringResource(R.string.chatcq_header).split(" - ")
        Row(Modifier.fillMaxWidth().background(HEADER_BG).padding(vertical = 6.dp, horizontal = 4.dp)) {
            val h = { i: Int -> titles.getOrElse(i) { "" } }
            HeaderCell(h(0), COL_UTC)
            HeaderCell(h(1), COL_FREQ)
            HeaderCell(h(2), COL_PERIOD)
            HeaderCell(h(3), null, Modifier.weight(1f))
            HeaderCell(h(4), COL_MODE)
            HeaderCell(h(5), COL_CFOM)
            HeaderCell(h(6), COL_HZ, align = TextAlign.End)
        }

        if (cqs.isEmpty()) {
            Text(stringResource(R.string.chatcq_empty), modifier = Modifier.padding(top = 16.dp))
        }
        LazyColumn {
            itemsIndexed(cqs, key = { _, cq -> cq.messageId }) { i, cq -> CqRow(cq, odd = i % 2 == 1) }
        }
    }
}

@Composable
private fun HeaderCell(text: String, width: Dp?, modifier: Modifier = Modifier, align: TextAlign = TextAlign.Start) {
    Text(
        text,
        color = MaterialTheme.colorScheme.secondary,
        fontSize = 9.5.sp,
        letterSpacing = 0.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = align,
        modifier = (if (width != null) modifier.width(width) else modifier).padding(end = 2.dp),
    )
}

/** Eén CQ als tabelregel: 09:01  1296.088  1st  F5AOL  Q65-60C  CFOM  1500 */
@Composable
private fun CqRow(cq: ChatCq, odd: Boolean) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (odd) ROW_ALT_BG else Color.Transparent)
            .padding(vertical = 7.dp, horizontal = 4.dp),
    ) {
        Cell(cq.time, COL_UTC, color = DIM)
        Cell(cq.freq, COL_FREQ, bold = true)
        Cell(cq.period, COL_PERIOD)
        Cell(cq.callsign, null, Modifier.weight(1f), bold = true, color = CQ_ORANGE)
        Cell(cq.mode, COL_MODE)
        Cell(if (cq.cfom == "own echo") "echo" else cq.cfom, COL_CFOM)
        Cell(cq.hz, COL_HZ, align = TextAlign.End)
    }
}

@Composable
private fun Cell(
    text: String,
    width: Dp?,
    modifier: Modifier = Modifier,
    bold: Boolean = false,
    color: Color = Color.Unspecified,
    align: TextAlign = TextAlign.Start,
) {
    Text(
        text,
        fontSize = 11.5.sp,
        letterSpacing = 0.sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        textAlign = align,
        modifier = (if (width != null) modifier.width(width) else modifier).padding(end = 2.dp),
    )
}
