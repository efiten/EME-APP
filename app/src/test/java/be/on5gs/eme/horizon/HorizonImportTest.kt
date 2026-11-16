package be.on5gs.eme.horizon

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import be.on5gs.eme.ExpectedCsv
import be.on5gs.eme.ui.theme.EmeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** "Horizon opmeten" en "Horizon inladen" in "Mijn horizon" (stap 4 van #87). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HorizonImportTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var opened: String? = null
    private var saved: Horizon? = null

    /** Toont het scherm; de bestandskiezer geeft [fileName] uit horizon_survey/ terug, zoals een gebruiker die het kiest. */
    private fun show(fileName: String, text: String = javaClass.classLoader!!.getResource("horizon_survey/$fileName")!!.readText()) {
        val file = File(compose.activity.cacheDir, fileName).apply { writeText(text) }
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                dispatchResult(requestCode, Uri.fromFile(file))
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val uris = object : UriHandler { override fun openUri(uri: String) { opened = uri } }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner, LocalUriHandler provides uris) {
                EmeTheme {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        HorizonScreen(Horizon.decode("1;5;P 180 200 20")!!, "JO21SC") { h, _, _ -> saved = h }
                    }
                }
            }
        }
    }

    @Test
    fun measureOpensHorizonSurvey() {
        show("horizon_survey_example.json")
        compose.onNodeWithText("Measure horizon").performClick()
        assertEquals("https://horizon.dl6zg.de/", opened)
    }

    /** Inladen vervangt de hindernissen; de ondergrens blijft; pas Opslaan bewaart. */
    @Test
    fun loadingASurveyFillsTheObstaclesAndSaveKeepsThem() {
        show("horizon_survey_example.json")
        compose.onNodeWithText("Load horizon").performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("From az (°)").fetchSemanticsNodes().size == 7 }
        compose.onNodeWithTag("horizon-import-message")
            .assertTextEquals("Loaded: 12 points, 7 obstacles. Check them, mark thin trees as partial, then save.")
        assertNull(saved)                                                  // nog niets bewaard
        compose.onNodeWithText("Save").performScrollTo().performClick()
        val want = ExpectedCsv.read("horizon_import").first { it["file"] == "horizon_survey_example.json" }.getValue("horizon")
        assertEquals(want.replaceFirst("1;0", "1;5"), saved!!.encode())   // ondergrens 5 blijft staan
    }

    /** Niet-gemeten stukken (meer dan 60° tussen twee punten) blijven open, en de melding zegt dat (review efiten). */
    @Test
    fun unmeasuredGapsAreNamedInTheMessage() {
        show("only_the_tree.json")
        compose.onNodeWithText("Load horizon").performClick()
        // Wachten op de melding: 1 rij staat er al voor het inladen (#110)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Loaded:", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, compose.onAllNodesWithText("From az (°)").fetchSemanticsNodes().size)
        compose.onNodeWithTag("horizon-import-message").assertTextEquals(
            "Loaded: 3 points, 1 obstacles. Check them, mark thin trees as partial, then save. Not measured, left open: 110–60°.",
        )
    }

    /** Groter dan 1 MB: geen opmeting, ook als het begin er een is (zoals de webversie; review efiten). */
    @Test
    fun aFileOverOneMegabyteIsRefused() {
        val big = buildString {
            append("azimuth_deg,elevation_deg,timestamp,is_calibration,source\n")
            var az = 0
            while (length <= 1_000_000) { append("${az % 360}.00,5.00,2026-11-15T10:00:00.000Z,0,sensor\n"); az += 10 }
        }
        show("big.csv", big)
        compose.onNodeWithText("Load horizon").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("This file is not", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, compose.onAllNodesWithText("From az (°)").fetchSemanticsNodes().size)
    }

    @Test
    fun aWrongFileShowsAnErrorAndKeepsTheObstacles() {
        show("not_a_survey.txt")
        compose.onNodeWithText("Load horizon").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("This file is not", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("horizon-import-message")
            .assertTextEquals("This file is not a horizon survey from horizon.dl6zg.de (JSON or CSV).")
        assertEquals(1, compose.onAllNodesWithText("From az (°)").fetchSemanticsNodes().size)   // de boompjes staan er nog
    }
}
