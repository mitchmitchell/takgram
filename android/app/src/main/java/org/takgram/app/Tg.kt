package org.takgram.app

import android.content.Context
import android.os.Build
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.util.Log
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import org.takgram.core.TgContent
import org.takgram.core.TgMessage
import org.takgram.core.TgSender
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The app's single TDLib client, and the only place that talks to Telegram.
 *
 * Messages from the linked chat are converted to plain [TgMessage] values and handed to
 * [onLinkedMessage]. Messages from every other chat are ignored completely. Nothing read from
 * Telegram is ever treated as an instruction.
 */
class Tg(private val ctx: Context) {

    fun interface Listener {
        fun onChange()
    }

    data class LiveRef(val chatId: Long, val messageId: Long) {
        /** Server-assigned message ids are multiples of 2^20; anything else is still a local, unsent message. */
        val confirmed: Boolean get() = messageId % (1L shl 20) == 0L
    }

    data class ChatInfo(val id: Long, val title: String, val kind: String, val usable: Boolean)

    private class UserInfo(val name: String, val isBot: Boolean)

    private val prefs = ctx.getSharedPreferences("takgram", Context.MODE_PRIVATE)
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val chats = ConcurrentHashMap<Long, TdApi.Chat>()
    private val users = ConcurrentHashMap<Long, UserInfo>()
    private val pendingSends = ConcurrentHashMap<Long, (Long?, String?) -> Unit>()
    private val log = ArrayDeque<String>()

    val testDc: Boolean = BuildConfig.TG_TEST_DC

    /** Receives every new or edited message of the linked chat. Set once, by the application. */
    @Volatile var onLinkedMessage: (TgMessage) -> Unit = {}

    @Volatile var authState: TdApi.AuthorizationState? = null
        private set
    @Volatile var selfUserId: Long = 0
        private set
    @Volatile var selfName: String = ""
        private set
    @Volatile var phone: String = ""
    @Volatile var live: LiveRef? = loadLive()
        private set

    val signedIn: Boolean get() = authState is TdApi.AuthorizationStateReady

    var linkedChatId: Long
        get() = prefs.getLong(key("linked_chat"), 0L)
        set(v) {
            prefs.edit().putLong(key("linked_chat"), v).apply()
            if (v != 0L) client.send(TdApi.OpenChat(v)) {}
            note("linked chat: ${chatTitle(v)}")
        }

    private val client: Client

    init {
        Client.execute(TdApi.SetLogVerbosityLevel(1))
        client = Client.create({ onUpdate(it) }, { Log.e(TAG, "update handler failed", it) }, {
            Log.e(TAG, "tdlib exception", it)
        })
    }

    // Keys are per data center so test-server state never mixes with production state.
    fun key(name: String) = if (testDc) "test.$name" else "prod.$name"

    fun addListener(l: Listener) = listeners.add(l)
    fun removeListener(l: Listener) = listeners.remove(l)
    private fun changed() = listeners.forEach { it.onChange() }

    fun logLines(): List<String> = synchronized(log) { log.toList() }

    fun note(line: String) {
        Log.i(TAG, line)
        synchronized(log) {
            log.addLast(line)
            while (log.size > 200) log.removeFirst()
        }
        changed()
    }

    /** Chats the user may link. Bots and channels are listed but marked unusable. */
    fun chatList(): List<ChatInfo> = chats.values.map { c ->
        when (val t = c.type) {
            is TdApi.ChatTypePrivate -> when {
                t.userId == selfUserId -> ChatInfo(c.id, c.title, "Saved Messages: not supported", false)
                users[t.userId]?.isBot == true -> ChatInfo(c.id, c.title, "bot: not supported", false)
                else -> ChatInfo(c.id, c.title, "person", true)
            }
            is TdApi.ChatTypeBasicGroup -> ChatInfo(c.id, c.title, "group", true)
            is TdApi.ChatTypeSupergroup ->
                if (t.isChannel) ChatInfo(c.id, c.title, "channel: not supported", false) else ChatInfo(c.id, c.title, "group", true)
            else -> ChatInfo(c.id, c.title, "not supported", false)
        }
    }.sortedWith(compareBy({ !it.usable }, { it.title.lowercase(Locale.ROOT) }))

    fun chatTitle(id: Long): String = chats[id]?.title ?: if (id == 0L) "none" else "chat $id"

    // ---- sign-in -------------------------------------------------------------------------------

    fun submitPhone(number: String) {
        phone = toInternational(number)
        note("sending phone number ${phone.take(4)}…${phone.takeLast(2)}")
        client.send(TdApi.SetAuthenticationPhoneNumber(phone, null)) { r -> report("phone", r) }
    }

    /**
     * Telegram needs the international form (+country code). A number typed without "+" is
     * completed using the SIM's country, falling back to the network's country, then to the US.
     */
    private fun toInternational(raw: String): String {
        val cleaned = raw.filter { it.isDigit() || it == '+' }
        if (cleaned.startsWith("+") || cleaned.isEmpty()) return cleaned
        val tm = ctx.getSystemService(TelephonyManager::class.java)
        val iso = listOf(tm?.simCountryIso, tm?.networkCountryIso).firstOrNull { !it.isNullOrEmpty() } ?: "US"
        return PhoneNumberUtils.formatNumberToE164(cleaned, iso.uppercase(Locale.ROOT)) ?: "+$cleaned"
    }

    fun submitCode(code: String) =
        client.send(TdApi.CheckAuthenticationCode(code.trim())) { r -> report("code", r) }

    /** Starts sign-in by QR code: another logged-in Telegram app scans the link as a QR code. */
    fun requestQr() {
        if (signedIn) {
            note("already signed in")
            return
        }
        client.send(TdApi.RequestQrCodeAuthentication(LongArray(0))) { r -> report("qr", r) }
    }

    /** Current QR sign-in link, or null. It changes about every 30 seconds. */
    @Volatile var qrLink: String? = null
        private set

    fun submitPassword(pw: String) =
        client.send(TdApi.CheckAuthenticationPassword(pw)) { r -> report("password", r) }

    fun logOut() = client.send(TdApi.LogOut()) { r -> report("sign out", r) }

    fun loadChats() {
        client.send(TdApi.LoadChats(TdApi.ChatListMain(), 100)) { r ->
            if (r is TdApi.Error && r.code != 404) report("loadChats", r)
            changed()
        }
    }

    /**
     * Re-reads identity cards already posted in the chat, oldest first, so teammates who posted
     * theirs before this app started are shown under their TAK identity.
     */
    fun loadIdentityCards(chatId: Long) {
        if (chatId == 0L || !signedIn) return
        val search = TdApi.SearchChatMessages(chatId, null, IDENTITY_TAG, null, 0, 0, 100, TdApi.SearchMessagesFilterEmpty())
        client.send(search) { r ->
            if (r is TdApi.FoundChatMessages) {
                for (m in r.messages.reversed()) if (isLinked(m.chatId)) deliver(m, edited = true)
            }
        }
    }

    // ---- sending ---------------------------------------------------------------------------------

    /**
     * Sends a text message. [done] gets the server's message id once Telegram has confirmed it, or
     * null and the reason if the send failed.
     */
    fun sendText(chatId: Long, text: String, done: (Long?, String?) -> Unit = { _, _ -> }) {
        val content = TdApi.InputMessageText(TdApi.FormattedText(text, emptyArray()), null, false)
        send(chatId, content, done)
    }

    fun editText(chatId: Long, messageId: Long, text: String, done: (String?) -> Unit) {
        val content = TdApi.InputMessageText(TdApi.FormattedText(text, emptyArray()), null, false)
        client.send(TdApi.EditMessageText(chatId, messageId, null, content)) { r ->
            done((r as? TdApi.Error)?.let { "${it.code} ${it.message}" })
        }
    }

    private fun send(chatId: Long, content: TdApi.InputMessageContent, done: (Long?, String?) -> Unit) {
        client.send(TdApi.SendMessage(chatId, null, null, null, null, content)) { r ->
            if (r is TdApi.Message) {
                pendingSends[r.id] = done
            } else {
                done(null, (r as? TdApi.Error)?.let { "${it.code} ${it.message}" } ?: "unknown error")
            }
        }
    }

    /** Why the last live-location send failed, or null. Cleared when a new send starts. */
    @Volatile var liveError: String? = null
        private set

    /** Forgets the current live-location message without touching Telegram (e.g. it can no longer be edited). */
    fun forgetLive() = setLive(null)

    fun startLive(chatId: Long, fix: Fix) {
        liveError = null
        setLive(LiveRef(chatId, PENDING_ID))
        send(chatId, TdApi.InputMessageLiveLocation(fix.toLive())) { id, error ->
            if (id != null) {
                setLive(LiveRef(chatId, id))
                note("sharing position in ${chatTitle(chatId)}")
            } else {
                liveError = error
                setLive(null)
            }
        }
    }

    fun editLive(fix: Fix, done: (TdApi.Object) -> Unit) {
        val ref = live ?: return
        client.send(TdApi.EditMessageLiveLocation(ref.chatId, ref.messageId, null, fix.toLive())) { r -> done(r) }
    }

    fun stopLive() {
        val ref = live ?: return
        if (ref.confirmed) {
            client.send(TdApi.EditMessageLiveLocation(ref.chatId, ref.messageId, null, null)) { r -> report("stop sharing", r) }
        }
        setLive(null)
    }

    private fun setLive(ref: LiveRef?) {
        live = ref
        prefs.edit().apply {
            if (ref == null) remove(key("live_chat")).remove(key("live_msg"))
            else putLong(key("live_chat"), ref.chatId).putLong(key("live_msg"), ref.messageId)
        }.apply()
        changed()
    }

    private fun loadLive(): LiveRef? {
        val c = prefs.getLong(key("live_chat"), 0L)
        val m = prefs.getLong(key("live_msg"), 0L)
        // A saved reference that the server never confirmed points at nothing; drop it.
        return if (c != 0L && m != 0L) LiveRef(c, m).takeIf { it.confirmed } else null
    }

    private fun report(what: String, r: TdApi.Object) {
        if (r is TdApi.Error) note("$what failed: ${r.code} ${r.message}") else Log.d(TAG, "$what ok")
    }

    // ---- updates ---------------------------------------------------------------------------------

    private fun onUpdate(o: TdApi.Object) {
        when (o) {
            is TdApi.UpdateAuthorizationState -> onAuth(o.authorizationState)
            is TdApi.UpdateUser -> {
                val u = o.user
                val name = listOf(u.firstName, u.lastName).filter { it.isNotBlank() }.joinToString(" ")
                users[u.id] = UserInfo(name, u.type is TdApi.UserTypeBot)
            }
            is TdApi.UpdateNewChat -> chats[o.chat.id] = o.chat
            is TdApi.UpdateChatTitle -> chats[o.chatId]?.title = o.title
            is TdApi.UpdateNewMessage -> if (isLinked(o.message.chatId)) deliver(o.message, edited = false)
            is TdApi.UpdateMessageContent -> if (isLinked(o.chatId) && o.messageId != live?.messageId) {
                // The update carries no sender, so fetch the whole message.
                client.send(TdApi.GetMessage(o.chatId, o.messageId)) { r ->
                    if (r is TdApi.Message) deliver(r, edited = true)
                }
            }
            is TdApi.UpdateMessageSendFailed -> {
                note("message send failed: ${o.error.code} ${o.error.message}")
                pendingSends.remove(o.oldMessageId)?.invoke(null, "${o.error.code} ${o.error.message}")
            }
            is TdApi.UpdateMessageSendSucceeded -> pendingSends.remove(o.oldMessageId)?.invoke(o.message.id, null)
            else -> Unit
        }
    }

    private fun isLinked(chatId: Long): Boolean = chatId != 0L && chatId == linkedChatId

    private fun deliver(m: TdApi.Message, edited: Boolean) {
        val sender = when (val s = m.senderId) {
            is TdApi.MessageSenderUser -> {
                val u = users[s.userId]
                TgSender(s.userId, u?.name.orEmpty(), u?.isBot ?: false, m.isOutgoing)
            }
            else -> TgSender(0, "", isBot = false, isSelf = false)
        }
        val content: TgContent = when (val c = m.content) {
            is TdApi.MessageText -> TgContent.Text(c.text.text)
            is TdApi.MessagePhoto -> TgContent.Text(c.caption.text)
            is TdApi.MessageDocument -> TgContent.Text(c.caption.text)
            is TdApi.MessageLiveLocation -> TgContent.LiveLocation(
                c.location.location.latitude, c.location.location.longitude,
                c.location.location.horizontalAccuracy, c.location.heading, c.expiresIn.toLong(),
            )
            is TdApi.MessageLocation -> TgContent.Pin(c.location.latitude, c.location.longitude)
            else -> TgContent.Other
        }
        val reply = (m.replyTo as? TdApi.MessageReplyToMessage)?.takeIf { it.chatId == m.chatId }?.messageId ?: 0L
        try {
            onLinkedMessage(TgMessage(m.chatId, m.id, sender, content, reply, edited))
        } catch (e: Exception) {
            Log.e(TAG, "bridge failed on a message", e)
        }
    }

    private fun onAuth(s: TdApi.AuthorizationState) {
        authState = s
        when (s) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                val dir = File(ctx.filesDir, if (testDc) "tdlib-test" else "tdlib")
                val fresh = !File(dir, "db").exists()
                val dbKey = DbKey.load(prefs, key("dbkey"), createIfMissing = fresh)
                if (dbKey.isEmpty() && !fresh) {
                    Log.i(TAG, "database predates encryption; it stays unencrypted until the next sign-in")
                }
                val p = TdApi.SetTdlibParameters(
                    testDc, File(dir, "db").absolutePath, File(dir, "files").absolutePath, dbKey,
                    false, true, true, false,
                    BuildConfig.TG_API_ID, BuildConfig.TG_API_HASH,
                    "en", Build.MODEL ?: "Android", "Android ${Build.VERSION.RELEASE}", BuildConfig.VERSION_NAME,
                )
                client.send(p) { r ->
                    if (r is TdApi.Error) {
                        note(
                            "Could not open the Telegram database (${r.code} ${r.message}). " +
                                "Clear TAKgram's data in Android settings and sign in again.",
                        )
                    }
                }
            }
            is TdApi.AuthorizationStateWaitCode ->
                note("Telegram is waiting for a login code. If none arrives, use QR sign-in.")
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> {
                qrLink = s.link
                // The link is a short-lived login token; only debug builds write it to the log.
                if (BuildConfig.DEBUG) Log.i(QR_TAG, s.link)
            }
            is TdApi.AuthorizationStateWaitPassword -> note("Enter your two-step verification password.")
            is TdApi.AuthorizationStateWaitRegistration ->
                note("This phone number has no Telegram account; sign up in Telegram first.")
            is TdApi.AuthorizationStateReady -> {
                qrLink = null
                client.send(TdApi.GetMe()) { r ->
                    if (r is TdApi.User) {
                        selfUserId = r.id
                        selfName = r.firstName
                        changed()
                    }
                }
                note("Signed in (${if (testDc) "test" else "production"} servers).")
                loadChats()
                linkedChatId.takeIf { it != 0L }?.let { client.send(TdApi.OpenChat(it)) {} }
            }
            is TdApi.AuthorizationStateLoggingOut, is TdApi.AuthorizationStateClosed -> {
                qrLink = null
                setLive(null)
            }
            else -> Unit
        }
        changed()
    }

    companion object {
        const val TAG = "TAKgram"
        const val QR_TAG = "TAKgramQR"
        const val FOREVER = 0x7FFFFFFF
        private const val IDENTITY_TAG = "#takid1"

        /** Placeholder id while a send is in flight; not a multiple of 2^20, so it reads as unconfirmed. */
        private const val PENDING_ID = 1L
    }
}

/** One position fix, already validated. */
data class Fix(val lat: Double, val lon: Double, val accuracyM: Double, val headingDeg: Int) {
    init {
        require(lat in -90.0..90.0 && lon in -180.0..180.0) { "coordinates out of range" }
    }

    fun toLive(): TdApi.LiveLocation = TdApi.LiveLocation(
        TdApi.Location(lat, lon, accuracyM.coerceIn(0.0, 1500.0)),
        Tg.FOREVER,
        headingDeg.coerceIn(0, 360),
        0,
    )
}
