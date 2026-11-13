package be.on5gs.eme.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginBackoffTest {

    private class FakeClock(var now: Long = 0L) : () -> Long {
        override fun invoke(): Long = now
    }

    @Test
    fun noCooldownBeforeAnyFailure() {
        val backoff = LoginBackoff(FakeClock(1_000L))
        assertNull(backoff.retryAfterMs())
    }

    @Test
    fun blocksRetriesForAWhileAfterAFailure() {
        val clock = FakeClock(1_000L)
        val backoff = LoginBackoff(clock)
        backoff.onFailure()
        val wait = backoff.retryAfterMs()
        assertTrue("expected a positive cooldown, got $wait", (wait ?: 0L) > 0L)
    }

    @Test
    fun cooldownExpiresAndBacksOffFurtherOnRepeatedFailures() {
        val clock = FakeClock(0L)
        val backoff = LoginBackoff(clock)
        backoff.onFailure()
        val firstWait = backoff.retryAfterMs()!!
        // Net voor het einde van de wachttijd: nog altijd geblokkeerd
        clock.now = firstWait - 1
        assertTrue((backoff.retryAfterMs() ?: 0L) > 0L)
        // Na de wachttijd: weer toegelaten
        clock.now = firstWait + 1
        assertNull(backoff.retryAfterMs())

        // Een 2e mislukking wacht minstens even lang (exponentieel = langer)
        backoff.onFailure()
        val secondWait = backoff.retryAfterMs()!!
        assertTrue("2nd wait ($secondWait) should be >= 1st wait ($firstWait)", secondWait >= firstWait)
    }

    @Test
    fun successReenablesImmediateRetries() {
        val clock = FakeClock(0L)
        val backoff = LoginBackoff(clock)
        backoff.onFailure()
        assertTrue((backoff.retryAfterMs() ?: 0L) > 0L)
        backoff.onSuccess()
        assertNull(backoff.retryAfterMs())
    }

    @Test
    fun neverWaitsMoreThanFiveMinutes() {
        val clock = FakeClock(0L)
        val backoff = LoginBackoff(clock)
        repeat(20) { backoff.onFailure() }
        val wait = backoff.retryAfterMs()!!
        assertEquals(300_000L, wait)
    }
}
