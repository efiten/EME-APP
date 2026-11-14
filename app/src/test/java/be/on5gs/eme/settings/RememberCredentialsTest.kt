package be.on5gs.eme.settings

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import be.on5gs.eme.R
import be.on5gs.eme.chat.ChatScreen
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RememberCredentialsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Zolang load() niet klaar is, is de login "nog aan het laden" en niet "geen login". */
    @Test
    fun loadingIsNotTheSameAsNoLogin() {
        val gate = CompletableDeferred<CredentialStore.Credentials?>()
        compose.setContent {
            val c = rememberCredentials(Unit) { gate.await() }
            Text(if (!c.loaded) "loading" else c.credentials?.login ?: "no login")
        }
        compose.onNodeWithText("loading").assertExists()
        compose.onNodeWithText("no login").assertDoesNotExist()

        gate.complete(null)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("no login")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("loading").assertDoesNotExist()
    }

    /** Zonder bewaarde login toont de chat de melding "need login", na het laden van de achtergronddraad. */
    @Test
    fun chatShowsNeedLoginAfterLoadingWhenNothingIsSaved() {
        val ctx = compose.activity
        compose.setContent { ChatScreen(SettingsRepository(ctx), CredentialStore(ctx), myCallsign = "") }
        val needLogin = ctx.getString(R.string.need_login)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(needLogin)).fetchSemanticsNodes().isNotEmpty() }
    }
}
