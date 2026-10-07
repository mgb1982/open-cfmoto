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
    const val PATH_TRIPS = "/ocm/trips"
    const val PATH_TRIPMAP = "/ocm/tripmap"
    const val PATH_TRIPIMG = "/ocm/tripimg"
    const val PATH_TURN = "/ocm/turn"
    const val PATH_PARKED = "/ocm/parked"
    const val PATH_TILE = "/ocm/tile"
    const val PATH_BIKEPHOTO = "/ocm/bikephoto"

    fun bikePhotoFile(ctx: Context) = java.io.File(ctx.filesDir, "bike_photo.jpg")

    /** The Garage photo, darkened for text on top; null if none. */
    fun bikePhoto(ctx: Context, dim: Float): android.graphics.drawable.Drawable? {
        val f = bikePhotoFile(ctx)
        if (!f.exists()) return null
        val bmp = android.graphics.BitmapFactory.decodeFile(f.absolutePath) ?: return null
        return android.graphics.drawable.LayerDrawable(
            arrayOf(
                android.graphics.drawable.BitmapDrawable(ctx.resources, bmp),
                android.graphics.drawable.ColorDrawable(android.graphics.Color.argb((dim * 255).toInt(), 0, 0, 0)),
            )
        )
    }

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

    // ---- Turn vibrations (phone: TurnHaptics) ----

    /**
     * Direction is in the pattern so it can be felt without looking: 2 pulses = left, 3 = right
     * (shorter pulses for "keep/slight"), long + N short = roundabout exit N, two long = U-turn,
     * one very long = destination. "pre" (well before) is softer than "now".
     */
    fun vibrateTurn(ctx: Context, json: String) {
        val o = try { JSONObject(json) } catch (_: Exception) { return }
        val kind = o.optString("k")
        val now = o.optString("p") == "now"
        val timings = ArrayList<Long>()
        fun pulses(count: Int, on: Long, gap: Long) {
            repeat(count) { timings += if (timings.isEmpty()) 0L else gap; timings += on }
        }
        when (kind) {
            "left" -> pulses(2, 170, 150)
            "right" -> pulses(3, 170, 150)
            "sleft" -> pulses(2, 90, 130)
            "sright" -> pulses(3, 90, 130)
            "uturn" -> pulses(2, 450, 220)
            "round" -> {
                pulses(1, 450, 0)
                timings += 260L; timings += 140L
                repeat((o.optInt("n", 1).coerceIn(1, 6)) - 1) { timings += 170L; timings += 140L }
            }
            "dest" -> pulses(1, 900, 0)
            else -> return
        }
        val vib = vibrator(ctx) ?: return
        val arr = timings.toLongArray()
        val effect = if (vib.hasAmplitudeControl()) {
            val amp = if (now) 255 else 110
            android.os.VibrationEffect.createWaveform(arr, IntArray(arr.size) { i -> if (i % 2 == 1) amp else 0 }, -1)
        } else {
            android.os.VibrationEffect.createWaveform(arr, -1)
        }
        // Riding = screen off / watch face showing: Android 12+ drops "unknown usage" vibrations from
        // background apps, so tag it as an alarm (one of the usages allowed from the background).
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                vib.vibrate(effect, android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(
                    effect,
                    android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).build(),
                )
            }
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrator(ctx: Context): android.os.Vibrator? =
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            ctx.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
        } else {
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        }

    /** Short arrow + distance for the trip page while Android Auto is guiding. */
    fun turnText(t: JSONObject?): String? {
        t ?: return null
        val d = t.optInt("d", -1)
        val dist = when {
            d < 0 -> ""
            d < 1000 -> " ${(d / 10) * 10} m"
            else -> String.format(java.util.Locale.getDefault(), " %.1f km", d / 1000.0)
        }
        val arrow = when (t.optString("k")) {
            "left" -> "↰"
            "right" -> "↱"
            "sleft" -> "↖"
            "sright" -> "↗"
            "uturn" -> "↶"
            "round" -> "⟳ ${t.optInt("n", 0).takeIf { it > 0 }?.let { "${it}ª" } ?: ""}"
            "dest" -> "🏁"
            else -> return null
        }
        return arrow + dist
    }

    // ---- Parked bike (phone: Parking) ----

    private const val PARK_PREFS = "parked"

    fun saveParked(ctx: Context, json: String) {
        ctx.getSharedPreferences(PARK_PREFS, Context.MODE_PRIVATE).edit().putString("spot", json).apply()
    }

    /** (lat, lon, savedAtMillis) or null. */
    fun parked(ctx: Context): Triple<Double, Double, Long>? {
        val raw = ctx.getSharedPreferences(PARK_PREFS, Context.MODE_PRIVATE).getString("spot", "") ?: ""
        if (raw.isBlank()) return null
        return try {
            val o = JSONObject(raw)
            Triple(o.getDouble("lat"), o.getDouble("lon"), o.optLong("t"))
        } catch (_: Exception) {
            null
        }
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
    val turn: JSONObject?,
    val husOff: Boolean,
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
                turn = o.optJSONObject("turn"),
                husOff = o.has("hus") && !o.optBoolean("hus", true),
            )
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Wakes up when the phone says the ride (projection to the dash) started or ended, and stores the
 * trip overview maps the phone pre-renders when the trips list opens (so the first tap is instant).
 */
class SessionListenerService : WearableListenerService() {
    override fun onDataChanged(events: com.google.android.gms.wearable.DataEventBuffer) {
        for (ev in events) {
            if (ev.type != com.google.android.gms.wearable.DataEvent.TYPE_CHANGED) continue
            val path = ev.dataItem.uri.path ?: continue
            if (path == PhoneLink.PATH_BIKEPHOTO) {
                saveBikePhoto(com.google.android.gms.wearable.DataMapItem.fromDataItem(ev.dataItem.freeze()).dataMap)
                continue
            }
            if (!path.startsWith(PhoneLink.PATH_TRIPMAP + "/")) continue
            val dm = com.google.android.gms.wearable.DataMapItem.fromDataItem(ev.dataItem.freeze()).dataMap
            if (dm.getString("key") != WearKeys.OVERVIEW || !dm.getBoolean("withMap")) continue
            val asset = dm.getAsset("map") ?: continue
            val tripId = path.substringAfterLast('/')
            try {
                val fd = com.google.android.gms.tasks.Tasks.await(
                    com.google.android.gms.wearable.Wearable.getDataClient(this).getFdForAsset(asset)
                )
                val bytes = fd.inputStream.use { it.readBytes() }
                WearKeys.overviewFile(this, tripId).writeBytes(bytes)
            } catch (_: Exception) {
            }
        }
    }

    private fun saveBikePhoto(dm: com.google.android.gms.wearable.DataMap) {
        val f = PhoneLink.bikePhotoFile(this)
        if (dm.getBoolean("none")) {
            f.delete()
            return
        }
        val asset = dm.getAsset("photo") ?: return
        try {
            val fd = com.google.android.gms.tasks.Tasks.await(
                com.google.android.gms.wearable.Wearable.getDataClient(this).getFdForAsset(asset)
            )
            f.writeBytes(fd.inputStream.use { it.readBytes() })
        } catch (_: Exception) {
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            PhoneLink.PATH_TURN -> {
                PhoneLink.vibrateTurn(this, String(messageEvent.data, Charsets.UTF_8))
                return
            }
            PhoneLink.PATH_PARKED -> {
                PhoneLink.saveParked(this, String(messageEvent.data, Charsets.UTF_8))
                RideGlance.refresh(this)
                return
            }
            PhoneLink.PATH_TILE -> {
                RideGlance.save(this, String(messageEvent.data, Charsets.UTF_8))
                return
            }
        }
        if (messageEvent.path == PhoneLink.PATH_TRIPIMG) {
            // Pre-rendered overview arriving while no map screen is open: keep it for the first tap.
            val img = WearKeys.parseImageMessage(messageEvent.data) ?: return
            if (img.key == WearKeys.OVERVIEW && img.withMap) {
                try { WearKeys.overviewFile(this, img.tripId).writeBytes(img.bytes) } catch (_: Exception) {}
            }
            return
        }
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
