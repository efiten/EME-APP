package be.on5gs.eme.horizon

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import be.on5gs.eme.AppClock
import be.on5gs.eme.moon.MoonScreen
import be.on5gs.eme.settings.SettingsRepository
import be.on5gs.eme.ui.theme.EmeTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.TimeZone

/** De eigen horizon in de Maan-tab (stap 3 van #87). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HorizonUseTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Brussels"))
        AppClock.nowMillis = { Instant.parse("2026-11-20T12:00:00Z").toEpochMilli() }
        settings = SettingsRepository(ApplicationProvider.getApplicationContext())
        settings.cqBands = emptySet()
    }

    @After
    fun tearDown() {
        AppClock.nowMillis = System::currentTimeMillis
        settings.horizon = Horizon()
        settings.dishDiameterM = 0.0
    }

    private fun show() {
        compose.setContent {
            EmeTheme { Surface(color = MaterialTheme.colorScheme.background) { MoonScreen(settings, "ON5GS", "JO21SC") } }
        }
        compose.waitForIdle()
    }

    /**
     * 20 november 2026, 12:00 UTC: de maan staat nog onder. Met de horizon van ON5GS is ze in het
     * midden van JO21SC (51,10° N 5,54° O) vrij van 15:50 tot 02:15 UTC, in Brussel 16:50 tot morgen
     * 03:15. (Voor 51,1° N 4,3° E is dat 15:55 tot 02:20, zie expected/horizon_next.csv.)
     */
    @Test
    fun withOwnHorizonTheMoonTabShowsTheStateAndTheWindow() {
        settings.horizon = Horizon.decode("1;5;B 60 110 25;P 180 200 20")!!
        show()
        compose.onNodeWithTag("horizon-now").performScrollTo().assertTextEquals("Moon behind your obstacles")
        compose.waitUntil(5_000) { compose.onAllNodesWithTagCount("horizon-window") > 0 }
        compose.onNodeWithTag("horizon-window").performScrollTo()
            .assertTextContains("Clear of your obstacles")
            .assertTextContains("16:50", substring = true)
            .assertTextContains("tomorrow 03:15", substring = true)
    }

    /** Met een schotel van 6 m op 1296 MHz (marge 1,35°) eindigt het venster 9 minuten vroeger: 02:06 UTC, 03:06 in Brussel. */
    @Test
    fun theDishMarginShortensTheWindow() {
        settings.horizon = Horizon.decode("1;5;B 60 110 25;P 180 200 20")!!
        settings.dishDiameterM = 6.0
        show()
        compose.waitUntil(5_000) { compose.onAllNodesWithTagCount("horizon-window") > 0 }
        compose.onNodeWithTag("horizon-window").performScrollTo()
            .assertTextContains("16:50", substring = true)
            .assertTextContains("tomorrow 03:06", substring = true)
    }

    /** Zonder eigen horizon blijft de Maan-tab zoals hij was. */
    @Test
    fun withoutOwnHorizonNothingChanges() {
        show()
        compose.onNodeWithTag("horizon-now").assertDoesNotExist()
        compose.onNodeWithTag("horizon-window").assertDoesNotExist()
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTagCount(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size
}
