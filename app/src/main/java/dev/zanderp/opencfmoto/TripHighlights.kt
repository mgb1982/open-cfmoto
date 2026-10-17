// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos

/**
 * v2.3 · ✨ "Best of the ride": picks the liveliest stretches of the recorded clips using the
 * ride's own GPS track — accelerations, bends, top speed — plus the arrival, and returns them in
 * order. Pure Kotlin on plain numbers so it's unit-tested ([TripHighlightsTest]).
 */
object TripHighlights {
    /** One recorded clip: when it started (ride time, already sync-corrected) and how long it lasts. */
    data class Source(val startMs: Long, val durationMs: Long)

    /** A piece of clip [clip], from [fromMs] to [toMs] (positions inside that clip). */
    data class Piece(val clip: Int, val fromMs: Long, val toMs: Long) { val lengthMs get() = toMs - fromMs }

    private const val STEP_MS = 1_000L

    fun pick(
        points: List<TrackPoint>, sources: List<Source>,
        targetMs: Long = 45_000L, pieceMs: Long = 5_000L, arrivalMs: Long = 4_000L,
    ): List<Piece> {
        if (sources.isEmpty()) return emptyList()
        val total = sources.sumOf { it.durationMs }
        // Short enough already: the whole thing.
        if (total <= targetMs) return sources.mapIndexed { i, s -> Piece(i, 0L, s.durationMs) }

        data class Cand(val piece: Piece, val score: Double)
        val cands = ArrayList<Cand>()
        sources.forEachIndexed { i, s ->
            var from = 0L
            while (from + pieceMs <= s.durationMs) {
                cands += Cand(Piece(i, from, from + pieceMs), score(points, s.startMs + from, s.startMs + from + pieceMs))
                from += STEP_MS
            }
        }
        val chosen = ArrayList<Piece>()
        var used = 0L
        // The arrival: the last seconds of the last clip.
        val last = sources.lastIndex
        val lastDur = sources[last].durationMs
        val arrival = Piece(last, (lastDur - arrivalMs).coerceAtLeast(0L), lastDur)
        chosen += arrival; used += arrival.lengthMs

        val noData = cands.all { it.score <= 0.0 }
        if (noData) {
            // No GPS overlap: spread evenly over the clips instead.
            val n = ((targetMs - used) / pieceMs).toInt().coerceAtLeast(1)
            val stride = (total / (n + 1)).coerceAtLeast(pieceMs)
            var t = stride / 2
            while (chosen.size - 1 < n && t < total) {
                var acc = 0L
                for ((i, s) in sources.withIndex()) {
                    if (t < acc + s.durationMs) {
                        val from = (t - acc).coerceAtMost((s.durationMs - pieceMs).coerceAtLeast(0L))
                        val p = Piece(i, from, (from + pieceMs).coerceAtMost(s.durationMs))
                        if (chosen.none { overlaps(it, p) }) { chosen += p; used += p.lengthMs }
                        break
                    }
                    acc += s.durationMs
                }
                t += stride
            }
        } else {
            for (c in cands.sortedByDescending { it.score }) {
                if (used + c.piece.lengthMs > targetMs) break
                if (chosen.any { overlaps(it, c.piece, gapMs = 2_000L) }) continue
                chosen += c.piece; used += c.piece.lengthMs
            }
        }
        return chosen.sortedWith(compareBy({ it.clip }, { it.fromMs }))
    }

    private fun overlaps(a: Piece, b: Piece, gapMs: Long = 0L) =
        a.clip == b.clip && a.fromMs < b.toMs + gapMs && b.fromMs < a.toMs + gapMs

    /** Liveliness of the ride between [t0] and [t1] (ride time). 0 when there's no GPS there. */
    fun score(points: List<TrackPoint>, t0: Long, t1: Long): Double {
        val win = points.filter { it.time in (t0 - 1_000L)..(t1 + 1_000L) }
        if (win.size < 2) return 0.0
        val kmh = win.map { it.speedMs * 3.6 }
        val mean = kmh.average()
        var accel = 0.0
        var turn = 0.0
        for (k in 1 until win.size) {
            val dt = ((win[k].time - win[k - 1].time) / 1000.0).coerceAtLeast(0.2)
            accel = maxOf(accel, (kmh[k] - kmh[k - 1]) / dt)          // speeding up (km/h per s)
            if (k >= 2 && win[k].speedMs > 3f) {
                val b1 = bearing(win[k - 2], win[k - 1]); val b2 = bearing(win[k - 1], win[k])
                var d = abs(b2 - b1); if (d > 180) d = 360 - d
                turn += d
            }
        }
        // Standing still at a light is the dullest bit there is.
        if (mean < 5) return 0.01
        return mean + accel * 4.0 + turn * 0.5
    }

    private fun bearing(a: TrackPoint, b: TrackPoint): Double {
        val x = (b.lon - a.lon) * cos(Math.toRadians((a.lat + b.lat) / 2))
        val y = b.lat - a.lat
        return Math.toDegrees(atan2(x, y))
    }
}
