package be.on5gs.eme.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatStoreTest {

    private fun msg(id: Long, time: String, call: String, name: String) =
        ChatMessage(id, time, "", call, name, null, "test", isCq = false, senderId = id.toString())

    /** De foutmelding hoort bij de band die net opgehaald werd, niet bij de band die nu gekozen is. */
    @Test
    fun errorOnlyShownForTheBandItBelongsTo() {
        val state = ChatStore.State(band = Hb9qBand.B432, error = 1 to "boom")
        assertNull(ChatStore.errorFor(state, Hb9qBand.B1296))
        assertEquals(1 to "boom", ChatStore.errorFor(state, Hb9qBand.B432))
    }

    /** Deelnemers: wie recent schreef, met de tijd van het laatste bericht, nieuwste eerst, zonder jezelf. */
    @Test
    fun recentParticipantsWithTime() {
        val messages = listOf(          // zoals de chat: nieuwste bovenaan
            msg(5, "09:03", "UA3PTW", "Dmitrij"),
            msg(4, "09:01", "F5AOL", "Pascal"),
            msg(3, "08:50", "ON5GS", "Dirk"),
            msg(2, "08:37", "OE3GWC", "Gerd"),
            msg(1, "08:30", "F5AOL", "Pascal"),
        )
        val p = ChatStore.recentParticipants(ChatStore.State(messages = messages), "ON5GS")
        assertEquals(
            listOf("09:03 UA3PTW Dmitrij", "09:01 F5AOL Pascal", "08:37 OE3GWC Gerd"),
            p.map { "${it.time} ${it.callsign} ${it.name}" },
        )
    }
}
