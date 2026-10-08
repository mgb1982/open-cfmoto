// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class TrophiesTest {
    private fun at(y: Int, m: Int, d: Int, h: Int): Long =
        Calendar.getInstance().apply { clear(); set(y, m - 1, d, h, 0) }.timeInMillis

    private fun trip(start: Long, km: Double, kmh: Int = 60, minutes: Int = 30) = Trip(
        id = start.toString(), start = start, end = start + minutes * 60_000L,
        distanceMeters = km * 1000, movingTimeMs = minutes * 60_000L, maxSpeedMs = kmh / 3.6f,
        points = emptyList(),
    )

    private fun List<Trophies.Trophy>.get(f: Trophies.Family, target: Int) =
        first { it.family == f && it.target == target }

    @Test fun distanceUnlocksAtTheTripThatCrossesIt() {
        val t1 = trip(at(2026, 10, 1, 10), 60.0)
        val t2 = trip(at(2026, 10, 5, 10), 50.0)
        val all = Trophies.compute(listOf(t2, t1)) // order must not matter
        assertEquals(t2.end, all.get(Trophies.Family.DISTANCE, 100).unlockedAt)
        assertNull(all.get(Trophies.Family.DISTANCE, 500).unlockedAt)
        assertEquals(110.0, all.get(Trophies.Family.DISTANCE, 500).progress, 0.01)
    }

    @Test fun streakNeedsConsecutiveDays() {
        val days = listOf(1, 2, 3, 5).map { trip(at(2026, 10, it, 10), 5.0) }
        val all = Trophies.compute(days)
        assertTrue(all.get(Trophies.Family.STREAK, 3).unlocked)
        assertFalse(all.get(Trophies.Family.STREAK, 7).unlocked)
    }

    @Test fun twoRidesSameDayDontBreakStreak() {
        val trips = listOf(
            trip(at(2026, 10, 1, 9), 5.0), trip(at(2026, 10, 1, 18), 5.0),
            trip(at(2026, 10, 2, 9), 5.0), trip(at(2026, 10, 3, 9), 5.0),
        )
        assertTrue(Trophies.compute(trips).get(Trophies.Family.STREAK, 3).unlocked)
    }

    @Test fun weekendNeedsSatAndSunOfSameWeekend() {
        // 3 Oct 2026 is a Saturday.
        val sat = trip(at(2026, 10, 3, 10), 10.0)
        val nextSun = trip(at(2026, 10, 11, 10), 10.0)
        assertFalse(Trophies.compute(listOf(sat, nextSun)).get(Trophies.Family.WEEKEND, 1).unlocked)
        val sun = trip(at(2026, 10, 4, 10), 10.0)
        assertTrue(Trophies.compute(listOf(sat, sun)).get(Trophies.Family.WEEKEND, 1).unlocked)
    }

    @Test fun gpsSpikeIsNotATopSpeed() {
        val all = Trophies.compute(listOf(trip(at(2026, 10, 1, 10), 10.0, kmh = 240)))
        assertFalse(all.get(Trophies.Family.SPEED, 70).unlocked)
    }

    @Test fun earlyBirdIgnoresShortHops() {
        assertFalse(Trophies.compute(listOf(trip(at(2026, 10, 1, 6), 1.0))).get(Trophies.Family.EARLY, 1).unlocked)
        assertTrue(Trophies.compute(listOf(trip(at(2026, 10, 1, 6), 8.0))).get(Trophies.Family.EARLY, 1).unlocked)
    }
}
