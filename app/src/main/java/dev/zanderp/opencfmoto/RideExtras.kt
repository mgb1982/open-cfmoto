// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors

/** On/off switches for the v1.2 ride extras (Setup → Ride extras). */
object RideExtras {
    private const val PREFS = "ride_extras"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun parking(ctx: Context) = prefs(ctx).getBoolean("parking", true)
    fun setParking(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("parking", on).apply()
        if (!on) Parking.clear(ctx)
    }

    fun rain(ctx: Context) = prefs(ctx).getBoolean("rain", true)
    fun setRain(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("rain", on).apply()

    /** Called by WearBridge.sync when projection to the dash starts / ends. */
    fun onSession(ctx: Context, active: Boolean) {
        val app = ctx.applicationContext
        if (active) {
            Parking.clear(app)
            if (rain(app)) RainCheck.runSoon(app)
        } else if (parking(app)) {
            Parking.saveNow(app)
        }
    }

    internal fun lastLocation(ctx: Context, maxAgeMs: Long): Location? {
        val now = System.currentTimeMillis()
        TripRecorder.lastSeen?.let { if (now - it.time <= maxAgeMs) return it }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return try {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused")
                .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
                .filter { now - it.time <= maxAgeMs }
                .minByOrNull { if (it.hasAccuracy()) it.accuracy else 999f }
        } catch (_: SecurityException) {
            null
        }
    }
}

/**
 * "Where did I park?": the bike's position when projection to the dash ended. Shown on the main
 * screen, the home-screen widget and the watch; tapping opens walking directions.
 */
object Parking {
    private const val PREFS = "parking"
    private const val MAX_FIX_AGE_MS = 3 * 60_000L

    data class Spot(val lat: Double, val lon: Double, val accuracyM: Int, val time: Long)

    fun get(ctx: Context): Spot? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.contains("lat")) return null
        return Spot(
            java.lang.Double.longBitsToDouble(p.getLong("lat", 0)),
            java.lang.Double.longBitsToDouble(p.getLong("lon", 0)),
            p.getInt("acc", 0),
            p.getLong("t", 0),
        )
    }

    fun saveNow(ctx: Context) {
        val loc = RideExtras.lastLocation(ctx, MAX_FIX_AGE_MS)
        if (loc == null) {
            LogBus.log("[PARK] no recent GPS fix — parking spot not saved")
            return
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("lat", java.lang.Double.doubleToRawLongBits(loc.latitude))
            .putLong("lon", java.lang.Double.doubleToRawLongBits(loc.longitude))
            .putInt("acc", if (loc.hasAccuracy()) loc.accuracy.toInt() else 0)
            .putLong("t", System.currentTimeMillis())
            .apply()
        LogBus.log("[PARK] saved (±${if (loc.hasAccuracy()) loc.accuracy.toInt() else -1} m)")
        changed(ctx)
    }

    fun clear(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.contains("lat")) return
        p.edit().clear().apply()
        changed(ctx)
    }

    /** Distance in metres from where the phone is now, or null when we don't know where it is. */
    fun distanceFromHere(ctx: Context, spot: Spot): Int? {
        val here = RideExtras.lastLocation(ctx, 10 * 60_000L) ?: return null
        val out = FloatArray(1)
        Location.distanceBetween(here.latitude, here.longitude, spot.lat, spot.lon, out)
        return out[0].toInt()
    }

    fun distanceText(meters: Int): String =
        if (meters < 1000) "$meters m" else String.format(Locale.getDefault(), "%.1f km", meters / 1000.0)

    /** Google Maps (or any maps app) walking directions to the bike. */
    fun walkIntent(spot: Spot): Intent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${spot.lat},${spot.lon}&travelmode=walking"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun json(spot: Spot?): String =
        if (spot == null) "" else JSONObject().put("lat", spot.lat).put("lon", spot.lon).put("t", spot.time).toString()

    private fun changed(ctx: Context) {
        WearBridge.sendToWatches(ctx, WearBridge.PATH_PARKED, json(get(ctx)))
        ParkingWidget.updateAll(ctx)
    }
}

/**
 * Rain heads-up when a ride starts: one Open-Meteo request (free, no account) for the next 3 hours at
 * the phone's position, rounded to ~1 km. Shown as a phone notification, which Wear OS also puts on
 * the watch.
 */
object RainCheck {
    private const val CHANNEL = "ride_alerts"
    private const val NOTIF_ID = 4201
    private const val MIN_INTERVAL_MS = 45 * 60_000L
    private const val RAIN_MM = 0.2          // per 15 min
    private const val RAIN_PROBABILITY = 60  // %

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "rain-check").apply { isDaemon = true } }
    @Volatile private var lastRunAt = 0L

    fun runSoon(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - lastRunAt < MIN_INTERVAL_MS) return
        lastRunAt = now
        io.execute {
            try {
                check(ctx.applicationContext)
            } catch (e: Exception) {
                LogBus.log("[RAIN] check failed: ${e.message}")
                lastRunAt = 0L
            }
        }
    }

    private fun check(ctx: Context) {
        // GPS may still be warming up right after connecting: give it a moment.
        var loc = RideExtras.lastLocation(ctx, 30 * 60_000L)
        if (loc == null) {
            Thread.sleep(20_000)
            loc = RideExtras.lastLocation(ctx, 30 * 60_000L) ?: run {
                LogBus.log("[RAIN] no location — skipped")
                lastRunAt = 0L
                return
            }
        }
        val lat = String.format(Locale.ROOT, "%.2f", loc.latitude)
        val lon = String.format(Locale.ROOT, "%.2f", loc.longitude)
        val body = AppHttp.getText(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&minutely_15=precipitation,precipitation_probability&forecast_minutely_15=12&timezone=auto",
        )
        val m = JSONObject(body).getJSONObject("minutely_15")
        val times = m.getJSONArray("time")
        val mm = m.optJSONArray("precipitation")
        val prob = m.optJSONArray("precipitation_probability")
        var firstSlot = -1
        var maxMm = 0.0
        for (i in 0 until times.length()) {
            val r = mm?.optDouble(i, 0.0) ?: 0.0
            val p = prob?.optInt(i, 0) ?: 0
            if (r > maxMm) maxMm = r
            if (firstSlot < 0 && (r >= RAIN_MM || p >= RAIN_PROBABILITY)) firstSlot = i
        }
        LogBus.log("[RAIN] next 3 h: first rain slot=$firstSlot max=${maxMm} mm/15min")
        if (firstSlot < 0) return
        val text = when {
            firstSlot == 0 -> ctx.getString(R.string.rain_now)
            else -> ctx.getString(R.string.rain_in_minutes, firstSlot * 15)
        }
        notify(ctx, text, maxMm >= 1.0)
    }

    private fun notify(ctx: Context, text: String, heavy: Boolean) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, ctx.getString(R.string.rain_channel), NotificationManager.IMPORTANCE_HIGH)
        )
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(ctx.getString(if (heavy) R.string.rain_title_heavy else R.string.rain_title))
            .setContentText(text)
            .setSubText(ctx.getString(R.string.rain_source))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setTimeoutAfter(90 * 60_000L)
            .setContentIntent(open)
            .build()
        try {
            nm.notify(NOTIF_ID, n)
        } catch (_: SecurityException) {
        }
    }
}
