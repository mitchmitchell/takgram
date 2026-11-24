package org.takgram.core

import java.time.Instant

/** Who sent a Telegram message. [userId] is 0 when the sender is not a user (a channel or anonymous admin). */
data class TgSender(val userId: Long, val displayName: String, val isBot: Boolean, val isSelf: Boolean)

sealed interface TgContent {
    data class Text(val text: String) : TgContent

    /** [expiresInS] is how much longer Telegram will accept updates; 0 means sharing has ended. */
    data class LiveLocation(
        val lat: Double,
        val lon: Double,
        val accuracyM: Double,
        val headingDeg: Int,
        val expiresInS: Long,
    ) : TgContent

    data class Pin(val lat: Double, val lon: Double) : TgContent
    data object Other : TgContent
}

/** One message from the linked chat. Everything in it is untrusted data. */
data class TgMessage(
    val chatId: Long,
    val messageId: Long,
    val sender: TgSender,
    val content: TgContent,
    val replyToMessageId: Long = 0,
    val edited: Boolean = false,
)

/** Where CoT for ATAK goes. */
fun interface CotSink {
    fun send(xml: String)
}

/**
 * Turns messages from the linked chat into CoT for ATAK.
 *
 * Nothing here trusts message content: text is only ever data, CoT carried in a message is parsed
 * into validated fields and written afresh, and bots are ignored unless the team has listed them.
 * The caller must only pass messages from the chat the user linked.
 */
class Bridge(
    private val sink: CotSink,
    private val now: () -> Instant = Instant::now,
    private val log: (String) -> Unit = {},
) {
    /** Telegram user ids of bots whose posts may reach the map. Empty by default. */
    @Volatile var trustedBots: Set<Long> = emptySet()

    private class Live(
        var lat: Double,
        var lon: Double,
        var accuracyM: Double,
        var headingDeg: Int,
        var expiresAt: Instant,
        var forever: Boolean,
        var lastUpdate: Instant,
        var lastEmit: Instant,
        var sender: TgSender,
    )

    private class PinRef(val uid: String, val lat: Double, val lon: Double, val by: String)

    private val identities = HashMap<Long, Identity>()
    private val live = HashMap<Long, Live>()
    private val pins = object : LinkedHashMap<Long, PinRef>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, PinRef>?) = size > MAX_PINS
    }
    private val dedup = Dedup()
    private val objectUids = object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > MAX_OBJECTS
    }
    private val contactUids = HashSet<String>()
    private val contactCallsigns = HashSet<String>()

    /** True if a chat member is shown in ATAK under this callsign. */
    @Synchronized
    fun isTelegramCallsign(callsign: String): Boolean = callsign in contactCallsigns

    /** True if this object reached ATAK from Telegram through this bridge, so it must not be posted back. */
    @Synchronized
    fun cameFromTelegram(uid: String): Boolean = objectUids.containsKey(uid) || uid in contactUids

    /** True if this uid is a chat member as shown in ATAK, so a direct message to it belongs in the chat. */
    @Synchronized
    fun isTelegramContact(uid: String): Boolean = uid in contactUids

    @Synchronized
    fun identityOf(userId: Long): Identity? = identities[userId]

    /** Callsigns of people currently sharing a live location. */
    @Synchronized
    fun activeCallsigns(): List<String> {
        val t = now()
        return live.filter { isActive(it.value, t) }.map { contact(it.value.sender).callsign }.sorted()
    }

    @Synchronized
    fun onMessage(m: TgMessage) {
        val s = m.sender
        if (s.userId == 0L) return
        if (s.isBot && s.userId !in trustedBots) return
        when (val c = m.content) {
            is TgContent.Text -> onText(m, c.text)
            is TgContent.LiveLocation -> onLive(m, c)
            is TgContent.Pin -> onPin(m, c)
            TgContent.Other -> Unit
        }
    }

    private fun onText(m: TgMessage, text: String) {
        if (text.length > MAX_TEXT_CHARS) return
        if (IdentityCard.contains(text)) {
            val id = IdentityCard.parse(text)
            if (id == null) {
                log("ignored an invalid identity card from ${m.sender.displayName}")
            } else {
                val before = contact(m.sender)
                if (before != id) {
                    identities[m.sender.userId] = id
                    if (!m.sender.isSelf) log("identity: ${m.sender.displayName} is ${id.callsign} (${id.team})")
                    // Move an already visible position over to the new identity at once, so the
                    // person is one icon and not two.
                    live[m.sender.userId]?.let {
                        if (before.uid != id.uid) sink.send(CotBuild.delete(before.uid, now()))
                        emit(it, now())
                    }
                }
            }
            return
        }
        val payload = Cot1Codec.find(text)
        if (payload != null) {
            if (!m.sender.isSelf) onCot1(m, payload) // our own objects are already in our ATAK
            return
        }
        val pin = pins[m.replyToMessageId]
        if (pin != null && !m.edited) {
            val name = CotParse.clean(text, CotParse.MAX_NAME)
            if (name.isNotEmpty()) {
                sink.send(CotBuild.marker(pin.uid, PIN_TYPE, name, pin.lat, pin.lon, "Pin from ${pin.by} via Telegram", PIN_STALE_S, now = now()))
                log("pin named \"$name\"")
            }
            return
        }
        if (m.sender.isSelf || m.edited) return
        val body = CotParse.clean(text, CotParse.MAX_REMARKS, multiline = true)
        if (body.isEmpty()) return
        val who = contact(m.sender)
        sink.send(CotBuild.chat(who.uid, who.callsign, body, "tg-${m.chatId}-${m.messageId}", now()))
    }

    private fun onCot1(m: TgMessage, payload: String) {
        val ev = try {
            CotParse.parse(Cot1Codec.decode(payload), now())
        } catch (e: CotException) {
            log("dropped a #cot1 message from ${m.sender.displayName}: ${e.message}")
            return
        }
        if (!ev.stale.isAfter(now())) return
        if (!dedup.isNew(ev.uid, ev.time.toEpochMilli())) return
        objectUids[ev.uid] = true
        sink.send(CotBuild.fromEvent(ev))
    }

    private fun onLive(m: TgMessage, c: TgContent.LiveLocation) {
        if (m.sender.isSelf) return
        if (c.lat.isNaN() || c.lon.isNaN() || c.lat !in -90.0..90.0 || c.lon !in -180.0..180.0) return
        val t = now()
        if (c.expiresInS <= 0) {
            if (live.remove(m.sender.userId) != null) log("${contact(m.sender).callsign} stopped sharing")
            return
        }
        val forever = c.expiresInS >= FOREVER_S
        val expires = if (forever) Instant.MAX else t.plusSeconds(c.expiresInS)
        val entry = live[m.sender.userId]
        if (entry == null) {
            val created = Live(c.lat, c.lon, c.accuracyM, c.headingDeg, expires, forever, t, Instant.MIN, m.sender)
            live[m.sender.userId] = created
            log("${contact(m.sender).callsign} is sharing a live location")
            emit(created, t)
        } else {
            entry.lat = c.lat
            entry.lon = c.lon
            entry.accuracyM = c.accuracyM
            entry.headingDeg = c.headingDeg
            entry.expiresAt = expires
            entry.forever = forever
            entry.lastUpdate = t
            entry.sender = m.sender
            emit(entry, t)
        }
    }

    private fun onPin(m: TgMessage, c: TgContent.Pin) {
        if (m.edited) return
        if (c.lat.isNaN() || c.lon.isNaN() || c.lat !in -90.0..90.0 || c.lon !in -180.0..180.0) return
        val by = contact(m.sender).callsign
        val ref = PinRef("TGPIN-${m.chatId}-${m.messageId}", c.lat, c.lon, by)
        pins[m.messageId] = ref
        objectUids[ref.uid] = true
        sink.send(CotBuild.marker(ref.uid, PIN_TYPE, "Pin ($by)", c.lat, c.lon, "Pin from $by via Telegram", PIN_STALE_S, now = now()))
    }

    /**
     * Call about every 30 s. Repeats the position of people who are still sharing but not moving,
     * so they do not go stale in ATAK, and forgets live locations that have ended.
     */
    @Synchronized
    fun tick() {
        val t = now()
        val it = live.entries.iterator()
        while (it.hasNext()) {
            val e = it.next().value
            if (!isActive(e, t)) {
                it.remove()
            } else if (!e.lastEmit.isAfter(t.minusSeconds(REPEAT_S))) {
                emit(e, t)
            }
        }
    }

    /**
     * A timed live location (a Telegram-only user) stays valid until Telegram's expiry. A "forever"
     * one comes from TAKgram, which updates at least once a minute, so silence means the phone is gone.
     */
    private fun isActive(e: Live, t: Instant): Boolean =
        t.isBefore(e.expiresAt) && (!e.forever || e.lastUpdate.isAfter(t.minusSeconds(FOREVER_SILENCE_S)))

    private fun emit(e: Live, t: Instant) {
        val who = contact(e.sender)
        val course = e.headingDeg.takeIf { it in 1..360 }?.toDouble()
        sink.send(CotBuild.position(who.uid, who.callsign, who.team, e.lat, e.lon, e.accuracyM, course, POSITION_STALE_S, t))
        e.lastEmit = t
    }

    /** The posted identity card if there is one; otherwise a Telegram-only contact such as "TG-Ann". */
    private fun contact(s: TgSender): Identity =
        (identities[s.userId] ?: telegramOnly(s)).also {
            if (!s.isSelf) {
                contactUids.add(it.uid)
                contactCallsigns.add(it.callsign)
            }
        }

    companion object {
        const val POSITION_STALE_S = 180L
        const val REPEAT_S = 60L
        const val FOREVER_SILENCE_S = 180L
        const val FOREVER_S = 0x7FFFFFFFL
        const val PIN_STALE_S = 24 * 3600L
        const val PIN_TYPE = "a-u-G"
        const val MAX_TEXT_CHARS = 16 * 1024
        private const val MAX_PINS = 200
        private const val MAX_OBJECTS = 2000

        fun telegramOnly(s: TgSender): Identity {
            val name = CotParse.clean(s.displayName, 40).ifEmpty { s.userId.toString() }
            return Identity("TG-${s.userId}", "TG-$name", Teams.TELEGRAM_ONLY)
        }
    }
}
