package be.on5gs.eme.degradation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import be.on5gs.eme.location.Maidenhead
import be.on5gs.eme.settings.SettingsRepository
import be.on5gs.eme.ui.theme.EmeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Zonder remember(now, pos, band, tsys) om de "nu"-berekening heen, zou een niet-gerelateerde
 * recompositie (bv. een reeks aan/uit zetten) Degradation.compute() opnieuw aanroepen, ook al
 * is er niets veranderd dat de uitkomst beïnvloedt.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DegradationScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun resetCounter() {
        currentComputeCallsForTest = 0
    }

    @Test
    fun currentValueIsNotRecomputedOnAnUnrelatedRecomposition() {
        val settings = SettingsRepository(ApplicationProvider.getApplicationContext())
        settings.degBand = EmeBand.B144
        compose.setContent {
            EmeTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize()) { DegradationScreen(settings, "JO21dd") }
                }
            }
        }
        compose.waitForIdle()
        val callsAfterFirstRender = currentComputeCallsForTest

        // Onafhankelijke recompositie: de kalenderknop opent alleen de datumkiezer-dialoog
        // (showPicker), wat now/pos/band/tsys niet raakt, dus mag de "nu"-waarde niet
        // herberekend worden.
        compose.onNodeWithText("📅", substring = true).performClick()
        compose.waitForIdle()

        assertEquals(callsAfterFirstRender, currentComputeCallsForTest)
    }

    private val bestDay = SemanticsMatcher("groene cirkel") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("deg-best-") == true
    }

    private fun greenDays(): Set<Int> =
        compose.onAllNodes(bestDay).fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.TestTag].removePrefix("deg-best-").toInt() }.toSet()

    @Test
    fun calendarCirclesTheFourBestDaysOfEachMonth() {
        val settings = SettingsRepository(ApplicationProvider.getApplicationContext())
        settings.degBand = EmeBand.B144
        compose.setContent {
            EmeTheme { Surface(color = MaterialTheme.colorScheme.background) { Box(Modifier.fillMaxSize()) { DegradationScreen(settings, "JO21dd") } } }
        }
        compose.onNodeWithText("📅", substring = true).performClick()
        compose.waitForIdle()
        val pos = Maidenhead.toLatLon("JO21dd")!!
        val thisMonth = YearMonth.now(ZoneOffset.UTC)
        fun expected(m: YearMonth) = Degradation.bestDaysOfMonth(m, pos.latDeg, pos.lonDeg, EmeBand.B144, EmeBand.B144.defaultTsys.toDouble())
            .map { it.dayOfMonth }.toSet()

        assertEquals(expected(thisMonth), greenDays())
        assertEquals(4, greenDays().size)

        // Volgende maand: andere cirkels, weer 4
        compose.onNodeWithContentDescription("Next month").performClick()
        compose.waitForIdle()
        assertEquals(expected(thisMonth.plusMonths(1)), greenDays())

        // Een dag kiezen sluit de kalender en zet die dag in het midden van de grafiek
        val day = greenDays().first()
        compose.onNode(hasTestTag("deg-best-$day")).performClick()
        compose.waitForIdle()
        assertTrue(compose.onAllNodes(bestDay).fetchSemanticsNodes().isEmpty())
        val chosen = thisMonth.plusMonths(1).atDay(day)
        compose.onNodeWithText("📅", substring = true)
            .assertTextContains(DateTimeFormatter.ofPattern("dd-MM-yyyy").format(chosen), substring = true)
    }

    @Test
    fun noCirclesWithoutLocator() {
        val settings = SettingsRepository(ApplicationProvider.getApplicationContext())
        compose.setContent {
            EmeTheme { Surface(color = MaterialTheme.colorScheme.background) { Box(Modifier.fillMaxSize()) { DegradationScreen(settings, "") } } }
        }
        compose.onNodeWithText("📅", substring = true).performClick()
        compose.waitForIdle()
        assertTrue(greenDays().isEmpty())
        compose.onNodeWithTag("deg-calendar-title").assertExists()
    }
}
