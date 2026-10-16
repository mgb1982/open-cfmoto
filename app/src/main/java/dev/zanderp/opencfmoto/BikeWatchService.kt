// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Low-power wait for the bike (v2.2). The dash's Bluetooth can stay on with the bike off, so the only
 * reliable "bike is on" signal is its Wi-Fi. Instead of the v2 hard stop after 20 min, we look at
 * the phone's Wi-Fi scan results every so often — no wake locks, no joins — and start Connect when
 * the bike's network shows up.
 *
 *  - [Mode.LONG]: after the 20-min give-up, for up to [LONG_MAX_MS], every [LONG_EVERY_MS]. Android
 *    scans by itself whenever the screen is on, so it's quickest the moment you look at the phone.
 *  - [Mode.SHORT]: after the dash's Bluetooth connects but its Wi-Fi isn't up yet, for [SHORT_MAX_MS],
 *    every [SHORT_EVERY_MS] (asks for a scan too).
 */
class BikeWatchService : Service() {
    enum class Mode { LONG, SHORT }

    private val h = Handler(Looper.getMainLooper())
    private var mode = Mode.LONG
    private var until = 0L
    private var ssid = ""
    private var checks = 0
    private var wifiOffNotified = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopNow("rider"); return START_NOT_STICKY }
        val newMode = runCatching { Mode.valueOf(intent?.getStringExtra(EXTRA_MODE) ?: "LONG") }.getOrDefault(Mode.LONG)
        ssid = BikeMemory.lastQr(this)?.ssid.orEmpty()
        if (ssid.isBlank()) { stopSelf(); return START_NOT_STICKY }
        val now = SystemClock.elapsedRealtime()
        // A SHORT request never shortens an ongoing LONG wait.
        if (!running || newMode == Mode.LONG || mode == Mode.SHORT) {
            mode = newMode
            until = now + if (mode == Mode.LONG) LONG_MAX_MS else SHORT_MAX_MS
        }
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, notification(),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
            )
        } catch (e: Exception) {
            LogBus.log("[WATCH] can't run in the foreground: $e")
            stopSelf(); return START_NOT_STICKY
        }
        running = true
        instance = this
        LogBus.log("[WATCH] waiting for '$ssid' in low-power mode ($mode)")
        h.removeCallbacksAndMessages(null)
        if (mode == Mode.SHORT) startProbe()
        h.post(check)
        return START_NOT_STICKY
    }

    private val check = object : Runnable {
        override fun run() {
            if (!running) return
            if (SystemClock.elapsedRealtime() > until) { stopNow("time limit"); return }
            checks++
            if (mode == Mode.SHORT) {
                try { (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).startScan() } catch (_: Exception) {}
            }
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val wifiOn = wm?.isWifiEnabled == true
            // LONG (hours): only read the results of the scans Android does anyway (screen on etc.).
            // Asking for a Wi-Fi scan every minute for 14 h cost real battery.
            val seen = BikeWifi.isSsidInRange(this@BikeWatchService, ssid, refresh = mode == Mode.SHORT)
            // v2.2 field test: logs showed "waiting…" and then nothing, so we couldn't tell "bike off"
            // from "phone Wi-Fi off" from "the process died". Log every check (SHORT) / every 10th
            // (LONG) and flush the log to disk so it survives the process being killed.
            if (mode == Mode.SHORT || checks % 30 == 1) {
                LogBus.log("[WATCH] check $checks: phone Wi-Fi ${if (wifiOn) "on" else "OFF"} · '$ssid' ${when (seen) { true -> "seen"; false -> "not seen"; null -> "unknown (no fresh scan)" }}")
                try { CrashGuard.persistSessionAsync(applicationContext) } catch (_: Exception) {}
            }
            // With the phone's Wi-Fi off we can never see (or join) the bike. Android doesn't let apps
            // switch it on, so tell the rider once, with a one-tap shortcut to the Wi-Fi panel.
            if (!wifiOn && !wifiOffNotified) {
                wifiOffNotified = true
                BtConnectTrigger.notifyWifiOff(applicationContext)
            }
            if (wifiOn && wifiOffNotified) { wifiOffNotified = false; BtConnectTrigger.clearWifiOffNotification(applicationContext) }
            if (seen == true) {
                LogBus.log("[WATCH] '$ssid' is up after $checks check(s) — starting Connect")
                running = false
                BtConnectTrigger.launchConnect(this@BikeWatchService, "bike Wi-Fi seen") { stopNow("bike found") }
                return
            }
            h.postDelayed(this, if (mode == Mode.LONG) LONG_EVERY_MS else SHORT_EVERY_MS)
        }
    }

    fun pokeNow() { h.removeCallbacks(check); h.post(check); if (mode == Mode.SHORT) startProbe() }

    // ---- Background join (SHORT mode only)
    // Polling scan results doesn't work with the screen off: Android barely scans then, and the bike's
    // network isn't a saved one. OpenCfMoto connects with the phone locked because it keeps a Wi-Fi
    // request open, and *that* makes Android look for the network by itself. We do the same, but only
    // for the few minutes after the dash's Bluetooth connects (the bike is probably being started), so
    // it doesn't cost battery the rest of the day. Needs the bike network approved once before (every
    // rider who has connected has), otherwise Android would show its picker.
    private var probeCb: android.net.ConnectivityManager.NetworkCallback? = null

    private fun startProbe() {
        if (probeCb != null) return
        if (BikeMemory.winningTransport(this, ssid) == "P2P") return
        val psk = BikeMemory.lastQr(this)?.pwd
        if (psk.isNullOrBlank()) return
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return
        val req = android.net.NetworkRequest.Builder()
            .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(android.net.wifi.WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(psk).build())
            .build()
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                h.post {
                    if (!running) return@post
                    LogBus.log("[WATCH] joined '$ssid' in the background (phone can stay locked) — starting Connect")
                    try { CrashGuard.persistSessionAsync(applicationContext) } catch (_: Exception) {}
                    running = false
                    // Keep our request a little longer so the link stays up while Connect asks for it.
                    h.postDelayed({ stopProbe() }, 20_000L)
                    BtConnectTrigger.launchConnect(this@BikeWatchService, "bike Wi-Fi joined") { h.postDelayed({ stopNow("bike found") }, 21_000L) }
                }
            }
            override fun onUnavailable() {
                h.post { probeCb = null; LogBus.log("[WATCH] background join: '$ssid' didn't appear") }
            }
        }
        val left = (until - SystemClock.elapsedRealtime()).coerceIn(10_000L, SHORT_MAX_MS).toInt()
        try {
            cm.requestNetwork(req, cb, left)
            probeCb = cb
            LogBus.log("[WATCH] asked Android to join '$ssid' as soon as it appears (${left / 1000}s)")
        } catch (e: Exception) {
            LogBus.log("[WATCH] background join request failed: $e")
        }
    }

    private fun stopProbe() {
        val cb = probeCb ?: return
        probeCb = null
        try { getSystemService(android.net.ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } catch (_: Exception) {}
    }

    private fun stopNow(why: String) {
        running = false
        stopProbe()
        h.removeCallbacksAndMessages(null)
        LogBus.log("[WATCH] stopped ($why)")
        if (instance === this) instance = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        stopProbe()
        h.removeCallbacksAndMessages(null)
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun notification(): android.app.Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.watch_channel), NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(
            this, 6, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 7, Intent(this, BikeWatchService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.watch_title, BikeMemory.lastBikeName(this) ?: ssid))
            .setContentText(getString(R.string.watch_text))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.watch_stop), stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "bike_watch"
        private const val NOTIF_ID = 4402
        private const val EXTRA_MODE = "mode"
        private const val ACTION_STOP = "dev.zanderp.opencfmoto.WATCH_STOP"
        private const val LONG_MAX_MS = 14 * 3600_000L
        private const val LONG_EVERY_MS = 60_000L
        private const val SHORT_MAX_MS = 4 * 60_000L
        private const val SHORT_EVERY_MS = 15_000L

        @Volatile var running = false
            private set
        @Volatile private var instance: BikeWatchService? = null

        /** @return false if Android wouldn't let us start (caller falls back to a direct launch). */
        fun start(ctx: Context, mode: Mode): Boolean {
            if (running && mode == Mode.SHORT) { instance?.pokeNow(); return true }
            return try {
                androidx.core.content.ContextCompat.startForegroundService(
                    ctx, Intent(ctx, BikeWatchService::class.java).putExtra(EXTRA_MODE, mode.name),
                )
                true
            } catch (e: Exception) {
                LogBus.log("[WATCH] start failed: $e")
                false
            }
        }

        /** Connect started (or the rider tapped Stop): no need to keep waiting. */
        fun stop(ctx: Context) {
            if (!running && instance == null) return
            try { ctx.startService(Intent(ctx, BikeWatchService::class.java).setAction(ACTION_STOP)) } catch (_: Exception) {}
        }
    }
}
