package be.on5gs.eme.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatParserTest {

    /** Berichtenrijen uit een echte HB9Q-pagina (1296); e-mailadressen vervangen door xxxx@test.com. */
    private val rows: String =
        javaClass.classLoader!!.getResource("hb9q_logger_rows.html")!!.readText()

    @Test
    fun parsesAllRows() {
        val msgs = ChatParser.parseRows(rows)
        assertEquals(63, msgs.size)
        assertEquals(12, msgs.count { it.isCq })
    }

    @Test
    fun cqMessage() {
        val m = ChatParser.parseRows(rows).first()
        assertEquals(1990523L, m.id)
        assertEquals("07:16", m.time)
        assertEquals("Fri 6th of Nov - 07:16", m.fullTime)
        assertEquals("SP5GDM", m.callsign)
        assertEquals("Jan", m.name)
        assertNull(m.recipient)
        assertTrue(m.isCq)
        assertEquals("***** CQ 1296.060 1st Q65-60C CFOM 1500Hz *****", m.text)
    }

    @Test
    fun directedMessage() {
        val m = ChatParser.parseRows(rows).first { it.callsign == "NJ2R" && it.recipient != null }
        assertEquals("KD2XN", m.recipient)
        assertEquals("Phil are you around for a try?", m.text)   // "@KD2XN:" zit niet meer in de tekst
        assertFalse(m.isCq)
    }

    @Test
    fun noEmailAddressesInParsedData() {
        ChatParser.parseRows(rows).forEach { assertFalse(it.toString().contains("@") && it.toString().contains(".com")) }
    }

    private val who: String =
        javaClass.classLoader!!.getResource("hb9q_who.html")!!.readText()

    @Test
    fun senderIdForReplies() {
        val m = ChatParser.parseRows(rows).first()
        assertEquals("212", m.senderId)
    }

    @Test
    fun parsesMemberList() {
        val members = ChatParser.parseMembers(who)
        assertEquals(19, members.size)
        assertEquals(5, members.count { it.activeLastHour })          // 4 anderen + ikzelf
        val me = members.single { it.isMe }
        assertEquals("ON5GS", me.callsign)
        assertEquals("310", me.memberId)
        assertEquals("me", me.highlightColor)
        val sp5gdm = members.single { it.callsign == "SP5GDM" }
        assertEquals("Jan", sp5gdm.name)
        assertEquals(15, sp5gdm.cqMinutes)
        assertEquals("212~SP5GDM", sp5gdm.recipientValue)
        assertNull(members.single { it.callsign == "F1RJ" }.cqMinutes)
    }

    @Test
    fun membersFromFullChatPage() {
        val page = javaClass.classLoader!!.getResource("hb9q_page.html")!!.readText()
        val members = ChatParser.parseMembers(page)
        assertEquals(19, members.size)
        assertTrue(members.single { it.isMe }.callsign == "ON5GS")
        assertEquals(15, members.single { it.callsign == "SP5GDM" }.cqMinutes)
    }

    @Test
    fun emptyOrGarbage() {
        assertTrue(ChatParser.parseRows("").isEmpty())
        assertTrue(ChatParser.parseRows("<tr><td>x</td></tr>").isEmpty())
        assertTrue(ChatParser.parseMembers("").isEmpty())
    }
}
