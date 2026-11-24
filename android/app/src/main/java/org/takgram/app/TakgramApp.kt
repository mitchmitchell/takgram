package org.takgram.app

import android.app.Application
import android.content.Context
import org.takgram.core.Bridge
import org.takgram.core.Caption
import org.takgram.core.ConnectionPackage
import org.takgram.core.Cot1Codec
import org.takgram.core.CotBuild
import org.takgram.core.CotEvent
import org.takgram.core.CotParse
import org.takgram.core.Dedup
import org.takgram.core.Identity
import org.takgram.core.IdentityCard
import org.takgram.core.OutAction
import org.takgram.core.OutboundPolicy
import org.takgram.core.Teams
import java.util.Locale
import java.util.concurrent.Executors

class TakgramApp : Application() {
    lateinit var tg: Tg
        private set
    lateinit var bridge: Bridge
        private set

    /** True while the bridge service runs. Messages are only passed to ATAK while it is on. */
    @Volatile var bridgeOn = false

    /** How many ATAK connections the local server has. */
    @Volatile var atakConnections = 0
        private set

    lateinit var takServer: TakServer
        private set
    private lateinit var outbox: Outbox
    private lateinit var policy: OutboundPolicy
    private val postedVersions = Dedup()

    private val io = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("takgram", Context.MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        tg = Tg(this)
        bridge = Bridge(
            sink = { xml ->
                io.execute {
                    try {
                        CotUdpSender.send(xml)
                    } catch (e: Exception) {
                        tg.note("could not send to ATAK: ${e.message}")
                    }
                }
            },
            log = { tg.note(it) },
        )
        tg.onLinkedMessage = { m -> if (bridgeOn) bridge.onMessage(m) }
        outbox = Outbox(tg, prefs)
        policy = OutboundPolicy(bridge::cameFromTelegram, bridge::isTelegramContact, bridge::isTelegramCallsign)
        takServer = TakServer(
            port = ConnectionPackage.PORT,
            onEvent = ::onAtakEvent,
            onClients = { n ->
                if (n != atakConnections) tg.note(if (n > 0) "ATAK connected to TAKgram" else "ATAK disconnected from TAKgram")
                atakConnections = n
            },
            log = { tg.note(it) },
        )
    }

    /** An event the user's own ATAK sent to the local server. */
    private fun onAtakEvent(ev: CotEvent) {
        if (!bridgeOn) return
        val chat = tg.linkedChatId
        val canPost = chat != 0L && tg.signedIn
        when (val action = policy.decide(ev)) {
            is OutAction.SelfSeen -> if (action.identity != identity) {
                // ATAK is the authority on the user's uid, callsign and team.
                identity = action.identity
                tg.note("identity from ATAK: ${action.identity.callsign} (${action.identity.team})")
                postIdentity()
            }
            is OutAction.Card -> if (postedVersions.isNew(ev.uid, ev.time.toEpochMilli())) {
                val title = Caption.lines(ev).first()
                if (canPost) {
                    outbox.card(chat, ev)
                    tg.note("posting to the chat: $title")
                } else if (BuildConfig.DEBUG) tg.note("would post (no chat linked): $title")
            }
            is OutAction.ChatText ->
                if (canPost) outbox.text(chat, action.text)
                else if (BuildConfig.DEBUG) tg.note("would post chat (no chat linked): ${action.text.take(80)}")
            OutAction.Ignore -> Unit
        }
    }

    // ---- the user's TAK identity -------------------------------------------------------------------

    /** The callsign, team and TAK uid this user is known by. Defaults until the user sets them. */
    var identity: Identity
        get() = Identity(
            uid = prefs.getString("id_uid", null).takeUnless { it.isNullOrBlank() } ?: "TAKGRAM-${tg.selfUserId}",
            callsign = prefs.getString("id_callsign", null).takeUnless { it.isNullOrBlank() }
                ?: tg.selfName.ifBlank { "TAKgram" },
            team = prefs.getString("id_team", null)?.takeIf { Teams.valid(it) } ?: Teams.DEFAULT,
        )
        set(v) {
            prefs.edit()
                .putString("id_uid", CotParse.clean(v.uid, CotParse.MAX_UID))
                .putString("id_callsign", CotParse.clean(v.callsign, CotParse.MAX_NAME))
                .putString("id_team", v.team)
                .apply()
        }

    /**
     * Posts this user's identity card in the linked chat, or edits the one already there, so
     * teammates' apps show one icon for this person whether seen over Wi-Fi or over Telegram.
     */
    fun postIdentity() {
        val chat = tg.linkedChatId
        if (chat == 0L || !tg.signedIn) return
        val text = IdentityCard.text(identity)
        val key = tg.key("card_$chat")
        if (prefs.getString(tg.key("card_text_$chat"), null) == text) return
        val existing = prefs.getLong(key, 0L)
        val remember = { id: Long -> prefs.edit().putLong(key, id).putString(tg.key("card_text_$chat"), text).apply() }
        val sendNew = {
            tg.sendText(chat, text) { id, error ->
                if (id != null) {
                    remember(id)
                    tg.note("identity card posted: ${identity.callsign} (${identity.team})")
                } else tg.note("identity card not posted: $error")
            }
        }
        if (existing == 0L) sendNew() else tg.editText(chat, existing, text) { error ->
            if (error == null) {
                remember(existing)
                tg.note("identity card updated: ${identity.callsign} (${identity.team})")
            } else sendNew() // deleted, or too old to edit
        }
    }

    /** Posts a test marker as a readable message ending in a #cot1 line, for the two-phone check. */
    fun postTestMarker(lat: Double, lon: Double) {
        val chat = tg.linkedChatId
        if (chat == 0L || !tg.signedIn) return
        val me = identity
        val name = "Test marker (${me.callsign})"
        val xml = CotBuild.marker("takgram-test-${tg.selfUserId}", "a-u-G", name, lat, lon, "Test marker posted by TAKgram")
        val text = "$name\nUnknown ground track\n" +
            String.format(Locale.ROOT, "%.5f, %.5f", lat, lon) + "\n" + Cot1Codec.encode(xml)
        tg.sendText(chat, text) { id, error ->
            tg.note(if (id != null) "test marker posted" else "test marker not posted: $error")
        }
    }
}
