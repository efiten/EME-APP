package be.on5gs.eme.moon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MoonPhaseTest {

    private fun at(iso: String) = MoonPhase.at(Instant.parse(iso).toEpochMilli())

    @Test
    fun illuminatedFractionAndAgeStayInRange() {
        val dates = listOf(
            "2026-11-09T12:00:00Z", "2026-11-17T12:00:00Z",
            "2026-11-24T12:00:00Z", "2026-12-01T12:00:00Z",
        )
        dates.forEach {
            val p = at(it)
            assertTrue("illuminated out of range: ${p.illuminated}", p.illuminated in 0.0..1.0)
            assertTrue("ageDeg out of range: ${p.ageDeg}", p.ageDeg in 0.0..360.0)
        }
    }

    /**
     * MoonPhase gebruikt dezelfde ΔT als MoonCalculator (de AZ/EL-berekening) in plaats van een
     * eigen kopie. Deze test pint de "nu geldige" waarde vast; verandert ze ooit in
     * MoonCalculator (zoals het commentaar daar al aankondigt voor na 2030), dan moet deze
     * test mee aangepast worden - dat is precies het signaal dat de twee bestanden niet meer
     * uit elkaar kunnen lopen.
     */
    @Test
    fun usesMoonCalculatorsSharedDeltaT() {
        assertEquals(69.2, MoonCalculator.DELTA_T_SECONDS, 0.0)
    }
}
