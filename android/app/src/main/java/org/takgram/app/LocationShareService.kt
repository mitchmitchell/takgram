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
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import org.drinkless.tdlib.TdApi
import org.takgram.core.FixFilter
import kotlin.math.roundToInt

/**
 * Foreground service (type "location") that shares the phone's GPS position as a Telegram live
 * location in the linked chat. Runs only after the user turns sharing on.
 */
class LocationShareService : Service(), LocationListener {

    private lateinit var tg: Tg
    private lateinit var lm: LocationManager
    private var starting = false
    private var lastEditAt = 0L
    private var edits = 0
    private var failures = 0
    private var pending: Fix? = null
    private val fixFilter = FixFilter()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        tg = (application as TakgramApp).tg
        lm = getSystemService(LocationManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSharing()
            return START_NOT_STICKY
        }
        if (tg.linkedChatId == 0L || !hasLocationPermission()) {
            tg.note("sharing not started: ${if (tg.linkedChatId == 0L) "no linked chat" else "no location permission"}")
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        try {
            // "fused" (Android 12+) combines GPS, Wi-Fi and cell; it gives a fix indoors where GPS alone may not.
            val wanted = listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            val used = wanted.filter { lm.allProviders.contains(it) && lm.isProviderEnabled(it) }
            for (p in used) lm.requestLocationUpdates(p, FIX_INTERVAL_MS, 0f, this, Looper.getMainLooper())
            tg.note("location sources: ${used.ifEmpty { listOf("none (is Location turned on?)") }.joinToString()}")
            // Start from the newest known position so the chat shows something at once.
            used.mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }?.let {
                tg.note("starting from last known position")
                onLocationChanged(it)
            }
        } catch (e: SecurityException) {
            tg.note("location permission revoked: ${e.message}")
            stopSharing()
            return START_NOT_STICKY
        }
        tg.note("sharing on: chat ${tg.chatTitle(tg.linkedChatId)}")
        return START_STICKY
    }

    override fun onLocationChanged(loc: Location) {
        // Drop coarse cell/Wi-Fi fixes while a recent accurate one exists, so the shared position does not jump.
        val accuracy = if (loc.hasAccuracy()) loc.accuracy.toDouble() else 0.0
        if (!fixFilter.accept(loc.elapsedRealtimeNanos / 1_000_000, accuracy)) return
        val heading = if (loc.hasBearing()) loc.bearing.roundToInt().let { if (it <= 0) 360 else it.coerceAtMost(360) } else 0
        val fix = try {
            Fix(loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy.toDouble() else 0.0, heading)
        } catch (e: IllegalArgumentException) {
            return
        }
        tg.liveError?.let {
            tg.note("Telegram refused the live location in ${tg.chatTitle(tg.linkedChatId)}: $it. Sharing stopped.")
            stopSharing()
            return
        }
        val live = tg.live
        if (live != null && live.chatId == tg.linkedChatId && !live.confirmed) return // still being sent
        if (live == null || live.chatId != tg.linkedChatId) {
            if (starting) return
            starting = true
            tg.note("first fix ${"%.5f".format(java.util.Locale.ROOT, fix.lat)},${"%.5f".format(java.util.Locale.ROOT, fix.lon)} from ${loc.provider}; posting live location")
            tg.startLive(tg.linkedChatId, fix) { starting = false }
            lastEditAt = SystemClock.elapsedRealtime()
            return
        }
        pending = fix
        val now = SystemClock.elapsedRealtime()
        if (now - lastEditAt < EDIT_INTERVAL_MS) return
        lastEditAt = now
        val f = pending ?: return
        pending = null
        tg.editLive(f) { r ->
            if (r is TdApi.Error) failures++ else edits++
            // The message is gone or too old to edit: forget it so the next fix posts a fresh one.
            if (r is TdApi.Error && r.code == 400) tg.forgetLive()
            if ((edits + failures) % 10 == 0 || r is TdApi.Error) {
                tg.note("live edits ok=$edits failed=$failures last=${f.lat},${f.lon}")
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit

    private fun stopSharing() {
        lm.removeUpdates(this)
        tg.stopLive()
        tg.note("sharing off")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        lm.removeUpdates(this)
        super.onDestroy()
    }

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Position sharing", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LocationShareService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("TAKgram is sharing your position")
            .setContentText("Live location in ${tg.chatTitle(tg.linkedChatId)}")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop sharing", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    companion object {
        const val ACTION_STOP = "org.takgram.app.STOP_SHARING"
        private const val CHANNEL = "sharing"
        private const val NOTIFICATION_ID = 1
        private const val FIX_INTERVAL_MS = 5_000L
        /** Minimum time between live-location edits. Tuned in A1 from measured flood limits. */
        const val EDIT_INTERVAL_MS = 15_000L

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, LocationShareService::class.java))
        fun stop(ctx: Context) =
            ctx.startService(Intent(ctx, LocationShareService::class.java).setAction(ACTION_STOP))
    }
}
