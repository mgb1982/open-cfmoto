package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ClockLabTest {
    @Before
    fun resetDefaults() {
        ClockLab.applyFrom(ClockQueryMode.EMPTY, ClockTimeSyncMode.ECHO, false)
    }

    @Test
    fun defaultsMatchLatest() {
        assertEquals(ClockLabPreset.LATEST, ClockLab.matchingPreset())
        assertEquals(
            "[CLOCK-LAB] query=empty timeSync=echo bt=off channel=-",
            ClockLab.banner(null),
        )
    }

    @Test
    fun presetsFillKnobs() {
        ClockLab.applyPreset(ClockLabPreset.V2012)
        assertEquals(ClockQueryMode.CARBIT, ClockLab.query)
        assertEquals(ClockTimeSyncMode.ECHO, ClockLab.timeSync)
        assertEquals(false, ClockLab.bluetooth)
        assertEquals(ClockLabPreset.V2012, ClockLab.matchingPreset())

        ClockLab.applyPreset(ClockLabPreset.ZONTES)
        assertEquals(ClockQueryMode.ZONTES, ClockLab.query)
        assertEquals(ClockLabPreset.ZONTES, ClockLab.matchingPreset())

        ClockLab.applyPreset(ClockLabPreset.PHONE_SYNC)
        assertEquals(ClockTimeSyncMode.PHONE, ClockLab.timeSync)
        assertEquals(ClockLabPreset.PHONE_SYNC, ClockLab.matchingPreset())

        ClockLab.applyPreset(ClockLabPreset.BT_LISTEN)
        assertEquals(true, ClockLab.bluetooth)
        assertEquals(ClockLabPreset.BT_LISTEN, ClockLab.matchingPreset())
    }

    @Test
    fun mixedKnobsHaveNoPreset() {
        ClockLab.applyFrom(ClockQueryMode.ZONTES, ClockTimeSyncMode.PHONE, true)
        assertNull(ClockLab.matchingPreset())
        assertEquals(
            "[CLOCK-LAB] query=zontes timeSync=phone bt=on channel=21340",
            ClockLab.banner("21340"),
        )
    }
}
