// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * v2.3 · "Why did today take longer?" A trip is compared with the usual one: the other rides that
 * start and end at the same places (±[ENDPOINT_M]). The difference in door-to-door time is split
 * into what explains it — time stood still (lights, jams), how fast you went while moving, and a
 * longer or shorter way. Pure Kotlin (unit-tested); the screen only formats [Result].
 */
object TripCompare {
    private const val ENDPOINT_M = 400.0
    private const val MIN_OTHERS = 3
    private const val STILL_MS = 1.5f            // < 5.4 km/h
    private const val MIN_STOP_S = 4
    private const val SIGNIFICANT_S = 60         // differences under a minute aren't worth a line

    data class Metrics(
        val totalS: Int,          // door to door
        val stillS: Int,          // stood still (lights, traffic)
        val stops: Int,           // separate stops of ≥ MIN_STOP_S
        val movingKmh: Double,    // average while moving
        val km: Double,
    )

    data class Result(
        val today: Metrics,
        val usual: Metrics,       // medians of the other rides
        val others: Int,
        val diffS: Int,           // today − usual (positive = slower)
        val stillDiffS: Int,      // part explained by standing still
        val paceDiffS: Int,       // part explained by moving slower / faster
        val routeDiffS: Int,      // part explained by a longer / shorter way
        val main: Factor?,        // the biggest of the three, if it matters
    )

    enum class Factor { STILL, PACE, ROUTE }

    fun metrics(t: Trip): Metrics? {
        val p = t.points
        if (p.size < 10) return null
        var still = 0L; var stops = 0; var run = 0L; var moving = 0L; var dist = 0.0
        for (i in 1 until p.size) {
            val dt = (p[i].time - p[i - 1].time).coerceIn(0L, 30_000L)
            if (p[i].speedMs < STILL_MS) {
                still += dt; run += dt
            } else {
                if (run >= MIN_STOP_S * 1000L) stops++
                run = 0L
                moving += dt
                dist += metres(p[i - 1].lat, p[i - 1].lon, p[i].lat, p[i].lon)
            }
        }
        if (run >= MIN_STOP_S * 1000L) stops++
        val km = (if (t.distanceMeters > 0) t.distanceMeters else dist) / 1000.0
        val movingKmh = if (moving > 0) dist / 1000.0 / (moving / 3_600_000.0) else 0.0
        return Metrics(((t.end - t.start) / 1000L).toInt(), (still / 1000L).toInt(), stops, movingKmh, km)
    }

    /** Rides from the same start to the same end as [t] (not [t] itself), roughly the same length. */
    fun similar(t: Trip, all: List<Trip>): List<Trip> {
        val a0 = t.points.firstOrNull() ?: return emptyList()
        val a1 = t.points.last()
        return all.filter { o ->
            if (o.id == t.id || o.points.size < 10) return@filter false
            val b0 = o.points.first(); val b1 = o.points.last()
            metres(a0.lat, a0.lon, b0.lat, b0.lon) < ENDPOINT_M && metres(a1.lat, a1.lon, b1.lat, b1.lon) < ENDPOINT_M &&
                o.distanceMeters in t.distanceMeters * 0.6..t.distanceMeters * 1.6
        }
    }

    fun compare(t: Trip, all: List<Trip>): Result? {
        val today = metrics(t) ?: return null
        val others = similar(t, all).mapNotNull { metrics(it) }
        if (others.size < MIN_OTHERS) return null
        val usual = Metrics(
            median(others.map { it.totalS.toDouble() }).toInt(),
            median(others.map { it.stillS.toDouble() }).toInt(),
            median(others.map { it.stops.toDouble() }).toInt(),
            median(others.map { it.movingKmh }),
            median(others.map { it.km }),
        )
        val diff = today.totalS - usual.totalS
        val stillDiff = today.stillS - usual.stillS
        // Moving time if today's distance had been ridden at the usual pace, vs what it really took.
        val usualPace = usual.movingKmh.takeIf { it > 1 } ?: today.movingKmh
        val todayMovingS = today.totalS - today.stillS
        val atUsualPaceS = if (usualPace > 1) (today.km / usualPace * 3600).toInt() else todayMovingS
        val paceDiff = todayMovingS - atUsualPaceS
        val routeDiff = diff - stillDiff - paceDiff
        val parts = listOf(Factor.STILL to stillDiff, Factor.PACE to paceDiff, Factor.ROUTE to routeDiff)
        val main = parts.filter { abs(it.second) >= SIGNIFICANT_S && (it.second > 0) == (diff > 0) }
            .maxByOrNull { abs(it.second) }?.first
        return Result(today, usual, others.size, diff, stillDiff, paceDiff, routeDiff, main)
    }

    fun significant(s: Int) = abs(s) >= SIGNIFICANT_S

    private fun median(v: List<Double>): Double = v.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }

    private fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val x = Math.toRadians(lon2 - lon1) * cos(Math.toRadians((lat1 + lat2) / 2))
        val y = Math.toRadians(lat2 - lat1)
        return sqrt(x * x + y * y) * 6_371_000.0
    }
}
