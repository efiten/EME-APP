package be.on5gs.eme.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException

/** org.json is een stub in gewone JVM-tests; dit draait daarom met Robolectric. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class Hb9qClientTest {

    private val client = Hb9qClient()

    @Test
    fun aGenuinelyEmptyChatHasAnEmptyLoggerField() {
        val json = JSONObject("""{"status":"success","logger":""}""")
        assertEquals("", client.loggerRows(json))
    }

    @Test
    fun rowsArePassedThroughUnchanged() {
        val json = JSONObject("""{"status":"success","logger":"<tr>...</tr>"}""")
        assertEquals("<tr>...</tr>", client.loggerRows(json))
    }

    /**
     * Zonder de fix geeft optString("logger") hier stilzwijgend "" terug, identiek aan een
     * eerlijk lege chat - deze test faalt dan omdat er geen IOException gegooid wordt.
     */
    @Test
    fun aMissingLoggerFieldIsTreatedAsAnErrorNotAnEmptyChat() {
        val json = JSONObject("""{"status":"success"}""")
        assertThrows(IOException::class.java) { client.loggerRows(json) }
    }
}
