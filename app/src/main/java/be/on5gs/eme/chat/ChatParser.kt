package be.on5gs.eme.chat

import org.jsoup.Jsoup

/**
 * Leest de berichtenrijen die HB9Q terugstuurt (het "logger"-veld uit logger_lst.cfm).
 * Elke rij: <tr id="msg_123" class="... msgtype_0">
 *   <td>tijd</td> <td>[<span>@CALL: </span>] tekst</td> <td>@</td> <td>CALL</td> <td><span>naam</span>...</td>
 */
object ChatParser {

    private val TIME_IN_ONCLICK = Regex("showTime\\(this,'([^']*)'\\)")

    private val CQ_CLASS = Regex("^CQis(\\d+)$")

    /**
     * Leest de deelnemerslijst (het "whoList"-veld uit whoisonline.cfm).
     * tr.who_hour = actief het laatste uur, tr.who_day = vandaag eerder, tr.whoMe = jijzelf.
     */
    fun parseMembers(html: String): List<ChatMember> {
        val doc = Jsoup.parseBodyFragment(if (html.contains("<table")) html else "<table><tbody>$html</tbody></table>")
        return doc.select("tr.who_hour, tr.who_day").mapNotNull { tr ->
            val id = tr.selectFirst("input.who_highlight")?.attr("value")?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val isMe = tr.hasClass("whoMe")
            val callsign = tr.selectFirst("span[onclick^=getInfo]")?.text()?.trim()
                ?: tr.selectFirst("label.label")?.text()?.replace("(ME)", "")?.trim()
                ?: return@mapNotNull null
            val cq = tr.select("td").firstNotNullOfOrNull { td ->
                CQ_CLASS.find(td.className())?.groupValues?.get(1)?.toIntOrNull()
            }
            ChatMember(
                memberId = id,
                callsign = callsign,
                name = tr.selectFirst("span[onclick^=sendMail]")?.text()?.trim() ?: "",
                isMe = isMe,
                activeLastHour = tr.hasClass("who_hour"),
                cqMinutes = cq,
                highlightColor = tr.selectFirst("input[name^=who_highlight_color_]")?.attr("value") ?: "0",
            )
        }
    }

    fun parseRows(html: String): List<ChatMessage> {
        val doc = Jsoup.parseBodyFragment("<table><tbody>$html</tbody></table>")
        return doc.select("tr[id^=msg_]").mapNotNull { tr ->
            val tds = tr.select("> td")
            if (tds.size < 4) return@mapNotNull null
            val id = tr.id().removePrefix("msg_").toLongOrNull() ?: return@mapNotNull null

            val textCell = tds[1].clone()
            val recipientSpan = textCell.selectFirst("> span")
            val recipient = recipientSpan?.text()?.trim()
                ?.takeIf { it.startsWith("@") }
                ?.removePrefix("@")?.removeSuffix(":")?.trim()
            if (recipient != null) recipientSpan.remove()

            ChatMessage(
                id = id,
                time = tds[0].text().trim(),
                fullTime = TIME_IN_ONCLICK.find(tds[0].attr("onclick"))?.groupValues?.get(1) ?: "",
                callsign = tds[3].text().trim(),
                name = tds.getOrNull(4)?.selectFirst("span")?.text()?.trim() ?: "",
                recipient = recipient,
                text = textCell.text().trim(),
                isCq = tr.hasClass("msgtype_10"),
                senderId = tr.classNames().firstOrNull { it.startsWith("msg_mem_") }?.removePrefix("msg_mem_"),
            )
        }
    }
}
