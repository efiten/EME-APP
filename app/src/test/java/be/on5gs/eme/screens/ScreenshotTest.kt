package be.on5gs.eme.screens

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import be.on5gs.eme.EmeApp
import be.on5gs.eme.moon.MoonPhase
import be.on5gs.eme.moon.drawMoonPhase
import be.on5gs.eme.chat.ChatCqList
import be.on5gs.eme.chat.ChatCqParser
import be.on5gs.eme.chat.ChatParser
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import be.on5gs.eme.cq.Band
import be.on5gs.eme.cq.LiveCqParser
import be.on5gs.eme.cq.LiveCqSpot
import be.on5gs.eme.settings.SettingsRepository
import be.on5gs.eme.cq.LiveCqStore
import be.on5gs.eme.ui.theme.EmeTheme
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Maakt schermafdrukken van de app op de pc (geen telefoon nodig).
 * Draait alleen met: ./gradlew testDebugUnitTest -Pscreenshots
 * De afbeeldingen komen in app/build/screenshots/.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        assumeTrue(System.getProperty("screenshots") == "true")
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/Brussels"))
        // CQ-voorbeelden uit de echte W3SZ-export, verschoven naar "nu" zodat ze op de kaart komen
        val csv = javaClass.classLoader!!.getResource("livecq_sample.csv")!!.readText()
        val spots = LiveCqParser.parse(csv)
        val shift = System.currentTimeMillis() - spots.maxOf { it.utcMillis } - 5 * 60_000
        val now = System.currentTimeMillis() - 5 * 60_000
        // Testgeval van ON5GS: OK1AA (JO61AA) en OK2BB (JO71AA) vlak bij elkaar, plus extra drukte
        val extra = listOf(
            Triple("OK1AA", "JO61AA", 1296.065), Triple("OK2BB", "JO71AA", 1296.070),
            Triple("OK1DFC", "JO70FD", 432.065), Triple("DL7APV", "JO62QM", 144.120),
            Triple("SP5GDM", "KO02MD", 1296.060), Triple("OK1AA", "JO61AA", 144.115),
            Triple("UA3PTW", "KO93", 1296.050), Triple("VK7MO", "QE37", 1296.050),
        ).map { (call, grid, f) ->
            LiveCqSpot(now, call, grid, f, Band.forFrequency(f), "Q65-60C", "-20", "PA3DRK", "JO21")
        }
        LiveCqStore.seedForTest(spots.map { it.copy(utcMillis = it.utcMillis + shift) } + extra)
        SettingsRepository(ApplicationProvider.getApplicationContext()).cqBands = setOf(Band.B144, Band.B432, Band.B23CM)
    }

    @Test
    fun moonScreen() {
        compose.setContent { EmeTheme { Surface(color = MaterialTheme.colorScheme.background) { Box(Modifier.fillMaxSize()) { EmeApp() } } } }
        compose.waitForIdle()
        save("maan")
    }

    @Test
    fun cqScreen() {
        compose.setContent { EmeTheme { Surface(color = MaterialTheme.colorScheme.background) { Box(Modifier.fillMaxSize()) { EmeApp() } } } }
        compose.onNodeWithText("Live CQ").performClick()
        compose.waitForIdle()
        save("livecq")
    }

    @Test
    @Config(qualifiers = "+ru")
    fun moonScreenRussian() {
        compose.setContent { EmeTheme { Surface(color = MaterialTheme.colorScheme.background) { Box(Modifier.fillMaxSize()) { EmeApp() } } } }
        compose.waitForIdle()
        save("maan-ru")
    }

    @Test
    @Config(qualifiers = "+fr")
    fun settingsFrench() {
        compose.setContent { EmeTheme { Surface(color = MaterialTheme.colorScheme.background) { Box(Modifier.fillMaxSize()) { EmeApp() } } } }
        compose.onNodeWithContentDescription("Paramètres").performClick()
        compose.waitForIdle()
        save("instellingen-fr")
    }

    @Test
    @Config(qualifiers = "+ja")
    fun chatJapanese() {
        compose.setContent { EmeTheme { Surface(color = MaterialTheme.colorScheme.background) { Box(Modifier.fillMaxSize()) { EmeApp() } } } }
        compose.onNodeWithText("HB9Q チャット").performClick()
        compose.waitForIdle()
        save("chat-ja")
    }

    @Test
    @Config(fontScale = 1.3f, qualifiers = "+nl")
    fun chatCqListLargeFont() {
        val rows = javaClass.classLoader!!.getResource("hb9q_rows_8nov.html")!!.readText()
        val now = java.time.Instant.parse("2026-11-08T09:10:00Z").toEpochMilli()
        val cqs = ChatCqParser.recent(ChatParser.parseRows(rows), now, 24 * 60)
        compose.setContent {
            EmeTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize().padding(12.dp)) { ChatCqList("1296", cqs) }
                }
            }
        }
        compose.waitForIdle()
        save("chatcq-groot")
    }

    @Test
    @Config(fontScale = 1.3f, qualifiers = "+nl")
    fun tabsLargeFont() {
        compose.setContent { EmeTheme { Surface(color = MaterialTheme.colorScheme.background) { Box(Modifier.fillMaxSize()) { EmeApp() } } } }
        compose.waitForIdle()
        save("maan-groot")
    }

    /** Acht fasen van november 2026 naast elkaar (wassend rechts verlicht, afnemend links). */
    @Test
    fun moonPhases() {
        val dates = listOf("2026-11-09", "2026-11-12", "2026-11-17", "2026-11-20", "2026-11-24", "2026-11-28", "2026-12-01", "2026-12-05")
        val phases = dates.map { MoonPhase.at(java.time.Instant.parse("${it}T12:00:00Z").toEpochMilli()) }
        compose.setContent {
            EmeTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                        phases.forEachIndexed { i, p ->
                            drawMoonPhase(androidx.compose.ui.geometry.Offset(70f + i * 145f, 150f), 60f, p)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        save("maanfasen")
    }

    @Test
    @Config(qualifiers = "+nl")
    fun degradationScreen() {
        SettingsRepository(ApplicationProvider.getApplicationContext()).degBand = be.on5gs.eme.degradation.EmeBand.B144
        compose.setContent { EmeTheme { Surface(color = MaterialTheme.colorScheme.background) { Box(Modifier.fillMaxSize()) { EmeApp() } } } }
        compose.onNodeWithText("Deg").performClick()
        compose.waitForIdle()
        save("degradatie")
    }

    @Test
    @Config(qualifiers = "+it")
    fun degradationItalian() {
        compose.setContent { EmeTheme { Surface(color = MaterialTheme.colorScheme.background) { Box(Modifier.fillMaxSize()) { EmeApp() } } } }
        compose.onNodeWithText("Deg").performClick()
        compose.waitForIdle()
        save("degradatie-it")
    }

    @Test
    fun chatCqList() {
        val rows = javaClass.classLoader!!.getResource("hb9q_rows_8nov.html")!!.readText()
        val now = java.time.Instant.parse("2026-11-08T09:10:00Z").toEpochMilli()
        // Venster ruimer dan 30 min zodat alle voorbeelden van die dag zichtbaar zijn
        val cqs = ChatCqParser.recent(ChatParser.parseRows(rows), now, 24 * 60)
        compose.setContent {
            EmeTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize().padding(12.dp)) { ChatCqList("1296", cqs) }
                }
            }
        }
        compose.waitForIdle()
        save("chatcq")
    }

    private fun save(name: String) {
        compose.mainClock.advanceTimeBy(500)
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
