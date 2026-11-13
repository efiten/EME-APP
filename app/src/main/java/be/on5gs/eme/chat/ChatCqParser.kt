package be.on5gs.eme.chat

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** Eén CQ-aanroep uit de HB9Q-chat, ontleed in zijn onderdelen. Onbekend = "?". */
data class ChatCq(
    val messageId: Long,
    val utcMillis: Long,
    val time: String,      // "09:01" (UTC, zoals in de chat)
    val freq: String,      // "1296.088"
    val period: String,    // "1st" / "2nd" / "?"
    val callsign: String,
    val name: String,
    val mode: String,      // "Q65-60C", "JT65C", "CW", ... of "?"
    val cfom: String,      // "CFOM" / "own echo" / "?"
    val hz: String,        // "1500" of "?"
)

/**
 * Herkent CQ-meldingen in de HB9Q-chat. Ze staan tussen 5 sterretjes, bv.
 *   ***** CQ 1296.065 2nd Q65-60C 1500 CFOM *****
 *   ***** CQ 1296.085 1st Q65-60C, CFOM @ 1500 hz. 1.5m/Superpatch. *****
 * Iedereen schrijft ze een beetje anders, dus het herkennen is bewust verdraagzaam.
 */
object ChatCqParser {

    private val FREQ = Regex("""\b(\d{2,5})\.(\d{0,3})""")
    private val PERIOD = Regex("""(?i)\b(1st|2nd)\b""")
    private val Q65 = Regex("""(?i)\bQ65[A-E]?\s*-?\s*(\d{2})\s*([A-E])\b""")
    private val OTHER_MODE = Regex("""(?i)\b(JT65[A-C]?|JT4[A-G]?|JT9|QRA64[A-E]?|FT8|FT4|FST4|MSK144|JTTY|ISCAT-?[AB]?|CW|SSB)\b""")
    private val CFOM = Regex("""(?i)CFOM""")
    private val OWN_ECHO = Regex("""(?i)own\s*echo""")
    /** Een getal van 3-4 cijfers dat geen vermogen (W), afstand (m), duur (min) of dB is. */
    private val HZ = Regex("""(?i)(?<![\d.])(\d{3,4})(?![\d.])(?!\s*(?:w\b|m\b|min|db|watt))""")

    /** Cyrillische letters die er hetzelfde uitzien als Latijnse (bv. "Q65-60С" met Cyrillische С). */
    private val LOOKALIKES = mapOf(
        'А' to 'A', 'В' to 'B', 'С' to 'C', 'Е' to 'E', 'Н' to 'H', 'К' to 'K', 'М' to 'M',
        'О' to 'O', 'Р' to 'P', 'Т' to 'T', 'Х' to 'X',
        'а' to 'a', 'с' to 'c', 'е' to 'e', 'о' to 'o', 'р' to 'p', 'х' to 'x',
    )

    /** Is dit bericht een CQ-aanroep? (CQ-knop van de site, of tekst tussen *****) */
    fun isCq(msg: ChatMessage): Boolean =
        msg.isCq || (msg.text.contains("*****") && Regex("""(?i)\bCQ\b""").containsMatchIn(msg.text))

    fun parse(msg: ChatMessage, nowMillis: Long): ChatCq? {
        if (!isCq(msg)) return null
        val text = clean(msg.text)

        val f = FREQ.find(text)
        val freq = f?.let { if (it.groupValues[2].isEmpty()) it.groupValues[1] + ".?" else it.value } ?: "?"
        val rest = if (f != null) text.removeRange(f.range) else text

        val period = PERIOD.find(rest)?.value?.lowercase() ?: "?"
        val mode = Q65.find(rest)?.let { "Q65-${it.groupValues[1]}${it.groupValues[2].uppercase()}" }
            ?: OTHER_MODE.find(rest)?.value?.uppercase()?.let { if (it == "ISCATA" || it == "ISCATB") "ISCAT-" + it.last() else it }
            ?: "?"
        val cfom = when {
            CFOM.containsMatchIn(rest) -> "CFOM"
            OWN_ECHO.containsMatchIn(rest) -> "own echo"
            else -> "?"
        }
        // Hz: eerste getal tussen 200 en 3000, buiten de frequentie en de mode (bv. "60" in Q65-60C)
        val withoutMode = Q65.replace(rest, " ")
        val hz = HZ.findAll(withoutMode).map { it.groupValues[1].toInt() }.firstOrNull { it in 200..3000 }?.toString() ?: "?"

        return ChatCq(
            messageId = msg.id,
            utcMillis = messageMillis(msg, nowMillis),
            time = msg.time,
            freq = freq,
            period = period,
            callsign = msg.callsign,
            name = msg.name.ifBlank { "?" },
            mode = mode,
            cfom = cfom,
            hz = hz,
        )
    }

    /**
     * CQ's van de laatste [minutes] minuten, nieuwste bovenaan.
     * Dezelfde aanroep die meerdere keren in dezelfde minuut gepost werd, wordt één keer getoond.
     */
    fun recent(messages: List<ChatMessage>, nowMillis: Long, minutes: Int = 30): List<ChatCq> =
        messages.mapNotNull { parse(it, nowMillis) }
            .filter { nowMillis - it.utcMillis in -120_000L..minutes * 60_000L }
            .distinctBy { Triple(it.callsign, it.time, it.freq) }
            .sortedWith(compareByDescending<ChatCq> { it.utcMillis }.thenByDescending { it.messageId })

    /** Tekst opschonen: lookalikes vervangen, sterretjes en >> << weg, spaties normaliseren. */
    internal fun clean(text: String): String =
        text.map { LOOKALIKES[it] ?: it }.joinToString("")
            .replace(Regex("""[*<>]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    /**
     * Tijdstip van een chatbericht in UTC. De chat toont "07:16" en (in fullTime) "Fri 6th of Nov - 07:16".
     * Zonder bruikbare datum: het meest recente moment met die klokuur (vandaag of gisteren).
     */
    internal fun messageMillis(msg: ChatMessage, nowMillis: Long): Long {
        val now = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMillis), ZoneOffset.UTC)
        val hm = Regex("""(\d{1,2}):(\d{2})""").find(msg.time) ?: return 0L
        val time = LocalTime.of(hm.groupValues[1].toInt(), hm.groupValues[2].toInt())
        val dm = Regex("""(\d{1,2})(?:st|nd|rd|th)\s+of\s+([A-Za-z]{3})""").find(msg.fullTime)
        val date = dm?.let {
            val month = MONTHS.indexOf(it.groupValues[2].lowercase()) + 1
            if (month == 0) null else {
                val daysInMonth = java.time.YearMonth.of(now.year, month).lengthOfMonth()
                var d = LocalDate.of(now.year, month, it.groupValues[1].toInt().coerceIn(1, daysInMonth))
                if (d.isAfter(now.toLocalDate().plusDays(1))) d = d.minusYears(1)   // jaarwissel
                d
            }
        } ?: run {
            val today = now.toLocalDate()
            if (today.atTime(time).isAfter(now.toLocalDateTime().plusMinutes(2))) today.minusDays(1) else today
        }
        return date.atTime(time).toInstant(ZoneOffset.UTC).toEpochMilli()
    }

    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
}
