package be.on5gs.eme.settings

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SettingsRepositoryTest {

    /** Een nieuwe installatie heeft geen call en geen locator: die van een ander station tonen klopt niet. */
    @Test
    fun freshInstallHasNoCallsignAndNoLocator() {
        val settings = SettingsRepository(ApplicationProvider.getApplicationContext())
        assertEquals("", settings.callsign)
        assertEquals("", settings.locator)
    }

    @Test
    fun savedValuesAreKept() {
        val settings = SettingsRepository(ApplicationProvider.getApplicationContext())
        settings.callsign = "PA3DRK"
        settings.locator = "JO21"
        assertEquals("PA3DRK", settings.callsign)
        assertEquals("JO21", settings.locator)
    }
}
