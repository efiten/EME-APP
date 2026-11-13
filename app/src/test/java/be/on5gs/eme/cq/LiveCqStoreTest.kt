package be.on5gs.eme.cq

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

class LiveCqStoreTest {

    private val realFetcher = LiveCqStore.fetcher

    @Before
    fun setUp() {
        LiveCqStore.seedForTest(emptyList(), lastFetch = 0L)
    }

    @After
    fun tearDown() {
        LiveCqStore.fetcher = realFetcher
        LiveCqStore.seedForTest(emptyList(), lastFetch = 0L)
    }

    /** Scherm verlaten tijdens het ophalen is geen fout: geen melding, en de volgende keer meteen opnieuw. */
    @Test
    fun cancelledFetchIsNotStoredAsError() = runBlocking {
        val started = CompletableDeferred<Unit>()
        LiveCqStore.fetcher = {
            started.complete(Unit)
            awaitCancellation()
        }
        val job = launch(Dispatchers.Default) { LiveCqStore.refreshIfOlderThan(0) }
        started.await()
        job.cancelAndJoin()

        val s = LiveCqStore.state.value
        assertNull(s.error)
        assertEquals(0L, s.lastFetch)
        assertFalse(s.loading)
    }

    /** Een echte fout blijft wel een fout, en telt als ophaalpoging. */
    @Test
    fun failedFetchIsStoredAsError() = runBlocking {
        LiveCqStore.fetcher = { throw IOException("W3SZ HTTP 500") }
        LiveCqStore.refreshIfOlderThan(0)

        val s = LiveCqStore.state.value
        assertEquals("W3SZ HTTP 500", s.error)
        assertTrue(s.lastFetch > 0L)
        assertFalse(s.loading)
    }
}
