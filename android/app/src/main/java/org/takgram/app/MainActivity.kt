package org.takgram.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
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
import org.takgram.core.CotBuild
import java.util.concurrent.Executors

/**
 * A0 spike screen: sign in, pick the linked chat, send a test CoT event to ATAK, and turn
 * position sharing on or off. Plain framework views keep the dependency list short.
 *
 * Debug builds also accept the same actions as intent extras so emulator tests can run unattended:
 *   adb shell am start -n org.takgram.app/.MainActivity --es phone 9996621234
 *   ... --es link_title "TAKgram test"   --ez create_group true   --ez send_cot true
 *   ... --es send_text "hello"           --es share start|stop
 */
class MainActivity : Activity() {

    private lateinit var tg: Tg
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var qrView: ImageView
    private lateinit var qrHelp: TextView
    private var shownQr: String? = null
    private val io = Executors.newSingleThreadExecutor()
    private val listener = Tg.Listener { runOnUiThread { refresh() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tg = (application as TakgramApp).tg
        setContentView(buildUi())
        tg.addListener(listener)
        refresh()
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

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 22f
            col.addView(this)
        }
        status = TextView(this).also { col.addView(it) }

        val phone = field(col, "Phone number, e.g. +1 972 555 0100", InputType.TYPE_CLASS_PHONE)
        button(col, "Send phone number") { tg.submitPhone(phone.text.toString()) }
        val code = field(col, "Login code", InputType.TYPE_CLASS_NUMBER)
        button(col, "Send code") { tg.submitCode(code.text.toString()) }
        button(col, "Resend code another way") { tg.resendCode() }
        val pw = field(col, "Two-step password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        button(col, "Send password") { tg.submitPassword(pw.text.toString()) }

        button(col, "Sign in with QR code (no login code needed)") { tg.requestQr() }
        qrHelp = TextView(this).apply {
            text = "On another phone signed in to this Telegram account: Settings > Devices > " +
                "Link Desktop Device, then scan this code. It refreshes by itself."
            visibility = View.GONE
            col.addView(this)
        }
        qrView = ImageView(this).apply {
            visibility = View.GONE
            col.addView(this, LinearLayout.LayoutParams(QR_PX, QR_PX).apply { gravity = Gravity.CENTER_HORIZONTAL })
        }

        button(col, "Choose linked chat") { pickChat() }
        if (tg.testDc) button(col, "Create test group (test servers)") { tg.createTestGroup("TAKgram test") }
        button(col, "Send test CoT to ATAK (UDP 4242)") { sendTestCot() }
        button(col, "Start sharing my position") { startSharing() }
        button(col, "Stop sharing") { LocationShareService.stop(this) }

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

    private fun field(parent: LinearLayout, hint: String, type: Int) = EditText(this).apply {
        this.hint = hint
        inputType = type
        parent.addView(this)
    }

    private fun button(parent: LinearLayout, label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { action() }
        parent.addView(this)
    }

    private fun refresh() {
        val auth = tg.authState?.javaClass?.simpleName?.removePrefix("AuthorizationState") ?: "starting"
        val linked = tg.linkedChatId
        status.text = buildString {
            append("Servers: ").append(if (tg.testDc) "TEST" else "production").append('\n')
            append("Sign-in: ").append(auth).append('\n')
            append("Linked chat: ").append(if (linked == 0L) "none" else "${tg.chatTitle(linked)} ($linked)").append('\n')
            append("Sharing: ").append(if (tg.live != null) "on (message ${tg.live?.messageId})" else "off")
        }
        logView.text = tg.logLines().asReversed().joinToString("\n")
        showQr(if (auth == "WaitOtherDeviceConfirmation") tg.qrLink else null)
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

    private fun pickChat() {
        tg.loadChats()
        val list = tg.chatList()
        if (list.isEmpty()) {
            tg.note("no chats loaded yet; try again in a moment")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Link which chat?")
            .setItems(list.map { it.second }.toTypedArray()) { _, i -> tg.linkedChatId = list[i].first }
            .show()
    }

    private fun sendTestCot() {
        val xml = CotBuild.marker(
            uid = "takgram-a0-test", type = "a-u-G", callsign = "TAKgram A0 test",
            lat = 30.2690, lon = -97.7390, remarks = "Sent by the TAKgram app to UDP 127.0.0.1:4242",
        )
        io.execute {
            try {
                CotUdpSender.send(xml)
                tg.note("test CoT sent to 127.0.0.1:${CotUdpSender.PORT} (${xml.length} bytes)")
            } catch (e: Exception) {
                tg.note("test CoT failed: $e")
            }
        }
    }

    private fun startSharing() {
        val need = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.contains(Manifest.permission.ACCESS_FINE_LOCATION)) {
            requestPermissions(need.toTypedArray(), REQ_SHARE)
            return
        }
        LocationShareService.start(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_SHARE && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            LocationShareService.start(this)
        }
    }

    private fun handleDebugIntent(i: Intent?) {
        if (!BuildConfig.DEBUG || i == null) return
        i.getStringExtra("phone")?.let { tg.submitPhone(it) }
        i.getStringExtra("code")?.let { tg.submitCode(it) }
        if (i.getBooleanExtra("resend", false)) tg.resendCode()
        if (i.getBooleanExtra("qr", false)) tg.requestQr()
        i.getStringExtra("password")?.let { tg.submitPassword(it) }
        if (i.getBooleanExtra("create_group", false)) tg.createTestGroup("TAKgram test")
        i.getStringExtra("link_title")?.let { title ->
            tg.loadChats()
            val hit = tg.chatList().firstOrNull { it.second == title }
            if (hit != null) tg.linkedChatId = hit.first else tg.note("no chat titled \"$title\" loaded yet")
        }
        i.getStringExtra("send_text")?.let { t -> tg.linkedChatId.takeIf { it != 0L }?.let { tg.sendText(it, t) } }
        if (i.getBooleanExtra("send_cot", false)) sendTestCot()
        when (i.getStringExtra("share")) {
            "start" -> startSharing()
            "stop" -> LocationShareService.stop(this)
        }
        // Consume the extras so a later onNewIntent or recreate does not repeat them.
        setIntent(Intent(i).apply { replaceExtras(Bundle()) })
    }

    companion object {
        private const val REQ_SHARE = 1
        private const val QR_PX = 720
    }
}
