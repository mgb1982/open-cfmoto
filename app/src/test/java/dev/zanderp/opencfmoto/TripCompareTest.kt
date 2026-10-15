package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripCompareTest {

    /** A straight 5 km ride north at [kmh], with [stopS] seconds stood still halfway. */
    private fun ride(id: String, start: Long, kmh: Double = 30.0, stopS: Int = 60, km: Double = 5.0): Trip {
        val pts = ArrayList<TrackPoint>()
        val ms = kmh / 3.6
        val movingS = (km * 1000 / ms).toInt()
        var t = start; var lat = 41.38
        val dLat = ms / 111_000.0
        for (s in 0 until movingS) {
            if (s == movingS / 2) repeat(stopS) { pts += TrackPoint(lat, 2.15, t, 0f); t += 1000 }
            pts += TrackPoint(lat, 2.15, t, ms.toFloat()); lat += dLat; t += 1000
        }
        return Trip(id, start, t, km * 1000, movingS * 1000L, ms.toFloat(), pts)
    }

    private fun usual() = (1..4).map { ride("u$it", it * 86_400_000L) }

    @Test fun needsThreeSimilarRides() {
        val today = ride("t", 10 * 86_400_000L)
        assertNull(TripCompare.compare(today, usual().take(2)))
        assertNotNull(TripCompare.compare(today, usual()))
    }

    @Test fun blamesTheStops() {
        val r = TripCompare.compare(ride("t", 10 * 86_400_000L, stopS = 420), usual())!!
        assertTrue("diff ${r.diffS}", r.diffS in 330..390)
        assertEquals(TripCompare.Factor.STILL, r.main)
    }

    @Test fun blamesThePace() {
        val r = TripCompare.compare(ride("t", 10 * 86_400_000L, kmh = 20.0), usual())!!
        assertTrue("diff ${r.diffS}", r.diffS > 250)
        assertEquals(TripCompare.Factor.PACE, r.main)
    }

    @Test fun currentStreakCountsBackFromToday() {
        val day = 86_400_000L
        val now = 100 * day + 12 * 3_600_000L
        val trips = listOf(98, 99, 100, 95).map { ride("d$it", it * day + 10 * 3_600_000L) }
        assertEquals(3, Trophies.currentStreak(trips, now))
        assertEquals(3, Trophies.currentStreak(trips, now + day))      // yesterday still counts
        assertEquals(0, Trophies.currentStreak(trips, now + 2 * day))
    }
}
