package be.on5gs.eme.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ChatCqParserTest {

    private val now = Instant.parse("2026-11-08T09:10:00Z").toEpochMilli()

    private fun msg(text: String, time: String = "09:01", call: String = "F5AOL", name: String = "Pascal") =
        ChatMessage(1, time, "Sun 8th of Nov - $time", call, name, null, text, isCq = false, senderId = "1")

    /** "freq period mode cfom hz" van één melding. */
    private fun fields(text: String): String {
        val c = ChatCqParser.parse(msg(text), now)!!
        return listOf(c.freq, c.period, c.mode, c.cfom, c.hz).joinToString(" ")
    }

    /** De voorbeelden van ON5GS uit de HB9Q-chat van 8 november 2026. */
    @Test
    fun realWorldExamples() {
        assertEquals("1296.088 1st Q65-60C CFOM 1500", fields("***** CQ 1296.088 1st Q65-60C CFOM 1500 2.5m 100W *****"))
        assertEquals("1296.065 2nd Q65-60C CFOM 1500", fields("***** CQ 1296.065 2nd Q65-60C 1500 CFOM *****"))
        assertEquals("1296.085 1st Q65-60C CFOM 1500", fields("***** CQ 1296.085 1st Q65-60C, CFOM @ 1500 hz. 1.5m/Superpatch. *****"))
        assertEquals("1296.077 2nd Q65-60B CFOM 1500", fields("***** CQ 1296.077 2nd ***** GE CQ 1296.077 2nd Q65-60b, CFOM@1500 ***** *****"))
        assertEquals("1296.070 1st Q65-60C CFOM 1500", fields("***** CQ 1296.070 1st **Q65-60C** CFOM 1500hz *****"))
        assertEquals("1296.075 2nd Q65-60C CFOM 1500", fields("***** CQ 1296.075 2nd Q65-60C CFOM 1500 Hz *****"))
        assertEquals("1296.065 1st Q65-30B CFOM 1500", fields("***** CQ 1296.065 1st - Q65 - >>30b< -1500Hz - CFOM>> *****"))
        assertEquals("1296.070 1st Q65-60C CFOM 1500", fields("***** CQ 1296.070 1st Q65c-60C 1500Hz CFOM 10 min *****"))
        assertEquals("1296.075 1st Q65-60C CFOM 1500", fields("***** CQ 1296.075 1st CFOM 1500Hz Q65-60C *****"))
        assertEquals("1296.090 1st Q65-30B CFOM 1500", fields("***** CQ 1296.090 1st Q65-30B CFOM @1500 ***** *****"))
        // Cyrillische С in de mode
        assertEquals("1296.085 1st Q65-60C CFOM 1500", fields("***** CQ 1296.085 1st Q65-60С CFOM 1500 ***** *****"))
    }

    @Test
    fun missingPartsBecomeQuestionMarks() {
        assertEquals("1296.? 1st ? ? ?", fields("***** CQ 1296. 1st Thank you very much for the first DIGI contact. *****"))
        assertEquals("432.065 ? JT65B ? ?", fields("***** CQ 432.065 JT65B *****"))
        assertEquals("1296.050 2nd CW own echo ?", fields("***** CQ 1296.050 2nd CW own echo *****"))
        assertEquals("1296.040 1st Q65-60A ? 1200", fields("***** CQ 1296.040 1st Q65-60A 1200Hz *****"))
    }

    @Test
    fun ordinaryMessagesAreNotCq() {
        assertNull(ChatCqParser.parse(msg("089 -332 0 0900 2.7 -10 : CQ F5AOL JN38"), now))
        assertNull(ChatCqParser.parse(msg("@KB2SA: Thanks for the reports."), now))
        assertNull(ChatCqParser.parse(msg("stop CQ 075"), now))
    }

    @Test
    fun lineContainsCallAndName() {
        val c = ChatCqParser.parse(msg("***** CQ 1296.088 1st Q65-60C CFOM 1500 2.5m 100W *****"), now)!!
        assertEquals("09:01", c.time)
        assertEquals("F5AOL", c.callsign)
        assertEquals("Pascal", c.name)
    }

    @Test
    fun recent_lastHalfHourNewestFirstWithoutDuplicates() {
        val list = listOf(
            msg("***** CQ 1296.088 1st Q65-60C CFOM 1500 *****", "09:01", "F5AOL").copy(id = 5),
            msg("***** CQ 1296.065 2nd Q65-60C 1500 CFOM *****", "08:50", "OE3GWC").copy(id = 4),
            msg("***** CQ 1296.065 2nd Q65-60C 1500 CFOM *****", "08:50", "OE3GWC").copy(id = 3),   // dubbel gepost
            msg("***** CQ 1296.085 1st Q65-60C CFOM 1500 *****", "08:30", "R4CX").copy(id = 2),     // > 30 min oud
        )
        val r = ChatCqParser.recent(list, now, 30)
        assertEquals(listOf("F5AOL", "OE3GWC"), r.map { it.callsign })
    }

    @Test
    fun messageTimeUsesDateFromFullTime() {
        // Bericht van gisteren op hetzelfde klokuur mag niet als "net verstuurd" tellen
        val old = ChatMessage(1, "09:05", "Sat 7th of Nov - 09:05", "K6EME", "Matt", null, "***** CQ 1296.075 *****", true)
        assertEquals(Instant.parse("2026-11-07T09:05:00Z").toEpochMilli(), ChatCqParser.messageMillis(old, now))
        assertTrue(ChatCqParser.recent(listOf(old), now, 30).isEmpty())
    }

    /** Zonder datum in fullTime: vlak voor middernacht UTC blijft een bericht van enkele minuten geleden van vandaag. */
    @Test
    fun messageTimeWithoutDateJustBeforeMidnight() {
        val lateNow = Instant.parse("2026-11-08T23:59:00Z").toEpochMilli()
        val justSent = ChatMessage(1, "23:57", "", "K6EME", "Matt", null, "***** CQ 1296.075 *****", true)
        assertEquals(Instant.parse("2026-11-08T23:57:00Z").toEpochMilli(), ChatCqParser.messageMillis(justSent, lateNow))
        assertEquals(1, ChatCqParser.recent(listOf(justSent), lateNow, 30).size)
        // Een klokuur dat nog moet komen, hoort bij gisteren
        val earlier = ChatMessage(2, "23:30", "", "K6EME", "Matt", null, "***** CQ 1296.075 *****", true)
        assertEquals(Instant.parse("2026-11-07T23:30:00Z").toEpochMilli(), ChatCqParser.messageMillis(earlier, Instant.parse("2026-11-08T08:00:00Z").toEpochMilli()))
    }

    /** Echte chat van 8 november: alle CQ-regels worden herkend. */
    @Test
    fun realChatPage() {
        val rows = javaClass.classLoader!!.getResource("hb9q_rows_8nov.html")!!.readText()
        val msgs = ChatParser.parseRows(rows)
        val cqs = msgs.mapNotNull { ChatCqParser.parse(it, now) }
        assertTrue("aantal CQ's: ${cqs.size}", cqs.size >= 18)
        val f5aol = cqs.first { it.callsign == "F5AOL" }
        assertEquals("1296.088", f5aol.freq)
        assertEquals("Pascal", f5aol.name)
    }

    @Test
    fun whoListFromNewPage() {
        val who = javaClass.classLoader!!.getResource("hb9q_who_8nov.html")!!.readText()
        val members = ChatParser.parseMembers(who)
        assertEquals(24, members.size)
        assertTrue(members.any { it.callsign == "F5AOL" && it.cqMinutes != null })
        assertEquals("ON5GS", members.single { it.isMe }.callsign)
    }
}
