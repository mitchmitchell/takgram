package org.takgram.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import org.drinkless.tdlib.TdApi
import org.takgram.core.FixFilter
import org.takgram.core.SharePolicy
import kotlin.math.roundToInt

/**
 * The foreground service that keeps TAKgram working with the screen off.
 *
 * While it runs, messages from the linked chat are passed to ATAK. Sharing the phone's own
 * position is a separate switch inside it and is off until the user turns it on.
 */
class BridgeService : Service(), LocationListener {

    private lateinit var app: TakgramApp
    private lateinit var tg: Tg
    private lateinit var lm: LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("takgram", Context.MODE_PRIVATE) }

    private var sharing = false
    private var edits = 0
    private var failures = 0
    private var fixFilter = FixFilter()
    private val sharePolicy = SharePolicy()

    private val ticker = object : Runnable {
        override fun run() {
            app.bridge.tick()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        app = application as TakgramApp
        tg = app.tg
        lm = getSystemService(LocationManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }
            ACTION_SHARE_ON -> {
                startBridge()
                startSharing()
            }
            ACTION_SHARE_OFF -> {
                stopSharing("sharing off")
                if (!app.bridgeOn) {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            null -> {
                // Restarted by Android in the background. Location may not be started from there
                // without the "allow all the time" permission, so sharing waits for the user.
                startBridge()
                if (prefs.getBoolean(PREF_SHARING, false)) {
                    prefs.edit().putBoolean(PREF_SHARING, false).apply()
                    tg.stopLive()
                    notifyPaused()
                    tg.note("Android restarted TAKgram; position sharing is paused until you turn it on again")
                }
            }
            else -> startBridge()
        }
        return START_STICKY
    }

    private fun startBridge() {
        if (app.bridgeOn) return
        foreground(location = false)
        app.bridgeOn = true
        prefs.edit().putBoolean(PREF_BRIDGE, true).apply()
        handler.postDelayed(ticker, TICK_MS)
        app.takServer.start()
        app.bridge.setChat(tg.linkedChatId, tg.chatTitle(tg.linkedChatId))
        tg.note("bridge on: ${tg.chatTitle(tg.linkedChatId)} and ATAK")
        tg.loadIdentityCards(tg.linkedChatId)
        app.postIdentity()
    }

    private fun startSharing() {
        if (sharing) return
        if (tg.linkedChatId == 0L || !hasLocationPermission()) {
            tg.note("sharing not started: ${if (tg.linkedChatId == 0L) "choose a linked chat first" else "location permission is missing"}")
            return
        }
        try {
            foreground(location = true)
            // "fused" (Android 12+) combines GPS, Wi-Fi and cell; it gives a fix indoors where GPS alone may not.
            val wanted = listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            val used = wanted.filter { lm.allProviders.contains(it) && lm.isProviderEnabled(it) }
            if (used.isEmpty()) {
                tg.note("sharing not started: Location is turned off on this phone")
                foreground(location = false)
                return
            }
            fixFilter = FixFilter()
            sharePolicy.reset()
            sharing = true
            prefs.edit().putBoolean(PREF_SHARING, true).apply()
            for (p in used) lm.requestLocationUpdates(p, FIX_INTERVAL_MS, 0f, this, Looper.getMainLooper())
            // Start from the newest known position so the chat shows something at once.
            used.mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.elapsedRealtimeNanos }?.let { onLocationChanged(it) }
            tg.note("sharing on")
        } catch (e: SecurityException) {
            tg.note("sharing not started: ${e.message}")
            stopSharing(null)
        }
    }

    override fun onLocationChanged(loc: Location) {
        if (!sharing) return
        // Drop coarse cell/Wi-Fi fixes while a recent accurate one exists, so the shared position does not jump.
        val accuracy = if (loc.hasAccuracy()) loc.accuracy.toDouble() else 0.0
        if (!fixFilter.accept(loc.elapsedRealtimeNanos / 1_000_000, accuracy)) return
        val heading = if (loc.hasBearing()) loc.bearing.roundToInt().let { if (it <= 0) 360 else it.coerceAtMost(360) } else 0
        val fix = try {
            Fix(loc.latitude, loc.longitude, accuracy, heading)
        } catch (e: IllegalArgumentException) {
            return
        }
        tg.liveError?.let {
            stopSharing("Telegram refused the live location in ${tg.chatTitle(tg.linkedChatId)} ($it). Sharing stopped.")
            return
        }
        val live = tg.live
        if (live != null && live.chatId == tg.linkedChatId && !live.confirmed) return // still being sent
        if (live == null || live.chatId != tg.linkedChatId) {
            if (live != null) tg.stopLive() // the linked chat changed
            sharePolicy.shouldSend(SystemClock.elapsedRealtime(), fix.lat, fix.lon)
            tg.startLive(tg.linkedChatId, fix)
            return
        }
        if (!sharePolicy.shouldSend(SystemClock.elapsedRealtime(), fix.lat, fix.lon)) return
        tg.editLive(fix) { r ->
            if (r is TdApi.Error) {
                failures++
                // 400: the message is gone or can no longer be edited. Forget it; the next fix posts a fresh one.
                if (r.code == 400) tg.forgetLive()
                tg.note("position update refused: ${r.code} ${r.message}")
            } else edits++
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit

    private fun stopSharing(message: String?) {
        lm.removeUpdates(this)
        if (sharing || tg.live != null) tg.stopLive()
        sharing = false
        prefs.edit().putBoolean(PREF_SHARING, false).apply()
        if (message != null) tg.note(message)
        if (app.bridgeOn) foreground(location = false)
    }

    private fun stopEverything() {
        stopSharing(null)
        handler.removeCallbacks(ticker)
        app.bridge.clearChat()
        app.takServer.stop()
        app.bridgeOn = false
        prefs.edit().putBoolean(PREF_BRIDGE, false).apply()
        tg.note("bridge off")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        lm.removeUpdates(this)
        handler.removeCallbacks(ticker)
        app.takServer.stop()
        app.bridgeOn = false
        super.onDestroy()
    }

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun foreground(location: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        // No badge on the app icon: this notification only says the bridge is running.
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "TAKgram bridge", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(if (location) android.R.drawable.ic_menu_mylocation else android.R.drawable.ic_menu_share)
            .setContentTitle(if (location) "TAKgram is sharing your position" else "TAKgram bridge is on")
            .setContentText("${tg.chatTitle(tg.linkedChatId)} and ATAK")
            .setContentIntent(open)
            .setOngoing(true)
        if (location) {
            b.addAction(Notification.Action.Builder(null, "Stop sharing", service(ACTION_SHARE_OFF, 1)).build())
        }
        b.addAction(Notification.Action.Builder(null, "Turn bridge off", service(ACTION_STOP, 2)).build())
        val n = b.build()
        if (Build.VERSION.SDK_INT >= 34) {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                (if (location) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
            startForeground(NOTIFICATION_ID, n, type)
        } else if (Build.VERSION.SDK_INT >= 29 && location) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun notifyPaused() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERT, "TAKgram alerts", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(
            PAUSED_ID,
            Notification.Builder(this, CHANNEL_ALERT)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Position sharing is paused")
                .setContentText("Android restarted TAKgram. Tap to turn sharing back on.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun service(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, BridgeService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val ACTION_START = "org.takgram.app.BRIDGE_ON"
        const val ACTION_STOP = "org.takgram.app.BRIDGE_OFF"
        const val ACTION_SHARE_ON = "org.takgram.app.SHARE_ON"
        const val ACTION_SHARE_OFF = "org.takgram.app.SHARE_OFF"
        const val PREF_BRIDGE = "bridge_on"
        const val PREF_SHARING = "sharing_on"
        private const val CHANNEL = "bridge"
        private const val CHANNEL_ALERT = "alerts"
        private const val NOTIFICATION_ID = 1
        private const val PAUSED_ID = 2
        private const val FIX_INTERVAL_MS = 5_000L
        private const val TICK_MS = 30_000L

        fun send(ctx: Context, action: String) {
            val i = Intent(ctx, BridgeService::class.java).setAction(action)
            if (action == ACTION_START || action == ACTION_SHARE_ON) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }
}
