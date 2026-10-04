package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateCheckerForkTest {
    @Test
    fun newerZontesReleaseIsOffered() {
        assertEquals(true, UpdateChecker.isNewerFork("zontes-3", "2.0.18-zontes2", manual = false))
    }

    @Test
    fun sameOrOlderZontesReleaseIsNot() {
        assertEquals(false, UpdateChecker.isNewerFork("zontes-2", "2.0.18-zontes2", manual = false))
        assertEquals(false, UpdateChecker.isNewerFork("zontes-1", "2.0.18-zontes2", manual = true))
    }

    @Test
    fun devBuildOnlyOnManualCheck() {
        assertEquals(false, UpdateChecker.isNewerFork("zontes-3", "2.0.18-dev", manual = false))
        assertEquals(true, UpdateChecker.isNewerFork("zontes-3", "2.0.18-dev", manual = true))
    }

    @Test
    fun nonForkTagFallsBack() {
        assertNull(UpdateChecker.isNewerFork("v2.0.19", "2.0.18-zontes2", manual = false))
    }
}
