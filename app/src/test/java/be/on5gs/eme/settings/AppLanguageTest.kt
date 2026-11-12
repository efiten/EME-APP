package be.on5gs.eme.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import be.on5gs.eme.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Controleert dat de taalkeuze echt de teksten van de app verandert. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AppLanguageTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun switchingLanguageChangesTexts() {
        val expected = mapOf(
            "nl" to "Maan", "fr" to "Lune", "en" to "Moon", "de" to "Mond", "es" to "Luna", "it" to "Luna", "pt" to "Lua",
            "sv" to "Måne", "da" to "Måne", "fi" to "Kuu", "ru" to "Луна", "zh" to "月球", "ja" to "月",
        )
        for ((code, word) in expected) {
            AppLanguage.set(ctx, code)
            assertEquals(code, word, AppLanguage.wrap(ctx).getString(R.string.tab_moon))
        }
        AppLanguage.set(ctx, "")   // terug naar de taal van de telefoon
    }
}
