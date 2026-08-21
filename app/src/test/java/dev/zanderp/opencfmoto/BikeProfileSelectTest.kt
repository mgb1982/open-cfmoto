package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Smoke-checks for the 1.0.11 profile registry: Nk800 claims known QR modelIds and returns
 * false for CFDL26 ids; 1000 MT-X stays non-touch (1.0.9). Full CLIENT_INFO scoring is exercised
 * on-device (host JVM lacks a real android.org.json).
 */
class BikeProfileSelectTest {

    @Test
    fun nk800MatchesKnownModelIdsOnly() {
        assertTrue(Nk800Profile.matchesModelId("66660703"))
        assertTrue(Nk800Profile.matchesModelId("66660721"))
        assertTrue(Nk800Profile.matchesModelId("66660732"))
        assertFalse(Nk800Profile.matchesModelId("37426"))
        assertFalse(Nk800Profile.matchesModelId("37416"))
    }

    @Test
    fun selectByModelIdPrefersNk800ForItsIds() {
        assertEquals(Nk800Profile, BikeProfiles.selectByModelId("66660703"))
        // 37426 is shared by NK Advanced touch, landscape 800MT, and portrait MT-X —
        // selectByModelId returns the first registry hit (Cfdl26NkTouchProfile).
        assertEquals(Cfdl26NkTouchProfile, BikeProfiles.selectByModelId("37426"))
    }

    @Test
    fun xcape1200StillsIsOverrideOnly() {
        assertTrue(Xcape1200StillsProfile.yunmoJpegStills)
        assertEquals(10, Xcape1200StillsProfile.videoFrameRate)
        assertFalse(MoriniMlSoftApProfile.yunmoJpegStills)
        assertFalse(LegacyCfdl16Profile.yunmoJpegStills)
        assertFalse(Xcape1200StillsProfile.matchesModelId("21333"))
        assertEquals(Xcape1200StillsProfile, ProfileOverride.XCAPE_1200.resolve())
        assertEquals(null, ProfileOverride.AUTO.resolve())
    }

    @Test
    fun portraitMtxStaysNonTouch() {
        assertFalse(Cfdl26PortraitProfile.supportsScreenTouch)
        assertTrue(Cfdl26LandscapeProfile.supportsScreenTouch)
        assertFalse(Nk800Profile.supportsScreenTouch)
    }

    @Test
    fun buttonDefaultsMatchHandlebarLayout() {
        // Universal all-bike model: single = knob, ×2 = D-pad ←/→, Select = OK / ×2 Back.
        assertEquals(ButtonAction.KNOB_BACK, ButtonGesture.NAV_BACK.default)
        assertEquals(ButtonAction.KNOB_FORWARD, ButtonGesture.NAV_FWD.default)
        assertEquals(ButtonAction.SELECT, ButtonGesture.SELECT_PRESS.default)
        assertEquals(ButtonAction.NONE, ButtonGesture.NAV_BACK_LONG.default)
        assertEquals(ButtonAction.NONE, ButtonGesture.NAV_FWD_LONG.default)
        assertEquals(ButtonAction.HOME, ButtonGesture.SELECT_LONG.default)
        assertEquals(ButtonAction.DPAD_LEFT, ButtonGesture.NAV_BACK_DOUBLE.default)
        assertEquals(ButtonAction.DPAD_RIGHT, ButtonGesture.NAV_FWD_DOUBLE.default)
        assertEquals(ButtonAction.BACK, ButtonGesture.SELECT_DOUBLE.default)
    }

    @Test
    fun backSetPresetUsesVolumeDoublesForBackHome() {
        val m = ButtonClusterPreset.BACK_SET.mapping()
        assertEquals(ButtonAction.KNOB_BACK, m[ButtonGesture.NAV_BACK])
        assertEquals(ButtonAction.KNOB_FORWARD, m[ButtonGesture.NAV_FWD])
        assertEquals(ButtonAction.SELECT, m[ButtonGesture.SELECT_PRESS])
        assertEquals(ButtonAction.NONE, m[ButtonGesture.SELECT_LONG])
        assertEquals(ButtonAction.NONE, m[ButtonGesture.NAV_BACK_LONG])
        assertEquals(ButtonAction.NONE, m[ButtonGesture.NAV_FWD_LONG])
        assertEquals(ButtonAction.NONE, m[ButtonGesture.SELECT_DOUBLE])
        assertEquals(ButtonAction.BACK, m[ButtonGesture.NAV_BACK_DOUBLE])
        assertEquals(ButtonAction.HOME, m[ButtonGesture.NAV_FWD_DOUBLE])
        assertTrue(ButtonClusterPreset.BACK_SET.instantSingles)
        assertTrue(ButtonClusterPreset.FIVE_WAY.instantSingles)
    }

    @Test
    fun fiveWayPresetMatchesShippedDefaults() {
        val m = ButtonClusterPreset.FIVE_WAY.mapping()
        for (g in ButtonGesture.entries) {
            assertEquals(g.default, m[g])
        }
    }
}
