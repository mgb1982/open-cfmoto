// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.content.Context
import android.location.Location
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Trip names like "Sants → Zona Franca" (or "Vuelta por Montjuïc" when you end where you started),
 * from OpenStreetMap's Nominatim reverse geocoder: start and end points only, rounded, at most one
 * request per second (Nominatim's usage policy), cached for good once found.
 */
object TripNames {
    private const val PREFS = "trip_names"
    private const val MAX_PER_BATCH = 12
    private const val LOOP_METERS = 400f

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "trip-names").apply { isDaemon = true } }
    private val pending = HashSet<String>()
    /** Trips Nominatim couldn't name in this process — don't keep asking for them. */
    private val failed = HashSet<String>()

    fun cached(ctx: Context, trip: Trip): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(trip.id, null)?.takeIf { it.isNotBlank() }

    /** Name if known, otherwise the date. */
    fun title(ctx: Context, trip: Trip): String = cached(ctx, trip) ?: trip.dateText()

    /** Names unnamed trips in the background; [onNamed] runs on the worker thread after each one. */
    fun ensure(ctx: Context, trips: List<Trip>, onNamed: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        val todo = synchronized(pending) {
            trips.filter { it.points.size >= 2 && it.id !in pending && it.id !in failed && cached(app, it) == null }
                .take(MAX_PER_BATCH)
                .also { batch -> batch.forEach { pending.add(it.id) } }
        }
        if (todo.isEmpty()) return
        io.execute {
            for (t in todo) {
                try {
                    val name = name(app, t)
                    if (name != null) {
                        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(t.id, name).apply()
                        onNamed?.invoke()
                    } else {
                        synchronized(pending) { failed.add(t.id) }
                    }
                } catch (e: Exception) {
                    // Usually no internet: try again next time the list opens.
                    LogBus.log("[NAMES] ${t.id}: ${e.message}")
                } finally {
                    synchronized(pending) { pending.remove(t.id) }
                }
            }
        }
    }

    private fun name(ctx: Context, t: Trip): String? {
        val a = t.points.first()
        val b = t.points.last()
        val d = FloatArray(1)
        Location.distanceBetween(a.lat, a.lon, b.lat, b.lon, d)
        if (d[0] < LOOP_METERS) {
            // Round trip: name it after the farthest point from the start.
            val far = t.points.maxByOrNull { p ->
                val x = FloatArray(1); Location.distanceBetween(a.lat, a.lon, p.lat, p.lon, x); x[0]
            } ?: b
            val place = place(far.lat, far.lon) ?: return null
            return ctx.getString(R.string.trip_name_loop, place)
        }
        val from = place(a.lat, a.lon) ?: return null
        val to = place(b.lat, b.lon) ?: return null
        return if (from == to) ctx.getString(R.string.trip_name_within, from) else "$from → $to"
    }

    /** Neighbourhood-level name for a point, or null. */
    private fun place(lat: Double, lon: Double): String? {
        val la = String.format(Locale.ROOT, "%.3f", lat)
        val lo = String.format(Locale.ROOT, "%.3f", lon)
        val lang = Locale.getDefault().language
        AppHttp.throttle("nominatim.openstreetmap.org", 1_100)
        val body = AppHttp.getText(
            "https://nominatim.openstreetmap.org/reverse?lat=$la&lon=$lo&format=jsonv2&zoom=16&addressdetails=1" +
                "&accept-language=$lang",
        )
        val addr = JSONObject(body).optJSONObject("address") ?: return null
        for (k in listOf("suburb", "neighbourhood", "quarter", "industrial", "village", "town", "city_district", "city", "road")) {
            val v = addr.optString(k)
            if (v.isNotBlank()) return v.take(32)
        }
        return null
    }
}
