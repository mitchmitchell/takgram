package org.takgram.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.takgram.core.Bridge
import org.takgram.core.Cot1Codec
import org.takgram.core.CotBuild
import org.takgram.core.Identity
import org.takgram.core.IdentityCard
import org.takgram.core.Teams
import org.takgram.core.TgContent
import org.takgram.core.TgMessage
import org.takgram.core.TgSender
import java.util.concurrent.Executors

/**
 * The one screen of the test builds: sign in, set the TAK identity, pick the linked chat, and
 * switch the bridge and position sharing on or off. Plain framework views keep dependencies few.
 *
 * Debug builds also accept actions as intent extras so emulator tests can run unattended, e.g.
 *   adb shell am start -n org.takgram.app/.MainActivity --es bridge on --es inject live
 * Release builds ignore them.
 */
class MainActivity : Activity() {

    private lateinit var app: TakgramApp
    private lateinit var tg: Tg
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var signInBox: LinearLayout
    private lateinit var mainBox: LinearLayout
    private lateinit var qrView: ImageView
    private lateinit var qrHelp: TextView
    private lateinit var callsign: EditText
    private lateinit var uid: EditText
    private lateinit var teamButton: Button
    private lateinit var bridgeButton: Button
    private lateinit var shareButton: Button
    private var team = Teams.DEFAULT
    private var identityShown = false
    private var shownQr: String? = null
    private val io = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("takgram", Context.MODE_PRIVATE) }
    private val listener = Tg.Listener { runOnUiThread { refresh() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as TakgramApp
        tg = app.tg
        setContentView(buildUi())
        tg.addListener(listener)
        refresh()
        // The bridge was on when the app last ran: bring it back now that the app is visible.
        if (prefs.getBoolean(BridgeService.PREF_BRIDGE, false) && !app.bridgeOn) BridgeService.send(this, BridgeService.ACTION_START)
        handleDebugIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDebugIntent(intent)
    }

    override fun onDestroy() {
        tg.removeListener(listener)
        io.shutdown()
        super.onDestroy()
    }

    // ---- layout ----------------------------------------------------------------------------------

    private fun buildUi(): View {
        val col = column().apply { setPadding(32, 32, 32, 32) }
        col.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 22f
        })
        status = TextView(this).also { col.addView(it) }

        signInBox = column().also { col.addView(it) }
        heading(signInBox, "Sign in to Telegram")
        button(signInBox, "Sign in with QR code") { tg.requestQr() }
        qrHelp = TextView(this).apply {
            text = "On another device signed in to this Telegram account: Settings > Devices > " +
                "Link Desktop Device, then scan this code. It refreshes by itself."
            visibility = View.GONE
            signInBox.addView(this)
        }
        qrView = ImageView(this).apply {
            visibility = View.GONE
            signInBox.addView(this, LinearLayout.LayoutParams(QR_PX, QR_PX).apply { gravity = Gravity.CENTER_HORIZONTAL })
        }
        heading(signInBox, "Or with a phone number (the code may not arrive)")
        val phone = field(signInBox, "Phone number, e.g. +1 972 555 0100", InputType.TYPE_CLASS_PHONE)
        button(signInBox, "Send phone number") { tg.submitPhone(phone.text.toString()) }
        val code = field(signInBox, "Login code", InputType.TYPE_CLASS_NUMBER)
        button(signInBox, "Send code") { tg.submitCode(code.text.toString()) }
        val pw = field(signInBox, "Two-step password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        button(signInBox, "Send password") {
            tg.submitPassword(pw.text.toString())
            pw.setText("")
        }

        mainBox = column().also { col.addView(it) }
        heading(mainBox, "Your TAK identity")
        label(mainBox, "Callsign (the same one you use in ATAK)")
        callsign = field(mainBox, "e.g. ALPHA-2", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        teamButton = button(mainBox, "Team") { pickTeam() }
        label(mainBox, "ATAK device UID. Leave blank unless you know it; it starts with ANDROID-")
        uid = field(mainBox, "blank", InputType.TYPE_CLASS_TEXT)
        button(mainBox, "Save identity and post it to the chat") { saveIdentity() }

        heading(mainBox, "Bridge")
        button(mainBox, "Choose linked chat") { pickChat() }
        bridgeButton = button(mainBox, "") { toggleBridge() }
        shareButton = button(mainBox, "") { toggleSharing() }

        heading(mainBox, "Tests")
        button(mainBox, "Send a test marker to ATAK") { sendTestCot() }
        button(mainBox, "Post a test marker to the chat") { app.postTestMarker(TEST_LAT, TEST_LON) }
        button(mainBox, "Sign out of Telegram") { confirmSignOut() }

        heading(col, "Log (newest first)")
        logView = TextView(this).apply { setTextIsSelectable(true) }.also { col.addView(it) }
        return ScrollView(this).apply {
            addView(col)
            // Android 15+ draws apps edge to edge: keep the content clear of the system bars and cutout.
            setOnApplyWindowInsetsListener { v, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                    v.setPadding(b.left, b.top, b.right, b.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                }
                insets
            }
        }
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun heading(parent: LinearLayout, label: String) = TextView(this).apply {
        text = label
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 28, 0, 4)
        parent.addView(this)
    }

    private fun label(parent: LinearLayout, text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setPadding(8, 12, 0, 0)
        parent.addView(this)
    }

    private fun field(parent: LinearLayout, hint: String, type: Int) = EditText(this).apply {
        this.hint = hint
        inputType = type
        parent.addView(this)
    }

    private fun button(parent: LinearLayout, label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
        parent.addView(this)
    }

    private fun refresh() {
        val auth = tg.authState?.javaClass?.simpleName?.removePrefix("AuthorizationState") ?: "starting"
        val signedIn = tg.signedIn
        val sharing = tg.live != null
        val seen = app.bridge.activeCallsigns()
        status.text = buildString {
            append(if (tg.testDc) "Telegram TEST servers\n" else "")
            append("Telegram: ").append(if (signedIn) "signed in" else signInText(auth)).append('\n')
            append("Linked chat: ").append(tg.chatTitle(tg.linkedChatId)).append('\n')
            append("Bridge to ATAK: ").append(if (app.bridgeOn) "on" else "off").append('\n')
            append("Sharing my position: ").append(if (sharing) "on" else "off").append('\n')
            append("Sharing with me: ").append(if (seen.isEmpty()) "nobody" else seen.joinToString())
        }
        signInBox.visibility = if (signedIn) View.GONE else View.VISIBLE
        mainBox.visibility = if (signedIn) View.VISIBLE else View.GONE
        bridgeButton.text = if (app.bridgeOn) "Turn bridge off" else "Turn bridge on"
        shareButton.text = if (sharing) "Stop sharing my position" else "Share my position"
        if (signedIn && !identityShown && tg.selfUserId != 0L) {
            identityShown = true
            val id = app.identity
            callsign.setText(id.callsign)
            uid.setText(if (id.uid.startsWith("TAKGRAM-")) "" else id.uid)
            team = id.team
        }
        teamButton.text = "Team colour: $team"
        logView.text = tg.logLines().asReversed().joinToString("\n")
        showQr(if (auth == "WaitOtherDeviceConfirmation") tg.qrLink else null)
    }

    private fun signInText(auth: String) = when (auth) {
        "WaitPhoneNumber" -> "not signed in"
        "WaitOtherDeviceConfirmation" -> "waiting for the QR code to be scanned"
        "WaitCode" -> "waiting for the login code"
        "WaitPassword" -> "waiting for your two-step password"
        else -> auth
    }

    private fun showQr(link: String?) {
        if (link == shownQr) return
        shownQr = link
        val show = if (link == null) View.GONE else View.VISIBLE
        qrView.visibility = show
        qrHelp.visibility = show
        if (link == null) {
            qrView.setImageDrawable(null)
            return
        }
        try {
            val m = QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, QR_PX, QR_PX, mapOf(EncodeHintType.MARGIN to 4))
            val px = IntArray(QR_PX * QR_PX) { i -> if (m.get(i % QR_PX, i / QR_PX)) Color.BLACK else Color.WHITE }
            qrView.setImageBitmap(Bitmap.createBitmap(px, QR_PX, QR_PX, Bitmap.Config.ARGB_8888))
        } catch (e: Exception) {
            tg.note("could not draw QR code: $e")
        }
    }

    // ---- actions ---------------------------------------------------------------------------------

    private fun pickTeam() {
        AlertDialog.Builder(this)
            .setTitle("Team colour")
            .setItems(Teams.ALL.toTypedArray()) { _, i ->
                team = Teams.ALL[i]
                refresh()
            }
            .show()
    }

    private fun saveIdentity() {
        val cs = callsign.text.toString().trim()
        if (cs.isEmpty()) {
            tg.note("enter a callsign first")
            return
        }
        val before = app.identity
        app.identity = Identity(uid.text.toString().trim(), cs, team)
        when {
            app.identity == before -> tg.note("identity unchanged: $cs ($team)")
            tg.linkedChatId == 0L -> tg.note("identity saved: $cs ($team). Choose a linked chat to post it.")
            else -> {
                tg.note("identity saved: $cs ($team)")
                app.postIdentity()
            }
        }
    }

    private fun pickChat() {
        tg.loadChats()
        val list = tg.chatList()
        if (list.isEmpty()) {
            tg.note("no chats loaded yet; try again in a moment")
            return
        }
        val labels = list.map { "${it.title}  (${it.kind})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Link which chat? TAKgram reads and posts only there.")
            .setItems(labels) { _, i ->
                val c = list[i]
                if (!c.usable) {
                    tg.note("${c.title} cannot be linked (${c.kind}). Choose a person or a group.")
                } else {
                    tg.linkedChatId = c.id
                    if (app.bridgeOn) app.postIdentity()
                }
            }
            .show()
    }

    private fun toggleBridge() {
        if (app.bridgeOn) {
            BridgeService.send(this, BridgeService.ACTION_STOP)
            return
        }
        if (tg.linkedChatId == 0L) {
            tg.note("choose a linked chat first")
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_BRIDGE)
            return
        }
        BridgeService.send(this, BridgeService.ACTION_START)
    }

    private fun toggleSharing() {
        if (tg.live != null) {
            BridgeService.send(this, BridgeService.ACTION_SHARE_OFF)
            return
        }
        if (tg.linkedChatId == 0L) {
            tg.note("choose a linked chat first")
            return
        }
        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.contains(Manifest.permission.ACCESS_FINE_LOCATION)) {
            requestPermissions(need.toTypedArray(), REQ_SHARE)
            return
        }
        BridgeService.send(this, BridgeService.ACTION_SHARE_ON)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            // Notifications are optional: the bridge still runs if the user refuses them.
            REQ_BRIDGE -> BridgeService.send(this, BridgeService.ACTION_START)
            REQ_SHARE ->
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    BridgeService.send(this, BridgeService.ACTION_SHARE_ON)
                } else tg.note("sharing needs the precise location permission")
        }
    }

    private fun confirmSignOut() {
        AlertDialog.Builder(this)
            .setMessage("Sign TAKgram out of Telegram on this phone? You will need the QR code to sign in again.")
            .setPositiveButton("Sign out") { _, _ ->
                BridgeService.send(this, BridgeService.ACTION_STOP)
                tg.logOut()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendTestCot() {
        val xml = CotBuild.marker(
            uid = "takgram-a0-test", type = "a-u-G", callsign = "TAKgram test",
            lat = TEST_LAT, lon = TEST_LON, remarks = "Sent by the TAKgram app to UDP 127.0.0.1:4242",
        )
        io.execute {
            try {
                CotUdpSender.send(xml)
                tg.note("test marker sent to ATAK at $TEST_LAT, $TEST_LON")
            } catch (e: Exception) {
                tg.note("test marker failed: $e")
            }
        }
    }

    // ---- debug-only automation ---------------------------------------------------------------------

    private fun handleDebugIntent(i: Intent?) {
        if (!BuildConfig.DEBUG || i == null) return
        i.getStringExtra("phone")?.let { tg.submitPhone(it) }
        i.getStringExtra("code")?.let { tg.submitCode(it) }
        if (i.getBooleanExtra("qr", false)) tg.requestQr()
        if (i.getBooleanExtra("send_cot", false)) sendTestCot()
        when (i.getStringExtra("bridge")) {
            "on" -> BridgeService.send(this, BridgeService.ACTION_START)
            "off" -> BridgeService.send(this, BridgeService.ACTION_STOP)
        }
        when (i.getStringExtra("share")) {
            "on" -> toggleSharing()
            "off" -> BridgeService.send(this, BridgeService.ACTION_SHARE_OFF)
        }
        i.getStringExtra("inject")?.let { kind -> window.decorView.postDelayed({ inject(kind) }, 1500) }
        // Consume the extras so a later onNewIntent or recreate does not repeat them.
        setIntent(Intent(i).apply { replaceExtras(Bundle()) })
    }

    /** Feeds the bridge a made-up Telegram message, exactly as Tg would, to test the path to ATAK. */
    private fun inject(kind: String) {
        val ann = TgSender(1001, "Ann", isBot = false, isSelf = false)
        fun msg(id: Long, c: TgContent, replyTo: Long = 0, edited: Boolean = false) =
            app.bridge.onMessage(TgMessage(-1, id, ann, c, replyTo, edited))
        when (kind) {
            "live" -> msg(10, TgContent.LiveLocation(30.2660, -97.7410, 9.0, 45, Bridge.FOREVER_S))
            "live2" -> msg(10, TgContent.LiveLocation(30.2675, -97.7395, 9.0, 45, Bridge.FOREVER_S), edited = true)
            "stop" -> msg(10, TgContent.LiveLocation(30.2675, -97.7395, 9.0, 45, 0), edited = true)
            "card" -> msg(11, TgContent.Text(IdentityCard.text(Identity("ANDROID-ann-1", "ALPHA-2", "Cyan"))))
            "text" -> msg(System.currentTimeMillis(), TgContent.Text("Hello from Telegram <test>"))
            "pin" -> msg(13, TgContent.Pin(30.2700, -97.7450))
            "name" -> msg(14, TgContent.Text("Rally point"), replyTo = 13)
            "cot1" -> {
                val xml = CotBuild.marker("inject-m-1", "a-h-G-U-C-A", "Tank 1", 30.2640, -97.7380, "Seen moving north")
                msg(15, TgContent.Text("Tank 1\nHostile ground armor\n" + Cot1Codec.encode(xml)))
            }
        }
        tg.note("debug: injected $kind")
    }

    companion object {
        private const val REQ_SHARE = 1
        private const val REQ_BRIDGE = 2
        private const val QR_PX = 720
        private const val TEST_LAT = 30.2690
        private const val TEST_LON = -97.7390
    }
}
