package be.on5gs.eme.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatStoreTest {

    private fun msg(id: Long, time: String, call: String, name: String) =
        ChatMessage(id, time, "", call, name, null, "test", isCq = false, senderId = id.toString())

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
