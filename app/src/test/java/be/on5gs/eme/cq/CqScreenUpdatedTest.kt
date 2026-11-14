package be.on5gs.eme.cq

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import be.on5gs.eme.AppClock
import be.on5gs.eme.settings.SettingsRepository
import be.on5gs.eme.ui.theme.EmeTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** "bijgewerkt" in de samenvattingsregel is de laatste geslaagde ophaling, niet de laatste poging (#13, bevinding 5). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en-w411dp-h891dp")
class CqScreenUpdatedTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val clock = AppClock.nowMillis
    private val fetchW3sz = LiveCqStore.fetchW3sz
    private val fetchN6nu = LiveCqStore.fetchN6nu
    private val now = 1_800_000_000_000L          // 2027-01-15 08:00 UTC

    @Before
    fun setUp() {
        AppClock.nowMillis = { now }
        LiveCqStore.fetchW3sz = { error("no network in CqScreenUpdatedTest") }
        LiveCqStore.fetchN6nu = { error("no network in CqScreenUpdatedTest") }
    }

    @After
    fun tearDown() {
        AppClock.nowMillis = clock
        LiveCqStore.fetchW3sz = fetchW3sz
        LiveCqStore.fetchN6nu = fetchN6nu
        LiveCqStore.resetForTest()
    }

    @Test
    fun showsTheLastSuccessfulFetchNotTheLastAttempt() {
        // Laatste poging nu (mislukt), laatste geslaagde ophaling 10 minuten geleden
        LiveCqStore.seedForTest(emptyList(), lastFetch = now, lastSuccess = now - 10 * 60_000)
        compose.setContent {
            EmeTheme { CqScreen(SettingsRepository(ApplicationProvider.getApplicationContext()), "JO21SC") }
        }
        compose.onAllNodesWithText("updated 07:50 UTC", substring = true).assertCountEquals(1)
        compose.onAllNodesWithText("updated 08:00 UTC", substring = true).assertCountEquals(0)
    }
}
