package org.takgram.core

import java.net.URLDecoder
import java.net.URLEncoder

/** Links a Telegram account to the TAK identity its owner uses. */
data class Identity(val uid: String, val callsign: String, val team: String)

/**
 * The identity card each TAKgram user posts once in a linked chat. Readable text, then one machine line:
 *
 *     #takid1 uid=<url-encoded> cs=<url-encoded> team=<url-encoded>
 *
 * A card only ever describes the account that posted it; Telegram vouches for the sender.
 */
object IdentityCard {
    const val TAG = "#takid1"
    private const val MAX_LINE = 600

    fun text(id: Identity): String =
        "TAKgram identity\nCallsign: ${id.callsign}\nTeam: ${id.team}\n" +
            "$TAG uid=${enc(id.uid)} cs=${enc(id.callsign)} team=${enc(id.team)}"

    private fun lastLine(text: String): String = text.trimEnd().substringAfterLast('\n').trim()

    fun contains(text: String): Boolean = lastLine(text).startsWith("$TAG ")

    /** @return the identity on the last line, or null if there is none or it is invalid. */
    fun parse(text: String): Identity? {
        val last = lastLine(text)
        if (!last.startsWith("$TAG ") || last.length > MAX_LINE) return null
        val fields = HashMap<String, String>()
        for (part in last.substring(TAG.length + 1).split(' ')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val value = try {
                URLDecoder.decode(part.substring(eq + 1), "UTF-8")
            } catch (e: IllegalArgumentException) {
                return null
            }
            fields[part.substring(0, eq)] = value
        }
        val uid = fields["uid"].orEmpty()
        val cs = CotParse.clean(fields["cs"].orEmpty(), CotParse.MAX_NAME)
        val team = fields["team"].orEmpty()
        if (uid.isEmpty() || uid.length > CotParse.MAX_UID || uid.any { it < ' ' }) return null
        if (cs.isEmpty()) return null
        return Identity(uid, cs, if (Teams.valid(team)) team else Teams.TELEGRAM_ONLY)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
