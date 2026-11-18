package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SailLensMotion
import com.coloros16.liquidglass.engine.SystemSceneMotion
import com.coloros16.liquidglass.engine.TransitionEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SailLensDurationTest {

    private val pose = SailLensMotion.Pose()

    private fun at(t: Float): SailLensMotion.Pose {
        SailLensMotion.sample(TransitionEdge.LOCK_TO_HOME, t, 0.5f, 0.435f, 1.24f, pose)
        return pose
    }

    @Test
    fun lockToHomeUsesLongestTrackNotSystemDuration() {
        assertEquals(3840L, SailLensMotion.durationMs(TransitionEdge.LOCK_TO_HOME))
        assertTrue(SailLensMotion.durationMs(TransitionEdge.LOCK_TO_HOME) > SystemSceneMotion.LOCK_TO_HOME_MS)
    }

    @Test
    fun blurStillClimbingAfterContentSettles() {
        val contentDoneFraction = SailLensMotion.LOCK_TO_HOME_MOVE_MS / SailLensMotion.LOCK_TO_HOME_MS
        val settled = at(contentDoneFraction).blur
        val end = at(1f).blur
        assertEquals(16f, end, 0.01f)
        assertTrue("blur at content-settle=$settled should be well below 16", settled < 8f)
        assertTrue(at(0.5f).blur > settled)
    }

    @Test
    fun blurIsZeroAtStartAndMonotonic() {
        assertEquals(0f, at(0f).blur, 0.001f)
        var last = -1f
        for (i in 0..40) {
            val b = at(i / 40f).blur
            assertTrue("blur must not decrease at $i", b >= last - 0.0001f)
            last = b
        }
        assertEquals(16f, last, 0.01f)
    }

    @Test
    fun contentScaleIsExpandedThroughout() {
        for (i in 0..10) {
            assertEquals(1.24f, at(i / 10f).scale, 0.0001f)
            assertEquals(0.5f, at(i / 10f).centerX, 0.0001f)
        }
    }

    @Test
    fun photoAngleFinishesWithinFirstPercent() {
        val angleFraction = SailLensMotion.LOCK_TO_HOME_ANGLE_MS / SailLensMotion.LOCK_TO_HOME_MS
        val early = at(angleFraction).angleDeg
        val late = at(1f).angleDeg
        assertEquals(late, early, 0.01f)
        assertTrue(angleFraction < 0.02f)
    }

    @Test
    fun otherEdgesKeepTheirOwnDurations() {
        assertEquals(950L, SailLensMotion.durationMs(TransitionEdge.AOD_TO_LOCK))
        assertEquals(1000L, SailLensMotion.durationMs(TransitionEdge.AOD_TO_HOME))
        assertEquals(650L, SailLensMotion.durationMs(TransitionEdge.LOCK_TO_AOD))
        assertEquals(650L, SailLensMotion.durationMs(TransitionEdge.HOME_TO_AOD))
        assertEquals(SystemSceneMotion.HOME_TO_LOCK_MS, SailLensMotion.durationMs(TransitionEdge.HOME_TO_LOCK))
    }

    @Test
    fun homeToLockBlurIsInstant() {
        SailLensMotion.sample(TransitionEdge.HOME_TO_LOCK, 0f, 0.5f, 0.435f, 1.24f, pose)
        assertEquals(0f, pose.blur, 0.001f)
        SailLensMotion.sample(TransitionEdge.HOME_TO_LOCK, 0.01f, 0.5f, 0.435f, 1.24f, pose)
        assertEquals(0f, pose.blur, 0.001f)
    }

    @Test
    fun directScrubStaysLinearOnBlur() {
        SailLensMotion.sample(TransitionEdge.LOCK_TO_HOME, 0.5f, 0.5f, 0.435f, 1.24f, pose, direct = true)
        assertEquals(8f, pose.blur, 0.01f)
        SailLensMotion.sample(TransitionEdge.LOCK_TO_HOME, 0.25f, 0.5f, 0.435f, 1.24f, pose, direct = true)
        assertEquals(4f, pose.blur, 0.01f)
    }

    @Test
    fun systemContentDurationIsUnchanged() {
        assertEquals(800L, SystemSceneMotion.LOCK_TO_HOME_MS)
        assertEquals(800L, SystemSceneMotion.durationOf(TransitionEdge.LOCK_TO_HOME))
    }
}