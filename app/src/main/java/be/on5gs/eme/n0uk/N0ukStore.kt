package be.on5gs.eme.n0uk

import be.on5gs.eme.UserAgent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/**
 * De N0UK WSJT EME-chat (144 MHz), ALLEEN LEZEN.
 *
 * Afspraken (zie CONTRIBUTING.md):
 *  - de app plaatst nooit berichten op chris.org;
 *  - hooguit één aanvraag per 90 s (de pagina vraagt zelf om de 90 s te verversen),
 *    ook als je het tabblad verlaat en meteen terugkomt (we tellen vanaf de start van de poging);
 *  - een eerlijke User-Agent.
 */
object N0ukStore {

    const val URL_CHAT = "https://www.chris.org/cgi-bin/jt65emeA"
    /** Minimale tijd tussen twee aanvragen, zoals de "meta refresh" van de pagina zelf. */
    const val REFRESH_MS = 90_000L

    data class State(
        val messages: List<N0ukMessage> = emptyList(),
        val error: String? = null,           // technisch detail; het scherm vertaalt de melding
        val loading: Boolean = false,
        val lastFetch: Long = 0L,            // laatste geslaagde of mislukte ophaling (voor "bijgewerkt")
        val lastAttempt: Long = 0L,          // start van de laatste poging (voor de 90 s)
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val lock = Mutex()

    /** Haalt de pagina op. Alleen voor tests vervangbaar, zodat er geen netwerk nodig is. */
    internal var fetchPage: suspend () -> String = { download() }

    /** Haalt de chat op als de vorige poging minstens 90 s geleden is. Anders gebeurt er niets. */
    suspend fun refreshIfDue(nowMillis: Long = System.currentTimeMillis()) = lock.withLock {
        val s = _state.value
        if (s.lastAttempt != 0L && nowMillis - s.lastAttempt < REFRESH_MS) return@withLock
        // De poging telt vanaf nu, ook als ze afgebroken wordt (geen extra aanvragen bij snel wisselen)
        _state.value = s.copy(loading = true, lastAttempt = nowMillis)
        _state.value = try {
            val html = fetchPage()
            _state.value.copy(messages = N0ukParser.parse(html, nowMillis), error = null, loading = false, lastFetch = nowMillis)
        } catch (e: CancellationException) {
            _state.value = _state.value.copy(loading = false)
            throw e
        } catch (e: Exception) {
            _state.value.copy(error = e.message ?: e.javaClass.simpleName, loading = false, lastFetch = nowMillis)
        }
    }

    /** Alleen voor tests: beginsituatie. */
    internal fun resetForTest() {
        _state.value = State()
    }

    private suspend fun download(): String = withContext(Dispatchers.IO) {
        val conn = (URL(URL_CHAT).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", UserAgent.VALUE)
        }
        try {
            val code = conn.responseCode
            if (code != 200) throw IOException("N0UK HTTP $code")
            val charset = conn.contentType?.substringAfter("charset=", "")?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
            conn.inputStream.bufferedReader(charset).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
