package org.takgram.app

import android.content.Context
import android.os.Build
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.util.Log
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The app's single TDLib client.
 *
 * A0 spike version: sign-in, chat list, one linked chat, text and live-location messages.
 * Every update from chats other than the linked chat is ignored. Message content is logged
 * as data only and is never interpreted as a command.
 */
class Tg(private val ctx: Context) {

    fun interface Listener {
        fun onChange()
    }

    data class LiveRef(val chatId: Long, val messageId: Long) {
        /** Server-assigned message ids are multiples of 2^20; anything else is still a local, unsent message. */
        val confirmed: Boolean get() = messageId % (1L shl 20) == 0L
    }

    private val prefs = ctx.getSharedPreferences("takgram", Context.MODE_PRIVATE)
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val chats = ConcurrentHashMap<Long, String>()
    private val log = ArrayDeque<String>()

    val testDc: Boolean = BuildConfig.TG_TEST_DC

    @Volatile var authState: TdApi.AuthorizationState? = null
        private set
    @Volatile var phone: String = ""
    @Volatile var live: LiveRef? = loadLive()
        private set

    var linkedChatId: Long
        get() = prefs.getLong(key("linked_chat"), 0L)
        set(v) {
            prefs.edit().putLong(key("linked_chat"), v).apply()
            if (v != 0L) {
                client.send(TdApi.OpenChat(v)) {}
            }
            note("linked chat: ${chatTitle(v)} ($v)")
        }

    private val client: Client

    init {
        Client.execute(TdApi.SetLogVerbosityLevel(1))
        client = Client.create({ onUpdate(it) }, { Log.e(TAG, "update handler failed", it) }, {
            Log.e(TAG, "tdlib exception", it)
        })
    }

    // Keys are per data center so test-server state never mixes with production state.
    private fun key(name: String) = if (testDc) "test.$name" else "prod.$name"

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

    fun chatList(): List<Pair<Long, String>> = chats.entries.map { it.key to it.value }.sortedBy { it.second }

    fun chatTitle(id: Long): String = chats[id] ?: "?"

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

    /** Asks Telegram to send the code again by its next delivery method (for example SMS). */
    fun resendCode() = client.send(TdApi.ResendAuthenticationCode(null)) { r -> report("resend", r) }

    /** Starts sign-in by QR code: another logged-in Telegram app scans the link as a QR code. */
    fun requestQr() = client.send(TdApi.RequestQrCodeAuthentication(LongArray(0))) { r -> report("qr", r) }

    /** Current QR sign-in link, or null. It changes about every 30 seconds. */
    @Volatile var qrLink: String? = null
        private set

    fun submitPassword(pw: String) =
        client.send(TdApi.CheckAuthenticationPassword(pw)) { r -> report("password", r) }

    fun logOut() = client.send(TdApi.LogOut()) { r -> report("logout", r) }

    fun loadChats() {
        client.send(TdApi.LoadChats(TdApi.ChatListMain(), 100)) { r ->
            if (r is TdApi.Error && r.code != 404) report("loadChats", r)
            changed()
        }
    }

    /** A0 helper for test servers: makes an empty supergroup owned by this account and links it. */
    fun createTestGroup(title: String) {
        val fn = TdApi.CreateNewSupergroupChat(title, false, false, "TAKgram test group", null, 0, false)
        client.send(fn) { r ->
            if (r is TdApi.Chat) {
                chats[r.id] = r.title
                linkedChatId = r.id
            } else report("createGroup", r)
        }
    }

    // ---- sending ---------------------------------------------------------------------------------

    fun sendText(chatId: Long, text: String) {
        val content = TdApi.InputMessageText(TdApi.FormattedText(text, emptyArray()), null, false)
        client.send(TdApi.SendMessage(chatId, null, null, null, null, content)) { r -> report("sendText", r) }
    }

    /** Why the last live-location send failed, or null. Cleared when a new send starts. */
    @Volatile var liveError: String? = null
        private set

    /** Forgets the current live-location message without touching Telegram (e.g. it can no longer be edited). */
    fun forgetLive() = setLive(null)

    fun startLive(chatId: Long, fix: Fix, done: (Boolean) -> Unit) {
        liveError = null
        val content = TdApi.InputMessageLiveLocation(fix.toLive())
        client.send(TdApi.SendMessage(chatId, null, null, null, null, content)) { r ->
            if (r is TdApi.Message) {
                setLive(LiveRef(chatId, r.id))
                note("live location started (temporary id ${r.id})")
                done(true)
            } else {
                report("startLive", r)
                liveError = (r as? TdApi.Error)?.let { "${it.code} ${it.message}" } ?: "unknown error"
                done(false)
            }
        }
    }

    fun editLive(fix: Fix, done: (TdApi.Object) -> Unit) {
        val ref = live ?: return
        client.send(TdApi.EditMessageLiveLocation(ref.chatId, ref.messageId, null, fix.toLive())) { r ->
            if (r is TdApi.Error) report("editLive", r)
            done(r)
        }
    }

    fun stopLive() {
        val ref = live ?: return
        client.send(TdApi.EditMessageLiveLocation(ref.chatId, ref.messageId, null, null)) { r ->
            report("stopLive", r)
        }
        setLive(null)
    }

    private fun setLive(ref: LiveRef?) {
        live = ref
        prefs.edit().apply {
            if (ref == null) remove(key("live_chat")).remove(key("live_msg"))
            else putLong(key("live_chat"), ref.chatId).putLong(key("live_msg"), ref.messageId)
        }.apply()
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
            is TdApi.UpdateNewChat -> chats[o.chat.id] = o.chat.title
            is TdApi.UpdateChatTitle -> chats[o.chatId] = o.title
            is TdApi.UpdateNewMessage -> onMessage(o.message, "new")
            is TdApi.UpdateMessageContent -> if (o.chatId == linkedChatId && o.chatId != 0L) {
                note("edit ${o.messageId}: ${describe(o.newContent)}")
            }
            is TdApi.UpdateMessageSendFailed -> {
                note("message send failed: ${o.error.code} ${o.error.message}")
                val ref = live
                if (ref != null && ref.chatId == o.message.chatId && ref.messageId == o.oldMessageId) {
                    liveError = "${o.error.code} ${o.error.message}"
                    setLive(null)
                }
            }
            is TdApi.UpdateMessageSendSucceeded -> {
                val ref = live
                if (ref != null && ref.chatId == o.message.chatId && ref.messageId == o.oldMessageId) {
                    setLive(LiveRef(ref.chatId, o.message.id))
                    note("live location confirmed by server, id ${o.message.id}")
                }
            }
            else -> Unit
        }
    }

    private fun onMessage(m: TdApi.Message, how: String) {
        val linked = linkedChatId
        if (linked == 0L || m.chatId != linked) return // every other chat is ignored completely
        val from = when (val s = m.senderId) {
            is TdApi.MessageSenderUser -> "user ${s.userId}"
            is TdApi.MessageSenderChat -> "chat ${s.chatId}"
            else -> "?"
        }
        note("$how ${m.id} from $from${if (m.isOutgoing) " (me)" else ""}: ${describe(m.content)}")
    }

    private fun describe(c: TdApi.MessageContent): String = when (c) {
        is TdApi.MessageText -> "text \"${c.text.text.take(200)}\""
        is TdApi.MessageLocation -> "location ${c.location.latitude},${c.location.longitude}"
        is TdApi.MessageLiveLocation ->
            "live ${c.location.location.latitude},${c.location.location.longitude} " +
                "period=${c.location.livePeriod} expiresIn=${c.expiresIn}"
        else -> c.javaClass.simpleName
    }

    private fun onAuth(s: TdApi.AuthorizationState) {
        authState = s
        when (s) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                val dir = File(ctx.filesDir, if (testDc) "tdlib-test" else "tdlib")
                // A0: database not yet encrypted. A1 adds a Keystore-wrapped databaseEncryptionKey.
                val p = TdApi.SetTdlibParameters(
                    testDc, File(dir, "db").absolutePath, File(dir, "files").absolutePath, ByteArray(0),
                    false, true, true, false,
                    BuildConfig.TG_API_ID, BuildConfig.TG_API_HASH,
                    "en", Build.MODEL ?: "Android", "Android ${Build.VERSION.RELEASE}", BuildConfig.VERSION_NAME,
                )
                client.send(p) { r -> report("parameters", r) }
            }
            is TdApi.AuthorizationStateWaitCode -> {
                note(
                    "waiting for login code (${s.codeInfo.type.javaClass.simpleName}, ${codeLength(s.codeInfo.type)} digits); " +
                        "next=${s.codeInfo.nextType?.javaClass?.simpleName ?: "none"} after ${s.codeInfo.timeout}s",
                )
                testCode(codeLength(s.codeInfo.type))?.let { submitCode(it) }
            }
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> {
                qrLink = s.link
                note("QR sign-in link updated")
                // The link is a short-lived login token; only debug builds write it to the log.
                if (BuildConfig.DEBUG) Log.i(QR_TAG, s.link)
            }
            is TdApi.AuthorizationStateWaitRegistration -> {
                if (testDc) {
                    client.send(TdApi.RegisterUser("TAKgram", "Test", true)) { r -> report("register", r) }
                } else note("this phone number has no Telegram account; sign up in Telegram first")
            }
            is TdApi.AuthorizationStateReady -> {
                qrLink = null
                note("SIGNED IN (${if (testDc) "test" else "production"} servers). Next: choose the linked chat.")
                loadChats()
                linkedChatId.takeIf { it != 0L }?.let { client.send(TdApi.OpenChat(it)) {} }
            }
            else -> note("auth: ${s.javaClass.simpleName.removePrefix("AuthorizationState")}")
        }
        changed()
    }

    private fun codeLength(t: TdApi.AuthenticationCodeType): Int = when (t) {
        is TdApi.AuthenticationCodeTypeSms -> t.length
        is TdApi.AuthenticationCodeTypeTelegramMessage -> t.length
        else -> 5
    }

    /**
     * Telegram's test accounts 99966XYYYY always receive the DC digit X repeated as the code. The
     * docs say five digits; the servers now ask for the length given in codeInfo, so use that.
     */
    private fun testCode(length: Int): String? {
        if (!testDc) return null
        val digits = phone.filter { it.isDigit() }
        if (digits.length != 10 || !digits.startsWith("99966")) return null
        val dc = digits[5]
        return if (dc in '1'..'3') dc.toString().repeat(length.coerceIn(5, 8)) else null
    }

    companion object {
        const val TAG = "TAKgram"
        const val QR_TAG = "TAKgramQR"
        const val FOREVER = 0x7FFFFFFF
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
