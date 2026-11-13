package be.on5gs.eme.chat

import be.on5gs.eme.R
import be.on5gs.eme.settings.CredentialStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class ChatStoreTest {

    private val realFetchMessages = ChatStore.fetchMessages
    private val creds = CredentialStore.Credentials("ON0TEST", "secret")

    @Before
    fun setUp() {
        ChatStore.logInAgain()      // begintoestand: niets opgehaald
    }

    @After
    fun tearDown() {
        ChatStore.fetchMessages = realFetchMessages
        ChatStore.logInAgain()
    }

    /** Tabblad verlaten tijdens het ophalen is geen fout: geen melding, en de volgende keer meteen opnieuw. */
    @Test
    fun cancelledFetchIsNotStoredAsError() = runBlocking {
        val started = CompletableDeferred<Unit>()
        ChatStore.fetchMessages = { _, _ ->
            started.complete(Unit)
            awaitCancellation()
        }
        val job = launch(Dispatchers.Default) { ChatStore.refreshIfOlderThan(0, Hb9qBand.B1296, creds) }
        started.await()
        job.cancelAndJoin()

        val s = ChatStore.state.value
        assertNull(s.error)
        assertEquals(0L, s.lastFetch)
        assertFalse(s.loading)
        assertEquals(Hb9qBand.B1296, s.band)
    }

    /** Een echte fout blijft wel een fout, en telt als ophaalpoging. */
    @Test
    fun failedFetchIsStoredAsError() = runBlocking {
        ChatStore.fetchMessages = { _, _ -> throw IOException("logger_lst: HTTP 500") }
        ChatStore.refreshIfOlderThan(0, Hb9qBand.B1296, creds)

        val s = ChatStore.state.value
        assertEquals(R.string.hb9q_error to "logger_lst: HTTP 500", s.error)
        assertTrue(s.lastFetch > 0L)
        assertFalse(s.loading)
    }

    private fun msg(id: Long, time: String, call: String, name: String) =
        ChatMessage(id, time, "", call, name, null, "test", isCq = false, senderId = id.toString())

    /** De foutmelding hoort bij de band die net opgehaald werd, niet bij de band die nu gekozen is. */
    @Test
    fun errorOnlyShownForTheBandItBelongsTo() {
        val state = ChatStore.State(band = Hb9qBand.B432, error = 1 to "boom")
        assertNull(ChatStore.errorFor(state, Hb9qBand.B1296))
        assertEquals(1 to "boom", ChatStore.errorFor(state, Hb9qBand.B432))
    }

    /** Deelnemers: wie recent schreef, met de tijd van het laatste bericht, nieuwste eerst, zonder jezelf. */
    @Test
    fun recentParticipantsWithTime() {
        val messages = listOf(          // zoals de chat: nieuwste bovenaan
            msg(5, "09:03", "UA3PTW", "Dmitrij"),
            msg(4, "09:01", "F5AOL", "Pascal"),
            msg(3, "08:50", "ON5GS", "Dirk"),
            msg(2, "08:37", "OE3GWC", "Gerd"),
            msg(1, "08:30", "F5AOL", "Pascal"),
        )
        val p = ChatStore.recentParticipants(ChatStore.State(messages = messages), "ON5GS")
        assertEquals(
            listOf("09:03 UA3PTW Dmitrij", "09:01 F5AOL Pascal", "08:37 OE3GWC Gerd"),
            p.map { "${it.time} ${it.callsign} ${it.name}" },
        )
    }
}
