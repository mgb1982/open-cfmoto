// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.location.Location
import org.json.JSONObject
import java.net.HttpURLConnection
import kotlin.math.cos
import kotlin.math.floor

/**
 * Petrol stations near the rider with their Gasolina 95 (SP95, E5) price, Spain only.
 *
 * Prices are the Ministerio's open data, cut into 0.25° tiles (~25 km) every hour by the Pages
 * workflow (scripts/fuel_tiles.py) and served from GitHub Pages, so the phone downloads a few KB
 * around it instead of the 10 MB national list.
 */
object FuelStations {
    const val BASE = "https://mgb1982.github.io/ridescreen-aa/fuel/"
    private const val CACHE_MS = 30 * 60_000L

    data class Station(
        val lat: Double, val lon: Double, val price: Double,
        val brand: String, val address: String, val town: String, val hours: String,
        val distanceM: Float,
    )

    class Result(val stations: List<Station>, val radiusKm: Int, val sourceTime: String, val outsideSpain: Boolean)

    private class Tile(val at: Long, val time: String, val rows: List<DoubleArrayWithText>?)
    private class DoubleArrayWithText(val lat: Double, val lon: Double, val price: Double, val text: Array<String>)

    private val cache = HashMap<String, Tile>()

    /** Tile keys covering a circle of [radiusKm] around ([lat], [lon]). */
    fun tilesAround(lat: Double, lon: Double, radiusKm: Double): List<String> {
        val dLat = radiusKm / 111.0
        val dLon = radiusKm / (111.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.2))
        val keys = ArrayList<String>()
        for (y in floor((lat - dLat) * 4).toInt()..floor((lat + dLat) * 4).toInt()) {
            for (x in floor((lon - dLon) * 4).toInt()..floor((lon + dLon) * 4).toInt()) keys += "${y}_$x"
        }
        return keys
    }

    /**
     * Blocking (network): stations within 5 km, widening to 10 / 20 km when fewer than 3 are found.
     * Sorted cheapest first; the caller re-sorts by distance if the rider wants that.
     */
    fun near(here: Location): Result {
        var time = ""
        var anyTile = false
        for (radius in intArrayOf(5, 10, 20)) {
            val found = ArrayList<Station>()
            for (key in tilesAround(here.latitude, here.longitude, radius.toDouble())) {
                val tile = tile(key)
                if (tile.rows != null) {
                    anyTile = true
                    if (tile.time.isNotEmpty()) time = tile.time
                }
                for (r in tile.rows.orEmpty()) {
                    val out = FloatArray(1)
                    Location.distanceBetween(here.latitude, here.longitude, r.lat, r.lon, out)
                    if (out[0] <= radius * 1000f) {
                        found += Station(r.lat, r.lon, r.price, r.text[0], r.text[1], r.text[2], r.text[3], out[0])
                    }
                }
            }
            if (found.size >= 3 || radius == 20) {
                return Result(found.sortedWith(compareBy({ it.price }, { it.distanceM })), radius, time, !anyTile)
            }
        }
        return Result(emptyList(), 20, time, !anyTile)
    }

    private fun tile(key: String): Tile {
        synchronized(cache) {
            cache[key]?.let { if (System.currentTimeMillis() - it.at < CACHE_MS) return it }
        }
        val t = try {
            val conn = AppHttp.openUrl("$BASE$key.json") as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", AppHttp.USER_AGENT)
            val code = conn.responseCode
            if (code == 404) {
                runCatching { conn.errorStream?.close() }
                Tile(System.currentTimeMillis(), "", null) // no stations here (sea, or outside Spain)
            } else if (code !in 200..299) {
                throw java.io.IOException("HTTP $code")
            } else {
                val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val arr = o.getJSONArray("s")
                val rows = ArrayList<DoubleArrayWithText>(arr.length())
                for (i in 0 until arr.length()) {
                    val a = arr.getJSONArray(i)
                    rows += DoubleArrayWithText(
                        a.getDouble(0), a.getDouble(1), a.getDouble(2),
                        arrayOf(a.optString(3), a.optString(4), a.optString(5), a.optString(6)),
                    )
                }
                Tile(System.currentTimeMillis(), o.optString("t"), rows)
            }
        } catch (e: Exception) {
            LogBus.log("[FUEL] tile $key failed: ${e.message}")
            throw e
        }
        synchronized(cache) { cache[key] = t }
        return t
    }
}
