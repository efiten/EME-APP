package be.on5gs.eme.degradation

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.util.Locale

/** De kalender begint de week op de dag die in het land van de gebruiker gebruikelijk is. */
class FirstDayOfWeekTest {

    @Test
    fun appLanguageWithoutCountryUsesThePhoneCountry() {
        // Taal "nl" in de app, telefoon in België: maandag (zonder land gaf Java zondag)
        assertEquals(DayOfWeek.MONDAY, firstDayOfWeek(Locale("nl"), "BE"))
        assertEquals(DayOfWeek.SUNDAY, firstDayOfWeek(Locale("en"), "US"))
    }

    @Test
    fun countryInTheLocaleWins() {
        assertEquals(DayOfWeek.MONDAY, firstDayOfWeek(Locale("de", "DE"), "US"))
        assertEquals(DayOfWeek.SUNDAY, firstDayOfWeek(Locale("ja", "JP"), "BE"))
    }

    @Test
    fun noCountryAtAllIsMonday() {
        assertEquals(DayOfWeek.MONDAY, firstDayOfWeek(Locale("nl"), ""))
    }
}
