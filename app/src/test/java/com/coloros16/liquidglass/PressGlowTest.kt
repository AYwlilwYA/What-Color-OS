package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.PressGlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PressGlowTest {

    private fun held(downAt: Long = 1000L) = PressGlow.State(downAt, null, 10f, 20f)

    @Test
    fun nullStateIsZero() {
        assertEquals(0f, PressGlow.intensity(null, 5000L), 0f)
    }

    @Test
    fun attackStartsAtZeroAndReachesOne() {
        val s = held()
        assertEquals(0f, PressGlow.intensity(s, 1000L), 1e-6f)
        assertEquals(1f, PressGlow.intensity(s, 1000L + PressGlow.ATTACK_MS), 1e-6f)
        assertEquals(1f, PressGlow.intensity(s, 9999L), 1e-6f)
    }

    @Test
    fun attackIsMonotonic() {
        val s = held()
        var prev = -1f
        for (dt in 0..PressGlow.ATTACK_MS step 5) {
            val v = PressGlow.intensity(s, 1000L + dt)
            assertTrue(v >= prev)
            prev = v
        }
    }

    @Test
    fun quickTapReleasesFromPartialIntensity() {
        val down = 1000L
        val up = down + 30L
        val s = PressGlow.State(down, up, 0f, 0f)
        val atRelease = PressGlow.intensity(s, up)
        assertTrue(atRelease > 0f && atRelease < 1f)
        assertTrue(PressGlow.intensity(s, up + 1L) < atRelease)
        assertEquals(0f, PressGlow.intensity(s, up + PressGlow.RELEASE_MS), 1e-6f)
    }

    @Test
    fun releaseIsMonotonicToZero() {
        val s = PressGlow.State(1000L, 2000L, 0f, 0f)
        var prev = 2f
        for (dt in 0..PressGlow.RELEASE_MS step 10) {
            val v = PressGlow.intensity(s, 2000L + dt)
            assertTrue(v <= prev)
            prev = v
        }
        assertEquals(0f, prev, 1e-6f)
    }

    @Test
    fun beforeUpUsesHeldCurve() {
        val s = PressGlow.State(1000L, 5000L, 0f, 0f)
        assertEquals(PressGlow.intensity(held(), 1050L), PressGlow.intensity(s, 1050L), 1e-6f)
    }

    @Test
    fun finishedOnlyAfterReleaseWindow() {
        val s = PressGlow.State(1000L, 2000L, 0f, 0f)
        assertFalse(PressGlow.finished(s, 2000L + PressGlow.RELEASE_MS - 1))
        assertTrue(PressGlow.finished(s, 2000L + PressGlow.RELEASE_MS))
        assertFalse(PressGlow.finished(held(), Long.MAX_VALUE / 2))
    }

    @Test
    fun radiusClampsBothEnds() {
        assertEquals(48f, PressGlow.radiusPx(10f), 0f)
        assertEquals(240f, PressGlow.radiusPx(4000f), 0f)
        assertEquals(108f, PressGlow.radiusPx(240f), 1e-4f)
    }

    @Test
    fun clockBeforeDownIsZero() {
        assertEquals(0f, PressGlow.intensity(held(), 900L), 1e-6f)
    }
}
