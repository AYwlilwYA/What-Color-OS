package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SailLensMotion
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.TransitionEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SailLensMotionTest {

    private fun pose() = SailLensMotion.Pose()

    @Test
    fun aodStaticSitsOnAnchor() {
        val p = pose()
        SailLensMotion.sampleStatic(SceneState.AOD, 0.5f, 0.6f, 1.24f, p)
        assertEquals(SailLensMotion.RADIUS, p.scale, 1e-6f)
        assertEquals(SailLensMotion.DISTORTION, p.distortion, 1e-6f)
        assertEquals(SailLensMotion.BLUR, p.blur, 1e-6f)
        assertEquals(SailLensMotion.DECOR_ALPHA, p.decorAlpha, 1e-6f)
        assertEquals(0.5f, p.centerX, 1e-6f)
        assertEquals(0.6f, p.centerY, 1e-6f)
    }

    @Test
    fun aodToLockExpandsToFullScreen() {
        val p = pose()
        SailLensMotion.sampleStatic(SceneState.AOD, 0.5f, 0.6f, 1.24f, p)
        SailLensMotion.sample(TransitionEdge.AOD_TO_LOCK, 0f, 0.5f, 0.6f, 1.24f, p)
        assertEquals(SailLensMotion.RADIUS, p.scale, 1e-6f)
        assertEquals(0.6f, p.centerY, 1e-6f)

        SailLensMotion.sample(TransitionEdge.AOD_TO_LOCK, 1f, 0.5f, 0.6f, 1.24f, p)
        assertEquals(1.24f, p.scale, 1e-6f)
        assertEquals(0.5f, p.centerX, 1e-6f)
        assertEquals(0.5f, p.centerY, 1e-6f)
        assertEquals(0f, p.distortion, 1e-6f)
        assertEquals(0f, p.blur, 1e-6f)
        assertEquals(0f, p.decorAlpha, 1e-6f)
    }

    @Test
    fun expansionKeepsOrdering() {
        val p = pose()
        var lastScale = SailLensMotion.RADIUS
        for (i in 0..20) {
            SailLensMotion.sample(TransitionEdge.AOD_TO_LOCK, i / 20f, 0.5f, 0.6f, 1.24f, p)
            assertTrue(p.scale >= lastScale - 1e-5f)
            lastScale = p.scale
        }
    }

    @Test
    fun lockReturnsToLens() {
        val lens = pose()
        SailLensMotion.sampleStatic(SceneState.LOCK, 0.5f, 0.6f, 1.24f, lens)
        assertEquals(1.24f, lens.scale, 1e-6f)
        assertEquals(0f, lens.blur, 1e-6f)
        assertEquals(0f, lens.decorAlpha, 1e-6f)

        val p = pose()
        SailLensMotion.sample(TransitionEdge.LOCK_TO_AOD, 1f, 0.5f, 0.6f, 1.24f, p)
        assertEquals(SailLensMotion.RADIUS, p.scale, 1e-4f)
        assertEquals(SailLensMotion.BLUR, p.blur, 1e-3f)
        assertEquals(SailLensMotion.DISTORTION, p.distortion, 1e-3f)
        assertEquals(0.6f, p.centerY, 1e-4f)
    }

    @Test
    fun timelineAnchorsMatchStates() {
        val p = pose()
        SailLensMotion.sampleTimeline(0f, 0.5f, 0.6f, 1.24f, p)
        assertEquals(SailLensMotion.RADIUS, p.scale, 1e-4f)

        SailLensMotion.sampleTimeline(0.5f, 0.5f, 0.6f, 1.24f, p)
        assertEquals(1.24f, p.scale, 1e-4f)
        assertEquals(0f, p.blur, 1e-4f)

        SailLensMotion.sampleTimeline(1f, 0.5f, 0.6f, 1.24f, p)
        assertEquals(1.24f, p.scale, 1e-4f)
    }

    @Test
    fun expandedKeepsDecorAtRestAngle() {
        val p = pose()
        SailLensMotion.sampleStatic(SceneState.LOCK, 0.5f, 0.6f, 1.24f, p)
        assertEquals(SailLensMotion.DECOR_ANGLE_DEG, p.decorAngleDeg, 1e-6f)
        SailLensMotion.sampleStatic(SceneState.AOD, 0.5f, 0.6f, 1.24f, p)
        assertEquals(0f, p.decorAngleDeg, 1e-6f)
    }

    @Test
    fun exitReturnsDecorToUpright() {
        val p = pose()
        SailLensMotion.sample(TransitionEdge.LOCK_TO_AOD, 0f, 0.5f, 0.6f, 1.24f, p)
        assertEquals(SailLensMotion.DECOR_ANGLE_DEG, p.decorAngleDeg, 1e-4f)
        SailLensMotion.sample(TransitionEdge.LOCK_TO_AOD, 1f, 0.5f, 0.6f, 1.24f, p)
        assertEquals(0f, p.decorAngleDeg, 1e-3f)
    }

    @Test
    fun expandedScaleFollowsAspect() {
        assertEquals(1.3f, SailLensMotion.expandedScale(2.33f), 1e-6f)
        assertEquals(1.24f, SailLensMotion.expandedScale(2.17f), 1e-6f)
    }

    @Test
    fun spinTurnsAtFortyDegreesPerSecond() {
        assertEquals(-40f, SailLensMotion.spin(0f, 1f), 1e-4f)
        assertEquals(-20f, SailLensMotion.spin(0f, 0.5f), 1e-4f)
    }

    @Test
    fun spinWrapsAtFullTurn() {
        val next = SailLensMotion.spin(-359f, 0.1f)
        assertEquals(-363f + 360f, next, 1e-4f)
    }

    private fun fade() = SailLensMotion.Fade()

    @Test
    fun entryFadeEndpoints() {
        val f = fade()
        SailLensMotion.fade(TransitionEdge.AOD_TO_LOCK, 0f, f)
        assertEquals(1f, f.aodAlpha, 1e-4f)
        assertEquals(0f, f.contentGate, 1e-4f)
        assertEquals(1f, f.aodClockAlpha, 1e-4f)
        SailLensMotion.fade(TransitionEdge.AOD_TO_LOCK, 1f, f)
        assertEquals(0f, f.aodAlpha, 1e-4f)
        assertEquals(1f, f.contentGate, 1e-4f)
        assertEquals(0f, f.aodClockAlpha, 1e-4f)
    }

    @Test
    fun exitFadeEndpoints() {
        val f = fade()
        SailLensMotion.fade(TransitionEdge.LOCK_TO_AOD, 0f, f)
        assertEquals(0f, f.aodAlpha, 1e-4f)
        assertEquals(1f, f.contentGate, 1e-4f)
        SailLensMotion.fade(TransitionEdge.LOCK_TO_AOD, 1f, f)
        assertEquals(1f, f.aodAlpha, 1e-4f)
        assertEquals(0f, f.contentGate, 1e-4f)
    }

    @Test
    fun contentFadeIsEasedNotLinear() {
        val f = fade()
        SailLensMotion.fade(TransitionEdge.AOD_TO_LOCK, 0.55f + 0.25f * 0.37f, f)
        assertTrue("先慢后快，前 25% 窗口应低于线性", f.contentGate < 0.25f)
        SailLensMotion.fade(TransitionEdge.AOD_TO_LOCK, 0.55f + 0.75f * 0.37f, f)
        assertTrue("后段应快于线性收尾", f.contentGate > 0.75f)
    }

    @Test
    fun aodFadeIsEasedNotLinear() {
        val f = fade()
        SailLensMotion.fade(TransitionEdge.AOD_TO_LOCK, 0.55f + 0.25f * 0.35f, f)
        assertTrue("息屏层前段衰减应慢于线性", f.aodAlpha > 0.75f)
        SailLensMotion.fade(TransitionEdge.AOD_TO_LOCK, 0.55f + 0.75f * 0.35f, f)
        assertTrue("息屏层后段应快于线性", f.aodAlpha < 0.25f)
    }

    @Test
    fun lensZoomFollowsOfficialRadiusFormula() {
        assertEquals(1f / (0.22f * 4f), SailLensMotion.lensZoom(0.22f), 1e-4f)
        assertEquals(0.5f, SailLensMotion.lensZoom(1.24f), 1e-4f)
        assertEquals(0.5f, SailLensMotion.lensZoom(0.5f), 1e-4f)
    }

    @Test
    fun nearestRotationPicksShortestEquivalent() {
        assertEquals(-497f, SailLensMotion.nearestRotation(-137f, -360f), 1e-3f)
        assertEquals(-350f, SailLensMotion.nearestRotation(-350f, -360f), 1e-3f)
        assertEquals(-10f, SailLensMotion.nearestRotation(-10f, 0f), 1e-3f)
    }

    @Test
    fun blurClearFadesWholeScreenOnlyAcrossLockHome() {
        assertEquals(1f, SailLensMotion.blurClearAlpha(TransitionEdge.LOCK_TO_HOME, 0f), 1e-4f)
        assertEquals(0f, SailLensMotion.blurClearAlpha(TransitionEdge.LOCK_TO_HOME, 0.6f), 1e-4f)
        assertEquals(0f, SailLensMotion.blurClearAlpha(TransitionEdge.HOME_TO_LOCK, 0.4f), 1e-4f)
        assertEquals(1f, SailLensMotion.blurClearAlpha(TransitionEdge.HOME_TO_LOCK, 1f), 1e-4f)
        assertEquals(0f, SailLensMotion.blurClearAlpha(TransitionEdge.AOD_TO_LOCK, 0.5f), 1e-4f)
    }

    @Test
    fun entrySettlesPhotoUprightAtEnd() {
        val pose = SailLensMotion.Pose()
        SailLensMotion.sample(TransitionEdge.AOD_TO_LOCK, 0f, 0.5f, 0.435f, 1.24f, pose, spinDeg = -137f)
        assertEquals(-137f, pose.angleDeg, 1e-3f)
        SailLensMotion.sample(TransitionEdge.AOD_TO_LOCK, 1f, 0.5f, 0.435f, 1.24f, pose, spinDeg = -137f)
        assertEquals(-497f, pose.angleDeg, 1e-3f)
    }

    @Test
    fun exitKeepsPhotoAngleConstant() {
        val pose = SailLensMotion.Pose()
        SailLensMotion.sample(TransitionEdge.LOCK_TO_AOD, 0.3f, 0.5f, 0.435f, 1.24f, pose, spinDeg = -137f)
        assertEquals(0f, pose.angleDeg, 1e-3f)
        SailLensMotion.sample(TransitionEdge.LOCK_TO_AOD, 0.9f, 0.5f, 0.435f, 1.24f, pose, spinDeg = -137f)
        assertEquals(0f, pose.angleDeg, 1e-3f)
    }

    @Test
    fun spinStopsWhenSpeedZero() {
        assertEquals(-137f, SailLensMotion.spin(-137f, 0.5f, 0f), 1e-3f)
        assertTrue(SailLensMotion.spin(-137f, 0.5f, 1f) < -137f)
    }
}
