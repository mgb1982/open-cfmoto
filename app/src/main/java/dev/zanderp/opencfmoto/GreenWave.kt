// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * v2.3 · 🌊 Green wave, *observation mode*. Nothing is shown on the dash yet: this only measures how
 * well the light of one approach can be predicted from the rider's own trips.
 *
 * Model (per approach of a junction, see [TrafficLights]):
 *  - cycle C: the period (60–150 s) that best lines up the move-off times after a red stop;
 *  - green start G0: the mean phase of those move-offs (≈ green + reaction / queue delay);
 *  - green length L: how far into the cycle the rider still went through without stopping.
 *
 * Evaluation is chronological, as the live feature would work: each pass is predicted only from the
 * passes *before* it. Two questions are scored:
 *  1. "Will you make the green?" from ~300 m out at the speed you had there (stopped = it was red).
 *  2. "Green soon" while stopped: predicted green vs the moment the bike actually moved off.
 * Pure Kotlin, no Android, so it is unit-tested.
 */
object GreenWave {
    const val APPROACH_M = 300.0
    private const val MIN_STOPS = 6
    private const val MIN_GREEN_PASSES = 3
    private const val MIN_FIT = 0.7
    private const val MARGIN_S = 3.0
    private const val MIN_SPEED_MS = 4f          // slower than ~15 km/h: traffic, no prediction
    const val READY_PREDICTIONS = 5
    const val READY_ACCURACY = 0.8f

    /** One ride through the junction from one direction. Times in epoch ms. */
    data class Pass(
        val tCross: Long,            // closest point to the junction (or move-off if stopped)
        val stopped: Boolean,
        val stopStart: Long = 0L,
        val moveOff: Long = 0L,
        val tApproach: Long = 0L,    // when it was ~[APPROACH_M] away (0 = unknown)
        val dApproachM: Double = 0.0,
        val vApproachMs: Float = 0f,
    )

    data class Model(val cycleS: Int, val fit: Float, val greenStartS: Double, val greenLenS: Double?)

    class Score {
        var predicted = 0
        var correct = 0
        var unsure = 0
        val soonErrorsS = ArrayList<Double>()
        val accuracy: Float get() = if (predicted == 0) 0f else correct.toFloat() / predicted
        val soonMedianS: Int? get() = if (soonErrorsS.isEmpty()) null else soonErrorsS.map { abs(it) }.sorted()[soonErrorsS.size / 2].toInt()
        val ready: Boolean get() = predicted >= READY_PREDICTIONS && accuracy >= READY_ACCURACY
    }

    fun fit(passes: List<Pass>): Model? {
        val offs = passes.filter { it.stopped && it.moveOff > 0 }.map { it.moveOff / 1000.0 }
        if (offs.size < MIN_STOPS) return null
        var bestC = 0; var bestR = 0.0; var bestPh = 0.0
        for (c in 60..150) {
            var sx = 0.0; var sy = 0.0
            for (t in offs) { val ph = 2 * PI * ((t % c) / c); sx += cos(ph); sy += sin(ph) }
            val r = hypot(sx, sy) / offs.size
            if (r > bestR + 0.02) { bestR = r; bestC = c; bestPh = atan2(sy, sx) }
        }
        if (bestR < MIN_FIT) return null
        val g0 = ((bestPh / (2 * PI)) * bestC).mod(bestC.toDouble())
        // Where in the cycle riders went straight through: green must last at least that long.
        val through = passes.filter { !it.stopped }.map { phase(it.tCross / 1000.0, g0, bestC) }.sorted()
        val len = if (through.size >= MIN_GREEN_PASSES)
            (through[((through.size - 1) * 0.9).toInt()] + MARGIN_S).coerceIn(10.0, bestC - 10.0) else null
        return Model(bestC, bestR.toFloat(), g0, len)
    }

    /** Seconds since the (estimated) green started, 0 until C. */
    fun phase(tS: Double, g0: Double, c: Int): Double = (tS - g0).mod(c.toDouble())

    /** true = green on arrival, false = red, null = too close to call / can't predict. */
    fun predictArrival(m: Model, p: Pass): Boolean? {
        val len = m.greenLenS ?: return null
        if (p.tApproach == 0L || p.vApproachMs < MIN_SPEED_MS || p.dApproachM <= 0) return null
        val eta = p.tApproach / 1000.0 + p.dApproachM / p.vApproachMs
        val x = phase(eta, m.greenStartS, m.cycleS)
        return when {
            x <= len - MARGIN_S -> true
            x >= len + MARGIN_S && x <= m.cycleS - MARGIN_S -> false
            else -> null
        }
    }

    /** Predicted green after stopping at [stopStartMs], in epoch seconds. */
    fun nextGreen(m: Model, stopStartMs: Long): Double {
        val s = stopStartMs / 1000.0
        return s + (m.cycleS - phase(s, m.greenStartS, m.cycleS)).mod(m.cycleS.toDouble())
    }

    /** Chronological replay: each pass scored with a model built only from earlier passes. */
    fun evaluate(passes: List<Pass>): Score {
        val sorted = passes.sortedBy { it.tCross }
        val score = Score()
        for (k in sorted.indices) {
            val m = fit(sorted.subList(0, k)) ?: continue
            val p = sorted[k]
            when (val green = predictArrival(m, p)) {
                null -> if (m.greenLenS != null && p.tApproach != 0L && p.vApproachMs >= MIN_SPEED_MS) score.unsure++
                else -> { score.predicted++; if (green == !p.stopped) score.correct++ }
            }
            if (p.stopped && p.moveOff > 0 && p.stopStart > 0) {
                var err = p.moveOff / 1000.0 - nextGreen(m, p.stopStart)
                if (err > m.cycleS / 2.0) err -= m.cycleS
                if (err < -m.cycleS / 2.0) err += m.cycleS
                score.soonErrorsS += err
            }
        }
        return score
    }
}
