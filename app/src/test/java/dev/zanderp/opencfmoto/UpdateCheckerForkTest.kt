package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateCheckerForkTest {
    @Test
    fun newerReleaseIsOffered() {
        assertEquals(true, UpdateChecker.isNewerFork("v2", "1", manual = false))
        assertEquals(true, UpdateChecker.isNewerFork("v1.1", "1", manual = false))
    }

    @Test
    fun sameOrOlderReleaseIsNot() {
        assertEquals(false, UpdateChecker.isNewerFork("v1", "1", manual = false))
        assertEquals(false, UpdateChecker.isNewerFork("v1", "1.1", manual = true))
    }

    @Test
    fun devBuildOnlyOnManualCheck() {
        assertEquals(false, UpdateChecker.isNewerFork("v1", "1-dev", manual = false))
        assertEquals(true, UpdateChecker.isNewerFork("v1", "1-dev", manual = true))
    }

    @Test
    fun preRenameBuildsAreOffered() {
        assertEquals(true, UpdateChecker.isNewerFork("v1", "2.0.18-zontes2", manual = false))
    }

    @Test
    fun foreignTagFallsBack() {
        assertNull(UpdateChecker.isNewerFork("zontes-3", "1", manual = false))
        assertNull(UpdateChecker.isNewerFork("2.0.19-pre", "1", manual = false))
    }
}
