package be.on5gs.eme.settings

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import be.on5gs.eme.horizon.Horizon
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

    @Test
    fun horizonIsKeptAndRubbishFallsBackToNoHorizon() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = SettingsRepository(context)
        assertEquals(Horizon(), settings.horizon)                       // nieuwe installatie: geen horizon

        val mine = Horizon.decode("1;5;B 60 110 25;P 180 200 20")!!
        settings.horizon = mine
        assertEquals(mine, SettingsRepository(context).horizon)

        context.getSharedPreferences("eme_settings", android.content.Context.MODE_PRIVATE)
            .edit().putString("horizon", "9;onzin").commit()
        assertEquals(Horizon(), SettingsRepository(context).horizon)
    }
}
