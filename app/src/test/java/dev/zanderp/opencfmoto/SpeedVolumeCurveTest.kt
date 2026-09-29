package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedVolumeCurveTest {
    private val medium = SpeedVolumeCurve(startKmh = 40, kmhPerStep = 20, maxSteps = 3)

    @Test
    fun belowStartIsZero() {
        assertEquals(0, medium.stepsFor(0f))
        assertEquals(0, medium.stepsFor(39.9f))
    }

    @Test
    fun stepsGrowPerBandAndCap() {
        assertEquals(1, medium.stepsFor(40f))
        assertEquals(1, medium.stepsFor(59f))
        assertEquals(2, medium.stepsFor(60f))
        assertEquals(3, medium.stepsFor(80f))
        assertEquals(3, medium.stepsFor(140f))
    }

    @Test
    fun offCurveNeverBoosts() {
        assertEquals(0, SpeedVolumeCurve(0, 1, 0).stepsFor(120f))
    }
}
