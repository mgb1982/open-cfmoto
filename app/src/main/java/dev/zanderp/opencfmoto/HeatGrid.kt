// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.graphics.Color
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max

/**
 * "Where I ride most": the map split into ~120 m cells, each counting how many *different trips*
 * passed through it (not GPS points, so waiting 5 minutes at a light doesn't light a cell up).
 */
class HeatGrid private constructor(
    val cells: List<Cell>,
    val maxTrips: Int,
    private val cellLat: Double,
    private val cellLon: Double,
) {
    data class Cell(val lat: Double, val lon: Double, val trips: Int, val heat: Float)

    /** Cell size in degrees, for drawing (latitude, longitude). */
    val sizeDeg: Pair<Double, Double> get() = cellLat to cellLon

    companion object {
        private const val CELL_M = 120.0

        fun build(trips: List<Trip>): HeatGrid {
            val pts = trips.asSequence().flatMap { it.points.asSequence() }
            val first = pts.firstOrNull() ?: return HeatGrid(emptyList(), 0, 0.001, 0.001)
            val cellLat = CELL_M / 111_320.0
            val cellLon = CELL_M / (111_320.0 * cos(Math.toRadians(first.lat)).coerceAtLeast(0.2))
            val counts = HashMap<Long, Int>()
            for (t in trips) {
                val seen = HashSet<Long>()
                var prev: TrackPoint? = null
                for (p in t.points) {
                    val q = prev
                    if (q != null) {
                        // Fill gaps between sparse fixes so a fast straight road is continuous.
                        val steps = max(
                            kotlin.math.abs(p.lat - q.lat) / cellLat,
                            kotlin.math.abs(p.lon - q.lon) / cellLon,
                        ).toInt().coerceAtMost(200)
                        for (i in 1 until steps) {
                            val f = i.toDouble() / steps
                            seen += key(q.lat + (p.lat - q.lat) * f, q.lon + (p.lon - q.lon) * f, cellLat, cellLon)
                        }
                    }
                    seen += key(p.lat, p.lon, cellLat, cellLon)
                    prev = p
                }
                for (k in seen) counts[k] = (counts[k] ?: 0) + 1
            }
            val maxTrips = counts.values.maxOrNull() ?: 0
            val norm = ln(1.0 + maxTrips).coerceAtLeast(1e-6)
            val cells = counts.map { (k, n) ->
                val row = (k shr 32).toInt()
                val col = (k and 0xffffffffL).toInt()
                Cell((row + 0.5) * cellLat, (col + 0.5) * cellLon, n,
                    if (maxTrips <= 1) 0.5f else (ln(1.0 + n) / norm).toFloat())
            }.sortedBy { it.heat } // hottest drawn last, on top
            return HeatGrid(cells, maxTrips, cellLat, cellLon)
        }

        private fun key(lat: Double, lon: Double, cLat: Double, cLon: Double): Long {
            val row = floor(lat / cLat).toInt()
            val col = floor(lon / cLon).toInt()
            return (row.toLong() shl 32) or (col.toLong() and 0xffffffffL)
        }

        /** Blue → green → yellow → red for 0..1. */
        fun color(heat: Float, alpha: Int = 255): Int {
            val stops = intArrayOf(
                Color.rgb(59, 130, 246), Color.rgb(34, 197, 94), Color.rgb(250, 204, 21), Color.rgb(239, 68, 68),
            )
            val x = heat.coerceIn(0f, 1f) * (stops.size - 1)
            val i = floor(x).toInt().coerceAtMost(stops.size - 2)
            val f = x - i
            val a = stops[i]; val b = stops[i + 1]
            fun mix(ca: Int, cb: Int) = (ca + (cb - ca) * f).toInt()
            return Color.argb(alpha, mix(Color.red(a), Color.red(b)), mix(Color.green(a), Color.green(b)), mix(Color.blue(a), Color.blue(b)))
        }
    }
}
