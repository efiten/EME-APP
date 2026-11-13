package be.on5gs.eme.cq

import be.on5gs.eme.UserAgent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Haalt de Live CQ-gegevens op bij W3SZ, op dezelfde manier als MoonTrack:
 * de officiële CSV-export van de laatste dag, met een eerlijke User-Agent.
 */
object LiveCqRepository {

    private const val EXPORT_URL = "https://w3sz.com/export_livecq3.php"

    /** Gooit een IOException met een begrijpelijke Nederlandse melding bij problemen. */
    suspend fun fetch(): List<LiveCqSpot> = withContext(Dispatchers.IO) {
        val conn = (URL(EXPORT_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", UserAgent.VALUE)
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        try {
            conn.outputStream.use { it.write("time_period=1d".toByteArray()) }
            val code = conn.responseCode
            when {
                code == 409 || code == 403 -> throw IOException("W3SZ HTTP $code")
                code != 200 -> throw IOException("W3SZ HTTP $code")
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            if (text.trimStart().startsWith("<")) throw IOException("W3SZ: no CSV data")
            LiveCqParser.parse(text)
        } finally {
            conn.disconnect()
        }
    }
}
