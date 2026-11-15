package be.on5gs.eme.horizon

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import be.on5gs.eme.settings.CredentialStore
import be.on5gs.eme.settings.SettingsRepository
import be.on5gs.eme.settings.SettingsScreen
import be.on5gs.eme.ui.theme.EmeTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HorizonScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        settings = SettingsRepository(ApplicationProvider.getApplicationContext())
        settings.horizon = Horizon()
        compose.setContent {
            EmeTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    SettingsScreen("ON5GS", "JO21SC", CredentialStore(compose.activity), settings) { _, _ -> }
                }
            }
        }
    }

    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label))

    /** De situatie van ON5GS ingeven: ondergrens 5°, de boom van 60 tot 110° tot 25°, de boompjes gedeeltelijk. */
    @Test
    fun enterAndSaveTheHorizonOfOn5gs() {
        compose.onNodeWithTag("horizon-summary").assertTextEquals("Minimum 0° · obstacles: 0")
        compose.onNodeWithText("Edit").performScrollTo().performClick()
        compose.onNodeWithTag("horizon-chart").assertExists()

        field("Minimum elevation (°)").performTextReplacement("5")
        compose.onNodeWithText("Add obstacle").performScrollTo().performClick()
        compose.onNodeWithText("Save").performScrollTo().assertIsNotEnabled()       // lege hindernis: nog niet geldig
        field("From az (°)").performTextReplacement("60")
        field("To az (°)").performTextReplacement("110")
        field("Up to el (°)").performTextReplacement("25")
        compose.onNodeWithText("Save").performScrollTo().assertIsEnabled()

        compose.onNodeWithText("Add obstacle").performScrollTo().performClick()
        compose.onAllNodesWithText("From az (°)")[1].performTextReplacement("180")
        compose.onAllNodesWithText("To az (°)")[1].performTextReplacement("200")
        compose.onAllNodesWithText("Up to el (°)")[1].performTextReplacement("20,0")  // komma mag
        compose.onAllNodesWithText("Partial")[1].performScrollTo().performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()

        assertEquals("1;5;B 60 110 25;P 180 200 20", settings.horizon.encode())
        compose.onNodeWithTag("horizon-summary").assertTextEquals("Minimum 5° · obstacles: 2")
        compose.onNodeWithText("Horizon saved").assertExists()
    }

    @Test
    fun wrongNumbersBlockSavingAndDeleteRemovesAnObstacle() {
        compose.onNodeWithText("Edit").performScrollTo().performClick()
        field("Minimum elevation (°)").performTextReplacement("95")
        compose.onNodeWithText("0 – 90").assertExists()
        compose.onNodeWithText("Save").performScrollTo().assertIsNotEnabled()
        field("Minimum elevation (°)").performTextReplacement("3")

        compose.onNodeWithText("Add obstacle").performScrollTo().performClick()
        compose.onNodeWithText("Save").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Delete obstacle").performClick()
        compose.onNodeWithText("Save").performScrollTo().assertIsEnabled().performClick()
        assertEquals("1;3", settings.horizon.encode())
    }
}
