package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripHighlightsTest {
    /** 10 min ride, 1 point/s, going north; stopped at a light from 200 s to 260 s, a hard
     *  acceleration at 260–270 s and a bend at 400–410 s. */
    private fun ride(): List<TrackPoint> {
        val pts = ArrayList<TrackPoint>()
        var lat = 41.37; var lon = 2.14
        for (s in 0..600) {
            val v = when (s) {
                in 200 until 260 -> 0f
                in 260 until 270 -> (s - 260) * 2.5f
                else -> 12f
            }
            if (s in 400 until 410) lon += 0.0001 * (s - 400) else lat += v / 111_000.0
            pts += TrackPoint(lat, lon, s * 1000L, v)
        }
        return pts
    }

    @Test fun shortClipsAreKeptWhole() {
        val r = TripHighlights.pick(ride(), listOf(TripHighlights.Source(0, 20_000), TripHighlights.Source(100_000, 15_000)))
        assertEquals(2, r.size)
        assertEquals(20_000L, r[0].lengthMs)
    }

    @Test fun picksAroundTheTargetInOrderWithArrivalAndSkipsTheRedLight() {
        val src = listOf(TripHighlights.Source(0, 600_000))
        val r = TripHighlights.pick(ride(), src, targetMs = 45_000)
        val total = r.sumOf { it.lengthMs }
        assertTrue("total $total", total in 30_000..45_000)
        assertEquals(600_000L, r.last().toMs)                       // the arrival is in
        assertTrue(r.zipWithNext().all { (a, b) -> a.toMs <= b.fromMs })   // in order, no overlap
        assertTrue(r.none { it.fromMs in 205_000..250_000 })         // not the wait at the light
        assertTrue(r.any { it.fromMs in 255_000..270_000 })          // the take-off is in
    }

    @Test fun noGpsSpreadsEvenly() {
        val src = listOf(TripHighlights.Source(5_000_000, 300_000))
        val r = TripHighlights.pick(ride(), src, targetMs = 30_000)
        assertTrue(r.size >= 4)
        assertTrue(r.zipWithNext().all { (a, b) -> a.toMs <= b.fromMs })
    }
}
