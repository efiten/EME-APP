package be.on5gs.eme.degradation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import be.on5gs.eme.R
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/** Hoeveel dagen per maand een groene cirkel krijgen. */
internal const val BEST_DAYS_PER_MONTH = 4

/** Groen van "uitstekend" in de Deg-tab. */
private val BEST_GREEN = Color(0xFF00E676)

/**
 * Maandkalender voor de Deg-tab, in een venster. De [BEST_DAYS_PER_MONTH] dagen met de laagste
 * degradatie van de getoonde maand krijgen een groene cirkel ([bestDays] berekent ze per maand).
 * Een tik op een dag kiest die dag.
 */
@Composable
internal fun DegCalendarDialog(
    selected: LocalDate,
    today: LocalDate,
    bestDays: (YearMonth) -> Set<LocalDate>,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    // Maand als getal (jaar * 12 + maand), zodat hij een draaiing van het scherm overleeft
    var monthIndex by rememberSaveable { mutableIntStateOf(selected.year * 12 + selected.monthValue - 1) }
    val month = YearMonth.of(monthIndex / 12, monthIndex % 12 + 1)
    val best = remember(monthIndex) { bestDays(month) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
        text = {
            DegCalendarMonth(month, selected, today, best, onMonth = { monthIndex = it.year * 12 + it.monthValue - 1 }, onPick = onPick)
        },
    )
}

/** De inhoud van de kalender: maand met pijltjes, weekdagen, dagen en de uitleg. */
@Composable
internal fun DegCalendarMonth(
    month: YearMonth,
    selected: LocalDate,
    today: LocalDate,
    best: Set<LocalDate>,
    onMonth: (YearMonth) -> Unit,
    onPick: (LocalDate) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val title = remember(month, locale) {
        DateTimeFormatter.ofPattern("LLLL yyyy", locale).format(month).replaceFirstChar { it.titlecase(locale) }
    }
    val firstDay = firstDayOfWeek(locale, android.content.res.Resources.getSystem().configuration.locales[0].country)
    val weekDays = (0 until 7).map { firstDay.plus(it.toLong()) }

    Column {
        // Maand met pijltjes
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onMonth(month.minusMonths(1)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.deg_prev_month))
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).testTag("deg-calendar-title"),
            )
            IconButton(onClick = { onMonth(month.plusMonths(1)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.deg_next_month))
            }
        }
        // Namen van de weekdagen, in de volgorde van de taal (bv. maandag eerst)
        Row(Modifier.fillMaxWidth()) {
            weekDays.forEach { d ->
                Text(
                    d.getDisplayName(TextStyle.SHORT, locale),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        // De dagen, in weken van 7
        val lead = Math.floorMod(month.atDay(1).dayOfWeek.value - firstDay.value, 7)
        val cells = List(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                (0 until 7).forEach { i ->
                    val date = week.getOrNull(i)
                    Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                        if (date != null) {
                            DayCell(date, date == selected, date == today, date in best) { onPick(date) }
                        }
                    }
                }
            }
        }
        // Uitleg van de groene cirkel (zonder locator valt er niets te berekenen)
        if (best.isNotEmpty()) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).border(2.dp, BEST_GREEN, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.deg_calendar_best, BEST_DAYS_PER_MONTH),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun DayCell(date: LocalDate, isSelected: Boolean, isToday: Boolean, isBest: Boolean, onClick: () -> Unit) {
    var m = Modifier.fillMaxWidth().aspectRatio(1f).clip(CircleShape)
    if (isSelected) m = m.background(MaterialTheme.colorScheme.primary, CircleShape)
    if (isBest) m = m.border(2.dp, BEST_GREEN, CircleShape).testTag("deg-best-${date.dayOfMonth}")
    Box(m.clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(
            date.dayOfMonth.toString(),
            color = when {
                isSelected -> MaterialTheme.colorScheme.onPrimary
                isToday -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            },
            fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/**
 * Eerste dag van de week. De taal in de app heeft geen land ("nl"), dus het land komt van de
 * telefoon ([systemCountry], bv. "BE" -> maandag, "US" -> zondag). Geen land bekend: maandag.
 */
internal fun firstDayOfWeek(locale: Locale, systemCountry: String): DayOfWeek {
    val country = locale.country.ifEmpty { systemCountry }
    if (country.isEmpty()) return DayOfWeek.MONDAY
    return WeekFields.of(Locale(locale.language, country)).firstDayOfWeek
}
