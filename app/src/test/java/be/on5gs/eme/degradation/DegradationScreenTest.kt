package be.on5gs.eme.degradation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import be.on5gs.eme.settings.SettingsRepository
import be.on5gs.eme.ui.theme.EmeTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

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
}
