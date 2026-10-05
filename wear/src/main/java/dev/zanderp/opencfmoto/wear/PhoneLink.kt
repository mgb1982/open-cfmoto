// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject

/** Watch side of the protocol; see WearBridge in the phone app for the full description. */
object PhoneLink {
    const val PATH_SUB = "/ocm/sub"
    const val PATH_KEY = "/ocm/key"
    const val PATH_SCROLL = "/ocm/scroll"
    const val PATH_STATS = "/ocm/stats"
    const val PATH_SESSION = "/ocm/session"

    // Same values as AaInput.KEY_* in the phone app (Android keycodes).
    const val KEY_UP = 19
    const val KEY_DOWN = 20
    const val KEY_LEFT = 21
    const val KEY_RIGHT = 22
    const val KEY_ENTER = 23
    const val KEY_BACK = 4
    const val KEY_HOME = 3
    const val KEY_ASSISTANT = 84

    @Volatile private var phoneNode: String? = null

    /** Send to the phone (cached node; refreshed from the connected nodes when unknown). */
    fun send(ctx: Context, path: String, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val node = phoneNode
        if (node != null) {
            Wearable.getMessageClient(ctx).sendMessage(node, path, bytes)
                .addOnFailureListener { phoneNode = null }
            return
        }
        Wearable.getNodeClient(ctx).connectedNodes.addOnSuccessListener { nodes ->
            val n = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull() ?: return@addOnSuccessListener
            phoneNode = n.id
            Wearable.getMessageClient(ctx).sendMessage(n.id, path, bytes)
        }
    }

    fun rememberPhone(nodeId: String) {
        phoneNode = nodeId
    }

    // ---- Ride notification (Ongoing Activity: icon on the watch face, one tap back into the app) ----

    private const val CHANNEL = "ride"
    private const val NOTIF_ID = 1

    fun showRide(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, ctx.getString(R.string.channel_ride), NotificationManager.IMPORTANCE_HIGH)
        )
        val open = Intent(ctx, WearMainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(
            ctx, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_moto)
            .setContentTitle(ctx.getString(R.string.app_name))
            .setContentText(ctx.getString(R.string.ride_active))
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setOngoing(true)
            .setContentIntent(pi)
            // Best effort to bring the trip screen up by itself when the ride starts.
            .setFullScreenIntent(pi, true)
        try {
            OngoingActivity.Builder(ctx, NOTIF_ID, builder)
                .setStaticIcon(R.drawable.ic_moto)
                .setTouchIntent(pi)
                .build()
                .apply(ctx)
        } catch (_: Exception) {
        }
        try {
            nm.notify(NOTIF_ID, builder.build())
        } catch (_: SecurityException) {
            // Notifications not allowed yet (asked on first launch).
        }
    }

    fun clearRide(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
    }
}

/** Snapshot sent by the phone ~1 Hz (see WearBridge.snapshotJson). */
data class RideStats(
    val phase: String,
    val session: Boolean,
    val recording: Boolean,
    val fix: Boolean,
    val speedKmh: Int,
    val distanceM: Double,
    val movingMs: Long,
    val maxKmh: Double,
    val avgKmh: Double,
    val elapsedMs: Long,
    val accuracyM: Int,
    val altitudeM: Double?,
    val altitudeRaw: Boolean,
    val bearing: Double?,
    val batteryPct: Int?,
    val batteryTempC: Double?,
    val charging: Boolean,
    val volume: Int?,
    val volumeMax: Int?,
    val boost: Int,
    val clockResync: Boolean,
) {
    companion object {
        fun parse(json: String): RideStats? = try {
            val o = JSONObject(json)
            RideStats(
                phase = o.optString("phase"),
                session = o.optBoolean("session"),
                recording = o.optBoolean("rec"),
                fix = o.optBoolean("fix"),
                speedKmh = o.optInt("spd"),
                distanceM = o.optDouble("dist", 0.0),
                movingMs = o.optLong("mov"),
                maxKmh = o.optDouble("max", 0.0),
                avgKmh = o.optDouble("avg", 0.0),
                elapsedMs = o.optLong("el"),
                accuracyM = o.optInt("acc"),
                altitudeM = if (o.has("alt")) o.optDouble("alt") else null,
                altitudeRaw = o.optBoolean("altRaw"),
                bearing = if (o.has("brg")) o.optDouble("brg") else null,
                batteryPct = if (o.has("bat")) o.optInt("bat") else null,
                batteryTempC = if (o.has("batT")) o.optDouble("batT") else null,
                charging = o.optBoolean("chg"),
                volume = if (o.has("vol")) o.optInt("vol") else null,
                volumeMax = if (o.has("volMax")) o.optInt("volMax") else null,
                boost = o.optInt("boost", -1),
                clockResync = o.optBoolean("clk"),
            )
        } catch (_: Exception) {
            null
        }
    }
}

/** Wakes up when the phone says the ride (projection to the dash) started or ended. */
class SessionListenerService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != PhoneLink.PATH_SESSION) return
        PhoneLink.rememberPhone(messageEvent.sourceNodeId)
        when (String(messageEvent.data, Charsets.UTF_8).trim()) {
            "start" -> {
                PhoneLink.showRide(this)
                try {
                    startActivity(
                        Intent(this, WearMainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    )
                } catch (_: Exception) {
                    // Background activity starts may be blocked; the ongoing icon is the fallback.
                }
            }
            "stop" -> PhoneLink.clearRide(this)
        }
    }
}
