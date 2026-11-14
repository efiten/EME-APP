package be.on5gs.eme.cq

import be.on5gs.eme.AppClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Gedeelde Live CQ-gegevens voor de hele app (CQ-lijst én wereldkaart),
 * zodat W3SZ en N6NU maar één keer per interval bevraagd worden, welk scherm ook open staat.
 * Beide bronnen worden samen opgehaald, met dezelfde regels voor allebei.
 */
object LiveCqStore {

    /** Hoe vaak de lijst automatisch opgehaald wordt (zelfde als MoonTrack). */
    const val AUTO_REFRESH_MS = 120_000L
    /** Minimale tijd tussen twee ophaalacties, ook met de vernieuwknop of na een afgebroken poging. */
    const val MIN_FETCH_INTERVAL_MS = 30_000L

    data class State(
        val spots: List<LiveCqSpot> = emptyList(),
        val lastFetch: Long = 0L,
        /** Laatste ophaling waarbij minstens één bron lukte (voor "bijgewerkt"). */
        val lastSuccess: Long = 0L,
        /** Start van de laatste aanvraag, ook een afgebroken: die bereikte de servers toch. */
        val lastAttempt: Long = 0L,
        val error: String? = null,
        val loading: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val lock = Mutex()

    // Laatste goede lijst per bron: faalt één bron, dan blijft zijn vorige lijst staan.
    private var w3szSpots: List<LiveCqSpot> = emptyList()
    private var n6nuSpots: List<LiveCqSpot> = emptyList()

    /** Testnaden: in de app de echte aanvragen. */
    internal var fetchW3sz: suspend () -> List<LiveCqSpot> = { LiveCqRepository.fetchW3sz() }
    internal var fetchN6nu: suspend () -> List<LiveCqSpot> = { LiveCqRepository.fetchN6nu() }

    /** Alleen voor tests (schermafdrukken): vaste gegevens instellen zonder netwerk. */
    internal fun seedForTest(spots: List<LiveCqSpot>, lastFetch: Long = AppClock.now()) {
        w3szSpots = emptyList(); n6nuSpots = emptyList()
        _state.value = State(spots = spots, lastFetch = lastFetch, lastSuccess = lastFetch)
    }

    /** Alleen voor tests: alles leeg, zoals bij het starten van de app. */
    internal fun resetForTest() {
        w3szSpots = emptyList(); n6nuSpots = emptyList()
        _state.value = State()
    }

    /**
     * Haalt nieuwe gegevens op als de vorige ophaling ouder is dan [maxAgeMs], en nooit
     * binnen [MIN_FETCH_INTERVAL_MS] na het begin van de vorige poging.
     * Twee schermen die tegelijk vragen, leiden tot maar één aanvraag per bron.
     */
    suspend fun refreshIfOlderThan(maxAgeMs: Long, now: () -> Long = AppClock::now) = lock.withLock {
        val s = _state.value
        val t = now()
        if (t - s.lastFetch < maxOf(maxAgeMs, MIN_FETCH_INTERVAL_MS)) return@withLock
        if (t - s.lastAttempt < MIN_FETCH_INTERVAL_MS) return@withLock
        _state.value = s.copy(loading = true, lastAttempt = t)
        try {
            val (w3sz, n6nu) = coroutineScope {
                val a = async { attempt(fetchW3sz) }
                val b = async { attempt(fetchN6nu) }
                a.await() to b.await()
            }
            w3sz.onSuccess { w3szSpots = it }
            n6nu.onSuccess { n6nuSpots = it }
            // Technisch detail per bron; het scherm zet er een vertaalde zin rond
            val errors = listOfNotNull(w3sz.exceptionOrNull(), n6nu.exceptionOrNull())
                .map { it.message ?: it.javaClass.simpleName }
            val done = now()
            _state.value = State(
                spots = LiveCqParser.merge(w3szSpots, n6nuSpots),
                lastFetch = done,
                lastSuccess = if (w3sz.isSuccess || n6nu.isSuccess) done else s.lastSuccess,
                lastAttempt = t,
                error = errors.joinToString("; ").ifEmpty { null },
            )
        } catch (e: CancellationException) {
            // Afgebroken (bv. ander tabblad): geen fout tonen, vorige lijst blijft staan
            _state.value = _state.value.copy(loading = false)
            throw e
        }
    }

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
}
