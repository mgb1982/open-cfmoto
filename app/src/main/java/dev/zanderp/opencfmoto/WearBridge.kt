// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
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
        if (active == sessionActive) return
        sessionActive = active
        if (active) sessionStartedAt = SystemClock.elapsedRealtime()
        LogBus.log("[WEAR] session ${if (active) "start" else "stop"} → watch")
        broadcast(ctx, PATH_SESSION, if (active) "start" else "stop")
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

    private fun snapshotJson(): String {
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
        return o.toString()
    }

    private fun sendStats(ctx: Context) {
        val node = subscriber ?: return
        try {
            Wearable.getMessageClient(ctx)
                .sendMessage(node, PATH_STATS, snapshotJson().toByteArray(Charsets.UTF_8))
        } catch (_: Exception) {
            // No Play services / Wear OS app on this phone: nothing to talk to.
        }
    }

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
