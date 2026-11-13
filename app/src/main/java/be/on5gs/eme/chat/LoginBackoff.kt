package be.on5gs.eme.chat

/**
 * Exponentiële back-off na herhaalde mislukte logins bij HB9Q, zodat [Hb9qClient] niet
 * bij elke pollingronde (om de 20s, zie [ChatStore.MESSAGES_REFRESH_MS]) een nieuwe login
 * blijft proberen als dat toch telkens mislukt (foute/ontbrekende login, of HB9Q plat).
 */
internal class LoginBackoff(private val nowMillis: () -> Long = System::currentTimeMillis) {

    private var consecutiveFailures = 0
    private var cooldownUntilMillis = 0L

    /** Hoeveel ms er nog te wachten is, of `null` als er nu geprobeerd mag worden. */
    fun retryAfterMs(): Long? {
        val remaining = cooldownUntilMillis - nowMillis()
        return if (remaining > 0) remaining else null
    }

    fun onSuccess() {
        consecutiveFailures = 0
        cooldownUntilMillis = 0L
    }

    fun onFailure() {
        consecutiveFailures++
        val shift = (consecutiveFailures - 1).coerceAtMost(MAX_SHIFT)
        val delayMs = (BASE_DELAY_MS shl shift).coerceAtMost(MAX_DELAY_MS)
        cooldownUntilMillis = nowMillis() + delayMs
    }

    companion object {
        /** Eerste wachttijd na 1 mislukte login. */
        private const val BASE_DELAY_MS = 5_000L
        /** Nooit langer dan 5 minuten wachten. */
        private const val MAX_DELAY_MS = 300_000L
        private const val MAX_SHIFT = 6
    }
}
