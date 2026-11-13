package be.on5gs.eme.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SavedPasswordTest {

    /** Eerste installatie: login bewaren, login verbeteren en opnieuw bewaren zonder het wachtwoord opnieuw te typen. */
    @Test
    fun secondSaveWithoutTypingKeepsPasswordJustSaved() {
        val p = SavedPassword(null)
        assertEquals("pw1", p.passwordToSave("pw1"))
        assertEquals("pw1", p.passwordToSave(""))
    }

    /** Er stond al een login: het nieuwe wachtwoord blijft, niet het oude. */
    @Test
    fun secondSaveKeepsNewPasswordNotOldOne() {
        val p = SavedPassword("old")
        assertEquals("new", p.passwordToSave("new"))
        assertEquals("new", p.passwordToSave(""))
    }

    @Test
    fun afterClearNothingIsReused() {
        val p = SavedPassword("old")
        p.clear()
        assertEquals("", p.passwordToSave(""))
    }
}
