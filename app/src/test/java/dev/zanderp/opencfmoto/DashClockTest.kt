package dev.zanderp.opencfmoto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashClockTest {
    @Test
    fun nameLooksLikeDash_mlnAndSoftAp() {
        assertTrue(DashClock.nameLooksLikeDash("MLN05D250"))
        assertTrue(DashClock.nameLooksLikeDash("MLN_p2p_7017"))
        assertTrue(DashClock.nameLooksLikeDash("ZM_CB42"))
        assertTrue(DashClock.nameLooksLikeDash("VOGE-006348"))
        assertTrue(DashClock.nameLooksLikeDash("MURTAS-RRX"))
        assertTrue(DashClock.nameLooksLikeDash("RRX-2026"))
        assertFalse(DashClock.nameLooksLikeDash("WH-1000XM5"))
        assertFalse(DashClock.nameLooksLikeDash("Galaxy Watch"))
        assertFalse(DashClock.nameLooksLikeDash(null))
    }
}
