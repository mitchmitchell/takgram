package org.takgram.core

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What to do with an event that the user's own ATAK sent to TAKgram's local server. */
sealed interface OutAction {
    /** ATAK's own position report: tells us the user's uid, callsign and team. Never posted. */
    data class SelfSeen(val identity: Identity) : OutAction

    /** Post (or update) the object as a card in the linked chat. */
    data class Card(val ev: CotEvent) : OutAction

    /** Post the text as an ordinary chat message. */
    data class ChatText(val text: String) : OutAction

    /** Not posted. [why] is set when the user would expect a post, so the app can explain. */
    data class Ignore(val why: String = "") : OutAction
}

/**
 * Decides what leaves the phone. Each phone posts only what its own user created, so a chat never
 * gets the same object from two members; and nothing that arrived from Telegram is posted back.
 */
class OutboundPolicy(
    private val cameFromTelegram: (uid: String) -> Boolean,
    private val isTelegramContact: (uid: String) -> Boolean,
    private val isTelegramCallsign: (callsign: String) -> Boolean = { false },
) {
    /** The uid of the user's ATAK, learned from its first position report. */
    @Volatile var selfUid: String = ""

    fun decide(ev: CotEvent): OutAction {
        val t = ev.type
        if (t.startsWith("t-")) return OutAction.Ignore() // pings, deletes and other control traffic

        val chat = ev.chat
        if (chat != null) {
            if (selfUid.isNotEmpty() && chat.senderUid != selfUid) return OutAction.Ignore()
            if (chat.text.isEmpty()) return OutAction.Ignore()
            val toChat = chat.toUid == CotBuild.ALL_CHAT || isTelegramContact(chat.toUid)
            return if (toChat) OutAction.ChatText(chat.text) else OutAction.Ignore()
        }

        if (t.startsWith("a-") && ev.isTakClient) {
            if (selfUid.isNotEmpty() && ev.uid != selfUid) return OutAction.Ignore()
            if (ev.callsign.isEmpty()) return OutAction.Ignore()
            selfUid = ev.uid
            return OutAction.SelfSeen(Identity(ev.uid, ev.callsign, ev.team.ifEmpty { Teams.DEFAULT }))
        }

        if (cameFromTelegram(ev.uid)) return OutAction.Ignore("it came from Telegram")
        // The same rule without memory, for after a restart: these uids are only ever made by the bridge.
        if (ev.uid.startsWith(Bridge.PIN_UID_PREFIX) || ev.uid.startsWith(Bridge.TELEGRAM_UID_PREFIX)) {
            return OutAction.Ignore("it came from Telegram")
        }
        if (ev.creatorUid.isNotEmpty() && selfUid.isNotEmpty() && ev.creatorUid != selfUid) {
            return OutAction.Ignore("someone else created it")
        }
        // Sent to named contacts rather than broadcast: it belongs in the chat only if one of them
        // is a chat member. Something sent privately to a Wi-Fi teammate stays private.
        if (ev.destCallsigns.isNotEmpty() && ev.destCallsigns.none(isTelegramCallsign)) {
            return OutAction.Ignore("it was sent only to contacts outside the chat")
        }

        val postable = t.startsWith("a-") || t.startsWith("b-m-p") || t.startsWith("b-a-") || t == "b-r-f-h-c"
        return if (postable) OutAction.Card(ev) else OutAction.Ignore("shapes and routes are not supported yet")
    }
}

/** The text of an object card: what a person without TAK needs, then the #cot1 line. */
object Caption {
    /** Telegram's limit for a photo or document caption. */
    const val PHOTO_LIMIT = 1024

    /** Telegram's limit for a text message. */
    const val TEXT_LIMIT = 4096

    private val CLOCK: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)

    fun clock(t: Instant): String = CLOCK.format(t)

    fun title(ev: CotEvent): String = ev.callsign.ifEmpty { ev.uid }

    /** The readable lines, without the machine line. */
    fun lines(ev: CotEvent): List<String> {
        val out = ArrayList<String>()
        val what = CotTypes.describe(ev.type)
        out.add(if (CotTypes.isAlert(ev.type)) "EMERGENCY: ${title(ev)} · $what" else "${title(ev)} · $what")
        out.add(String.format(Locale.ROOT, "%.5f, %.5f", ev.lat, ev.lon))
        Mgrs.of(ev.lat, ev.lon).takeIf { it.isNotEmpty() }?.let { out.add("MGRS $it") }
        val bits = ArrayList<String>()
        ev.hae?.let { bits.add(String.format(Locale.ROOT, "%.0f m HAE", it)) }
        ev.ce?.let { bits.add(String.format(Locale.ROOT, "±%.0f m", it)) }
        if (bits.isNotEmpty()) out.add(bits.joinToString(" · "))
        val speed = ev.speed
        val course = ev.course
        if (course != null && speed != null && speed > 0.3) {
            out.add(String.format(Locale.ROOT, "%03.0f° at %.0f km/h", course, speed * 3.6))
        }
        out.add(clock(ev.time))
        return out
    }

    /**
     * The parts of a card to show in fixed-width type: the coordinates, the grid reference and the
     * machine line. Without this Telegram turns the grid digits into a phone-number link and the
     * tag into a hashtag link. Ranges are in UTF-16 units, as Telegram counts them.
     */
    fun codeRanges(card: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        var start = 0
        val lines = card.split('\n')
        for ((i, line) in lines.withIndex()) {
            val isCode = i == 1 || line.startsWith("MGRS ") || (i == lines.lastIndex && line.startsWith(Cot1Codec.TAG))
            if (isCode && line.isNotEmpty()) {
                val from = if (line.startsWith("MGRS ")) start + 5 else start
                out.add(from until start + line.length)
            }
            start += line.length + 1
        }
        return out
    }

    /**
     * The whole card text within [limit] characters, ending in the #cot1 line. Remarks are cut to
     * fit. Returns null if even the essentials do not fit, in which case the event goes as a
     * `.cot` document instead.
     */
    fun card(ev: CotEvent, limit: Int): String? {
        val machine = Cot1Codec.encode(CotBuild.fromEvent(ev))
        val head = lines(ev).joinToString("\n")
        val fixed = head.length + 1 + machine.length
        if (fixed > limit) return null
        var remarks = ev.remarks.replace('\n', ' ').trim()
        val room = limit - fixed - 1
        if (remarks.length > room) remarks = if (room > 20) remarks.take(room - 1) + "…" else ""
        return if (remarks.isEmpty()) "$head\n$machine" else "$head\n$remarks\n$machine"
    }
}
