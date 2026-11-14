package be.on5gs.eme.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState

/**
 * De HB9Q-login, geladen buiten de UI-draad. [loaded] blijft false tot het laden klaar is,
 * zodat een scherm "nog aan het laden" kan onderscheiden van "geen login ingesteld".
 */
internal data class LoadedCredentials(val loaded: Boolean, val credentials: CredentialStore.Credentials?)

@Composable
internal fun rememberCredentials(key: Any, load: suspend () -> CredentialStore.Credentials?): LoadedCredentials {
    val state by produceState(LoadedCredentials(loaded = false, credentials = null), key) {
        value = LoadedCredentials(loaded = true, credentials = load())
    }
    return state
}

@Composable
internal fun rememberCredentials(store: CredentialStore): LoadedCredentials =
    rememberCredentials(store) { store.loadAsync() }
