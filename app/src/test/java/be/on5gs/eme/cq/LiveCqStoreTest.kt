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

/** W3SZ en N6NU samen: niet vaker dan vroeger, en één bron die faalt houdt de andere niet tegen. */
class LiveCqStoreTest {

    private val realW3sz = LiveCqStore.fetchW3sz
    private val realN6nu = LiveCqStore.fetchN6nu
    private var w3szCalls = 0
    private var n6nuCalls = 0
    private val t0 = 1_800_000_000_000L

    private fun spot(call: String, src: LiveCqSource) =
        LiveCqSpot(t0, call, "JO21", 1296.065, Band.B23CM, "Q65-60C", "-20", "PA3DRK", "JO21", setOf(src))

    @Before
    fun setUp() {
        LiveCqStore.resetForTest()
        LiveCqStore.fetchW3sz = { w3szCalls++; listOf(spot("OK1DFC", LiveCqSource.W3SZ)) }
        LiveCqStore.fetchN6nu = { n6nuCalls++; listOf(spot("OK1DFC", LiveCqSource.N6NU), spot("K3SK", LiveCqSource.N6NU)) }
    }

    @After
    fun tearDown() {
        LiveCqStore.fetchW3sz = realW3sz
        LiveCqStore.fetchN6nu = realN6nu
        LiveCqStore.resetForTest()
    }

    @Test
    fun bothSourcesMerged() = runBlocking {
        LiveCqStore.refreshIfOlderThan(0) { t0 }
        val s = LiveCqStore.state.value
        assertEquals(listOf("OK1DFC", "K3SK"), s.spots.map { it.callsign })
        assertEquals(setOf(LiveCqSource.W3SZ, LiveCqSource.N6NU), s.spots.first().sources)
        assertNull(s.error)
        assertFalse(s.loading)
    }

    @Test
    fun notMoreOftenThanBefore() = runBlocking {
        LiveCqStore.refreshIfOlderThan(LiveCqStore.AUTO_REFRESH_MS) { t0 }
        LiveCqStore.refreshIfOlderThan(LiveCqStore.AUTO_REFRESH_MS) { t0 + 119_999 }
        LiveCqStore.refreshIfOlderThan(0) { t0 + 29_999 }                 // vernieuwknop: minimum 30 s
        assertEquals(1, w3szCalls); assertEquals(1, n6nuCalls)
        LiveCqStore.refreshIfOlderThan(0) { t0 + 30_000 }                 // vernieuwknop na 30 s: mag
        assertEquals(2, w3szCalls); assertEquals(2, n6nuCalls)
        LiveCqStore.refreshIfOlderThan(LiveCqStore.AUTO_REFRESH_MS) { t0 + 149_999 }   // < 120 s na de vorige
        assertEquals(2, w3szCalls)
        LiveCqStore.refreshIfOlderThan(LiveCqStore.AUTO_REFRESH_MS) { t0 + 150_000 }
        assertEquals(3, w3szCalls); assertEquals(3, n6nuCalls)
    }

    @Test
    fun oneSourceFailingKeepsTheOtherAndItsOldList() = runBlocking {
        LiveCqStore.refreshIfOlderThan(0) { t0 }
        LiveCqStore.fetchN6nu = { n6nuCalls++; throw IOException("N6NU HTTP 503") }
        LiveCqStore.refreshIfOlderThan(0) { t0 + 30_000 }
        val s = LiveCqStore.state.value
        assertEquals("N6NU HTTP 503", s.error)
        assertEquals(listOf("OK1DFC", "K3SK"), s.spots.map { it.callsign })   // vorige N6NU-lijst blijft
        assertEquals(2, w3szCalls)
    }

    // ---- Uit #14 (efiten), aangepast aan twee bronnen ----

    /** Scherm verlaten tijdens het ophalen is geen fout: geen melding, en lastFetch blijft leeg. */
    @Test
    fun cancelledFetchIsNotStoredAsError() = runBlocking {
        val started = CompletableDeferred<Unit>()
        LiveCqStore.fetchW3sz = { started.complete(Unit); awaitCancellation() }
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
        LiveCqStore.fetchW3sz = { throw IOException("W3SZ HTTP 500") }
        LiveCqStore.refreshIfOlderThan(0)

        val s = LiveCqStore.state.value
        assertEquals("W3SZ HTTP 500", s.error)
        assertTrue(s.lastFetch > 0L)
        assertFalse(s.loading)
    }

    // ---- "bijgewerkt" na een mislukte ophaling (#13, bevinding 5) ----

    /** Beide bronnen falen: de lijst is niet nieuw, dus "bijgewerkt" blijft op de vorige tijd. */
    @Test
    fun bothSourcesFailingKeepsTheLastSuccessTime() = runBlocking {
        LiveCqStore.refreshIfOlderThan(0) { t0 }
        LiveCqStore.fetchW3sz = { throw IOException("W3SZ HTTP 500") }
        LiveCqStore.fetchN6nu = { throw IOException("N6NU HTTP 503") }
        LiveCqStore.refreshIfOlderThan(0) { t0 + 30_000 }
        val s = LiveCqStore.state.value
        assertEquals(t0, s.lastSuccess)
        assertEquals(t0 + 30_000, s.lastFetch)   // telt wel als ophaling: niet vaker dan vroeger
    }

    /** Eén bron lukt: er is nieuwe informatie, de fout staat in de foutregel. */
    @Test
    fun oneSourceSucceedingUpdatesTheSuccessTime() = runBlocking {
        LiveCqStore.refreshIfOlderThan(0) { t0 }
        LiveCqStore.fetchN6nu = { throw IOException("N6NU HTTP 503") }
        LiveCqStore.refreshIfOlderThan(0) { t0 + 30_000 }
        assertEquals(t0 + 30_000, LiveCqStore.state.value.lastSuccess)
    }

    /** De allereerste ophaling faalt: nog niets opgehaald. */
    @Test
    fun firstFetchFailingHasNoSuccessTime() = runBlocking {
        LiveCqStore.fetchW3sz = { throw IOException("W3SZ HTTP 500") }
        LiveCqStore.fetchN6nu = { throw IOException("N6NU HTTP 503") }
        LiveCqStore.refreshIfOlderThan(0) { t0 }
        assertEquals(0L, LiveCqStore.state.value.lastSuccess)
    }

    // ---- N6NU ----

    /** Ook #14: een afgebroken aanvraag telt mee voor de 30 s (hier met een vaste klok). */
    @Test
    fun cancelledFetchIsNoErrorAndStillCountsForThe30Seconds() = runBlocking {
        val started = CompletableDeferred<Unit>()
        LiveCqStore.fetchW3sz = { w3szCalls++; started.complete(Unit); awaitCancellation() }
        val job = launch(Dispatchers.Default) { LiveCqStore.refreshIfOlderThan(0) { t0 } }
        started.await()
        job.cancelAndJoin()
        val s = LiveCqStore.state.value
        assertNull(s.error)
        assertFalse(s.loading)
        // Meteen opnieuw: geen nieuwe aanvraag binnen 30 s na de afgebroken poging
        LiveCqStore.fetchW3sz = { w3szCalls++; emptyList() }
        LiveCqStore.refreshIfOlderThan(0) { t0 + 5_000 }
        assertEquals(1, w3szCalls)
        LiveCqStore.refreshIfOlderThan(0) { t0 + 30_000 }
        assertEquals(2, w3szCalls)
        assertTrue(LiveCqStore.state.value.spots.isNotEmpty())
    }
}
