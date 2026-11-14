package be.on5gs.eme.cq

import be.on5gs.eme.UserAgent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Haalt de Live CQ-gegevens op, alleen lezen en met een eerlijke User-Agent:
 *  - W3SZ: de officiële CSV-export van de laatste dag, op dezelfde manier als MoonTrack;
 *  - N6NU: de JSON die de pagina livecq.n6nu.org zelf ook gebruikt, alleen EME, laatste 60 min.
 */
object LiveCqRepository {

    private const val W3SZ_EXPORT_URL = "https://w3sz.com/export_livecq3.php"
    internal const val N6NU_SPOTS_URL =
        "https://livecq.n6nu.org/api/spots?limit=200&offset=0&minutes=60&path=eme&lists=0"

    /** Gooit een IOException bij problemen. */
    suspend fun fetchW3sz(): List<LiveCqSpot> = withContext(Dispatchers.IO) {
        val conn = (URL(W3SZ_EXPORT_URL).openConnection() as HttpURLConnection).apply {
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
            if (code != 200) throw IOException("W3SZ HTTP $code")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            if (text.trimStart().startsWith("<")) throw IOException("W3SZ: no CSV data")
            LiveCqParser.parse(text)
        } finally {
            conn.disconnect()
        }
    }

    /** Gooit een IOException bij problemen. Alleen een GET: de app stuurt niets naar N6NU. */
    suspend fun fetchN6nu(): List<LiveCqSpot> = withContext(Dispatchers.IO) {
        val conn = (URL(N6NU_SPOTS_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", UserAgent.VALUE)
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            if (code != 200) throw IOException("N6NU HTTP $code")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            if (!text.trimStart().startsWith("{")) throw IOException("N6NU: no JSON data")
            N6nuParser.parse(text)
        } finally {
            conn.disconnect()
        }
    }
}
