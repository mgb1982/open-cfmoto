package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GreenWaveTest {

    /** A fixed-time light: cycle 90 s, green from second 20 to 60 of each cycle. */
    private fun ride(rnd: Random, tApproachS: Long, cycle: Int = 90, greenFrom: Int = 20, greenLen: Int = 40): GreenWave.Pass {
        val v = 12f                       // ~43 km/h
        val d = 300.0
        val arrive = tApproachS + d / v
        val ph = ((arrive - greenFrom) % cycle + cycle) % cycle
        return if (ph < greenLen) {
            GreenWave.Pass((arrive * 1000).toLong(), false, 0L, 0L, tApproachS * 1000, d, v)
        } else {
            val green = arrive + (cycle - ph)
            val off = green + 2 + rnd.nextInt(0, 3)    // reaction / queue
            GreenWave.Pass((off * 1000).toLong(), true, (arrive * 1000).toLong(), (off * 1000).toLong(), tApproachS * 1000, d, v)
        }
    }

    private fun rides(n: Int, seed: Int = 1, cycle: Int = 90): List<GreenWave.Pass> {
        val rnd = Random(seed)
        val base = 1_760_000_000L
        return (0 until n).map { i -> ride(rnd, base + i * 86_400L + rnd.nextLong(0, 3600), cycle) }
    }

    @Test fun findsTheCycle() {
        val m = GreenWave.fit(rides(40))
        assertNotNull(m)
        assertEquals(90, m!!.cycleS)
        assertNotNull(m.greenLenS)
    }

    @Test fun predictsAFixedTimeLightWell() {
        val sc = GreenWave.evaluate(rides(60))
        assertTrue("predicted ${sc.predicted}", sc.predicted >= 10)
        assertTrue("accuracy ${sc.accuracy}", sc.accuracy >= 0.85f)
        assertTrue("soon ${sc.soonMedianS}", (sc.soonMedianS ?: 99) <= 5)
    }

    @Test fun refusesRandomLights() {
        val rnd = Random(7)
        val base = 1_760_000_000L
        val passes = (0 until 40).map { i ->
            val t = base + i * 86_400L + rnd.nextLong(0, 3600)
            val off = t + 25 + rnd.nextInt(0, 120)
            GreenWave.Pass(off * 1000, true, (t + 25) * 1000, off * 1000, t * 1000, 300.0, 12f)
        }
        assertNull(GreenWave.fit(passes))
    }

    @Test fun nextGreenIsAfterTheStop() {
        val m = GreenWave.Model(90, 0.9f, 20.0, 40.0)
        val g = GreenWave.nextGreen(m, 1_000_000L * 1000 + 70_000)
        assertTrue(g >= 1_000_070.0 && g < 1_000_070.0 + 90)
        assertEquals(0.0, GreenWave.phase(g, 20.0, 90), 1e-6)
    }
}
