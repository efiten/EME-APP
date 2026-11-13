package be.on5gs.eme.n0uk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.io.IOException

/** N0UK wordt hooguit om de 90 s bevraagd, ook na een afgebroken of mislukte poging. */
class N0ukStoreTest {

    private val realFetch = N0ukStore.fetchPage
    private val page = javaClass.classLoader!!.getResource("n0uk_jt65emeA.html")!!.readText()
    private var calls = 0

    @Before
    fun setUp() {
        N0ukStore.resetForTest()
        calls = 0
        N0ukStore.fetchPage = { calls++; page }
    }

    @After
    fun tearDown() {
        N0ukStore.fetchPage = realFetch
        N0ukStore.resetForTest()
    }

    @Test
    fun notMoreOftenThanEvery90Seconds() = runBlocking {
        val t0 = 1_800_000_000_000L
        N0ukStore.refreshIfDue(t0)
        N0ukStore.refreshIfDue(t0 + 30_000)
        N0ukStore.refreshIfDue(t0 + 89_999)
        assertEquals(1, calls)
        N0ukStore.refreshIfDue(t0 + 90_000)
        assertEquals(2, calls)
        assertEquals(50, N0ukStore.state.value.messages.size)
    }

    @Test
    fun cancelledFetchStillCountsForThe90Seconds() = runBlocking {
        val started = CompletableDeferred<Unit>()
        N0ukStore.fetchPage = { calls++; started.complete(Unit); awaitCancellation() }
        val t0 = 1_800_000_000_000L
        val job = launch(Dispatchers.Default) { N0ukStore.refreshIfDue(t0) }
        started.await()
        job.cancelAndJoin()
        // Meteen terug naar het tabblad: geen nieuwe aanvraag
        N0ukStore.fetchPage = { calls++; page }
        N0ukStore.refreshIfDue(t0 + 5_000)
        assertEquals(1, calls)
        assertFalse(N0ukStore.state.value.loading)
        assertEquals(null, N0ukStore.state.value.error)
    }

    @Test
    fun failureIsShownAndAlsoWaits90Seconds() = runBlocking {
        N0ukStore.fetchPage = { calls++; throw IOException("N0UK HTTP 500") }
        val t0 = 1_800_000_000_000L
        N0ukStore.refreshIfDue(t0)
        N0ukStore.refreshIfDue(t0 + 10_000)
        assertEquals(1, calls)
        assertEquals("N0UK HTTP 500", N0ukStore.state.value.error)
    }
}
