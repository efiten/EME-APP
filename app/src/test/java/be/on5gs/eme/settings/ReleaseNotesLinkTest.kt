package be.on5gs.eme.settings

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import be.on5gs.eme.BuildConfig
import be.on5gs.eme.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ReleaseNotesLinkTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** "Release notes" in de instellingen opent de GitHub-release van deze versie. */
    @Test
    fun releaseNotesOpensTheReleaseOfThisVersion() {
        val opened = mutableListOf<String>()
        val handler = object : UriHandler {
            override fun openUri(uri: String) { opened += uri }
        }
        val ctx = compose.activity
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides handler) {
                SettingsScreen("ON5GS", "JO21SC", CredentialStore(ctx)) { _, _ -> }
            }
        }

        compose.onNodeWithText(ctx.getString(R.string.release_notes), substring = true).performScrollTo().performClick()

        assertEquals(
            listOf("https://github.com/on5gsdirk-cmd/EME-APP/releases/tag/v${BuildConfig.VERSION_NAME}"),
            opened,
        )
    }
}
