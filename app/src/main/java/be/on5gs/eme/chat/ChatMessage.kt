package be.on5gs.eme.chat

/** De bandknoppen van de HB9Q-chat (EME). id = het nummer dat de HB9Q-site gebruikt. */
enum class Hb9qBand(val id: Int, val label: String) {
    B50(1, "50"),
    B144(2, "144"),
    B222(3, "222"),
    B432(4, "432"),
    B902(5, "902"),
    B1296(6, "1296"),
    B23XX(7, "23xx"),
    B3400(10, "3400"),
    B5760(9, "5760"),
    B10G(8, "10xxx"),
    B24G(11, "24048"),
    B47G(12, "47088"),
    B76G(13, "76032"),
}

/** Eén bericht uit de HB9Q-chat. */
data class ChatMessage(
    val id: Long,
    val time: String,          // "07:16" (UTC)
    val fullTime: String,      // "Fri 6th of Nov - 07:16"
    val callsign: String,      // afzender
    val name: String,          // voornaam van de afzender
    val recipient: String?,    // "KD2XN" als het bericht aan iemand gericht is, anders null
    val text: String,
    val isCq: Boolean,         // CQ-melding (via de CQ-knop op de site)
    val senderId: String? = null,  // intern HB9Q-nummer van de afzender (om te antwoorden)
)

/** Eén deelnemer uit de lijst "Who is online". */
data class ChatMember(
    val memberId: String,      // intern nummer bij HB9Q
    val callsign: String,
    val name: String,
    val isMe: Boolean,
    val activeLastHour: Boolean,   // false = vandaag eerder gezien (>1 uur geleden)
    val cqMinutes: Int?,           // riep CQ in de laatste 5/10/15 minuten, anders null
    val highlightColor: String = "0",  // verborgen veld dat de site terugstuurt bij het verversen
) {
    /** Waarde die de HB9Q-site gebruikt om een bericht aan dit station te richten. */
    val recipientValue: String get() = "$memberId~$callsign"
}
