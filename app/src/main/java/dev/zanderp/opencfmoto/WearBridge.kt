// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject

/**
 * Phone side of the Wear OS companion (module `:wear`, same applicationId and signing key, which the
 * Wearable Data Layer requires).
 *
 * Protocol (MessageClient, all paths under /ocm/):
 *  watch → phone  /ocm/sub        lease: "stream me stats"; re-sent every few seconds while the watch
 *                                 app is on screen, so nothing is sent once it closes.
 *                 /ocm/key        payload = Android keycode as text (AaInput.KEY_*)
 *                 /ocm/scroll     payload = signed knob steps as text ("1", "-2")
 *  phone → watch  /ocm/stats      JSON trip + link snapshot, ~1 Hz while a lease is live
 *                 /ocm/session    "start" when projection to the dash begins, "stop" when it ends
 *                 /ocm/turn       next-manoeuvre buzz {k,n,d,p} (TurnHaptics), sent to every watch
 *                 /ocm/parked     where the bike was left {lat,lon,t} (Parking), "" when cleared
 *
 * Trip numbers come from the shared [TripRecorder] (the same one that saves rides), so the watch
 * shows exactly what will be stored.
 */
object WearBridge {
    const val PATH_SUB = "/ocm/sub"
    const val PATH_KEY = "/ocm/key"
    const val PATH_SCROLL = "/ocm/scroll"
    const val PATH_STATS = "/ocm/stats"
    const val PATH_SESSION = "/ocm/session"
    const val PATH_TURN = "/ocm/turn"
    const val PATH_PARKED = "/ocm/parked"
    const val PATH_TILE = "/ocm/tile"
    private const val TILE_EVERY_MS = 60_000L
    @Volatile private var lastTileAt = 0L

    private const val LEASE_MS = 12_000L
    private const val STREAM_EVERY_MS = 1_000L

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var appCtx: Context? = null
    @Volatile private var subscriber: String? = null
    @Volatile private var leaseUntil = 0L
    @Volatile private var sessionActive = false
    @Volatile private var sessionStartedAt = 0L
    private var streaming = false

    private val streamTick = object : Runnable {
        override fun run() {
            val ctx = appCtx
            if (ctx == null || SystemClock.elapsedRealtime() > leaseUntil) {
                streaming = false
                return
            }
            sendStats(ctx)
            main.postDelayed(this, STREAM_EVERY_MS)
        }
    }

    /** Called from the AA service ticks/teardown: detects session start/stop and tells the watch. */
    fun sync(context: Context) {
        val ctx = context.applicationContext
        appCtx = ctx
        val phase = ConnectionState.phase
        val active = phase == Phase.STREAMING || (sessionActive && phase.busy)
        // Tile / complication on the watch: a summary about once a minute while riding.
        if (active && sessionActive && SystemClock.elapsedRealtime() - lastTileAt >= TILE_EVERY_MS) {
            lastTileAt = SystemClock.elapsedRealtime()
            broadcast(ctx, PATH_TILE, snapshotJson(ctx))
        }
        if (active == sessionActive) return
        sessionActive = active
        if (!active) TurnHaptics.clear()
        if (active) sessionStartedAt = SystemClock.elapsedRealtime()
        LogBus.log("[WEAR] session ${if (active) "start" else "stop"} → watch")
        broadcast(ctx, PATH_SESSION, if (active) "start" else "stop")
        lastTileAt = SystemClock.elapsedRealtime()
        broadcast(ctx, PATH_TILE, snapshotJson(ctx))
        RideExtras.onSession(ctx, active)
    }

    internal fun onMessage(ctx: Context, event: MessageEvent) {
        appCtx = ctx.applicationContext
        val text = String(event.data, Charsets.UTF_8).trim()
        when (event.path) {
            PATH_SUB -> main.post {
                val first = subscriber == null || SystemClock.elapsedRealtime() > leaseUntil
                subscriber = event.sourceNodeId
                leaseUntil = SystemClock.elapsedRealtime() + LEASE_MS
                if (first) LogBus.log("[WEAR] watch subscribed (${event.sourceNodeId})")
                if (!streaming) {
                    streaming = true
                    main.post(streamTick)
                }
            }
            PATH_KEY -> text.toIntOrNull()?.let { code -> main.post { sendKey(code) } }
            PATH_SCROLL -> text.toIntOrNull()?.let { d -> main.post { sendScroll(d) } }
            WearTrips.PATH_TRIPS -> WearTrips.sendList(ctx.applicationContext, event.sourceNodeId, text)
            WearTrips.PATH_TRIPMAP -> WearTrips.sendMap(ctx.applicationContext, text, event.sourceNodeId)
        }
    }

    /** Same routing as the phone's on-screen pad ([ControlsActivity]): map UI first, then AA. */
    private fun sendKey(code: Int) {
        val sink = MapInputBridge.keySink ?: AaVideoBridge.keySink
        if (sink == null) LogBus.log("[WEAR] key $code ignored — Android Auto / map not running")
        else try { sink(code) } catch (e: Exception) { LogBus.log("[WEAR] key $code failed: ${e.message}") }
    }

    private fun sendScroll(delta: Int) {
        if (delta == 0) return
        val sink = MapInputBridge.scrollSink ?: AaVideoBridge.scrollSink
        if (sink == null) LogBus.log("[WEAR] scroll $delta ignored — Android Auto / map not running")
        else try { sink(delta) } catch (e: Exception) { LogBus.log("[WEAR] scroll failed: ${e.message}") }
    }

    private fun snapshotJson(ctx: Context): String {
        val s = TripLogger.current?.snapshot()
        val o = JSONObject()
        o.put("phase", ConnectionState.phase.name)
        o.put("session", sessionActive)
        o.put("rec", s?.recording == true)
        o.put("fix", s?.hasFix == true)
        o.put("spd", s?.speedKmh ?: 0)
        o.put("dist", s?.distanceMeters ?: 0.0)
        o.put("mov", s?.movingTimeMs ?: 0L)
        o.put("max", (s?.maxSpeedMs ?: 0f) * 3.6f)
        val movH = (s?.movingTimeMs ?: 0L) / 3_600_000.0
        o.put("avg", if (movH > 0.0) (s!!.distanceMeters / 1000.0) / movH else 0.0)
        o.put("el", if (sessionActive) SystemClock.elapsedRealtime() - sessionStartedAt else 0L)
        o.put("acc", s?.accuracyM ?: 0)
        o.put("clk", ClockLab.resyncInProgress())
        TurnHaptics.current()?.let { o.put("turn", it) }
        if (HeadUnitServer.lastKnown == false && !sessionActive) o.put("hus", false)
        putInfo(ctx, o)
        return o.toString()
    }

    /** Third watch page: altitude, heading, phone battery/temperature, media volume. */
    private fun putInfo(ctx: Context, o: JSONObject) {
        try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val loc = lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (loc != null && System.currentTimeMillis() - loc.time < 15_000L) {
                // Mean-sea-level altitude when the phone can compute it (Android 14+); raw GPS
                // altitude is ellipsoidal (~50 m high around Barcelona).
                if (Build.VERSION.SDK_INT >= 34 && loc.hasMslAltitude()) o.put("alt", loc.mslAltitudeMeters)
                else if (loc.hasAltitude()) { o.put("alt", loc.altitude); o.put("altRaw", true) }
                if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 1.5f) o.put("brg", loc.bearing.toDouble())
            }
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
        try {
            val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (b != null) {
                val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (level >= 0 && scale > 0) o.put("bat", level * 100 / scale)
                val t = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                if (t != Int.MIN_VALUE) o.put("batT", t / 10.0)
                o.put("chg", b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
            }
        } catch (_: Exception) {
        }
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (am != null) {
                o.put("vol", am.getStreamVolume(AudioManager.STREAM_MUSIC))
                o.put("volMax", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            }
            o.put("boost", if (SpeedVolume.running) SpeedVolume.boostSteps else -1)
        } catch (_: Exception) {
        }
    }

    private fun sendStats(ctx: Context) {
        val node = subscriber ?: return
        try {
            Wearable.getMessageClient(ctx)
                .sendMessage(node, PATH_STATS, snapshotJson(ctx).toByteArray(Charsets.UTF_8))
        } catch (_: Exception) {
            // No Play services / Wear OS app on this phone: nothing to talk to.
        }
    }

    /** One-off message to every connected watch (no lease needed: the watch's listener wakes up). */
    fun sendToWatches(ctx: Context, path: String, text: String) = broadcast(ctx.applicationContext, path, text)

    private fun broadcast(ctx: Context, path: String, text: String) {
        try {
            Wearable.getNodeClient(ctx).connectedNodes.addOnSuccessListener { nodes ->
                val client = Wearable.getMessageClient(ctx)
                for (n in nodes) client.sendMessage(n.id, path, text.toByteArray(Charsets.UTF_8))
            }
        } catch (_: Exception) {
        }
    }
}

/** Receives the watch's messages even when no OpenCfMoto screen is open. */
class WearListenerService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        WearBridge.onMessage(this, messageEvent)
    }
}
