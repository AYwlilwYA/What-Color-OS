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

    /** 展开边照片角恒等于传入自转角，且 spinSpeed=1 让调用方继续推进自转。 */
    @Test
    fun entryTracksSpinAndKeepsSpinning() {
        val pose = SailLensMotion.Pose()
        for (edge in listOf(TransitionEdge.AOD_TO_LOCK, TransitionEdge.AOD_TO_HOME)) {
            for (i in 0..10) {
                val p = i / 10f
                val spin = -137f - p * 38f
                SailLensMotion.sample(edge, p, 0.5f, 0.435f, 1.24f, pose, spinDeg = spin)
                assertEquals("edge=$edge p=$p 角度必须跟随自转", spin, pose.angleDeg, 1e-3f)
                assertEquals("edge=$edge p=$p 展开期必须继续自转", 1f, pose.spinSpeed, 1e-3f)
            }
        }
    }

    /** 阳性对照：传入不同自转角时输出必须不同（证明不是写死常量）。 */
    @Test
    fun entryAngleRespondsToSpinInput() {
        val pose = SailLensMotion.Pose()
        SailLensMotion.sample(TransitionEdge.AOD_TO_LOCK, 0.5f, 0.5f, 0.435f, 1.24f, pose, spinDeg = -100f)
        val a = pose.angleDeg
        SailLensMotion.sample(TransitionEdge.AOD_TO_LOCK, 0.5f, 0.5f, 0.435f, 1.24f, pose, spinDeg = -260f)
        assertTrue("换自转角输出必须变", kotlin.math.abs(a - pose.angleDeg) > 100f)
    }

    /** 阳性对照：旧口径边末补到最近整圈（-497），与冻结值必须不同。 */
    @Test
    fun entryFreezeDiffersFromOldNearestRotation() {
        val frozen = -137f
        val oldSettled = -497f
        assertTrue("冻结值必须不等于旧的最近整圈值，否则是假修复", frozen != oldSettled)
    }

    @Test
    fun exitFreezesPhotoAngleAtEntryValue() {
        val pose = SailLensMotion.Pose()
        SailLensMotion.sample(TransitionEdge.LOCK_TO_AOD, 0.3f, 0.5f, 0.435f, 1.24f, pose, spinDeg = -137f)
        assertEquals(-137f, pose.angleDeg, 1e-3f)
        SailLensMotion.sample(TransitionEdge.LOCK_TO_AOD, 0.9f, 0.5f, 0.435f, 1.24f, pose, spinDeg = -137f)
        assertEquals(-137f, pose.angleDeg, 1e-3f)
        SailLensMotion.sample(TransitionEdge.HOME_TO_AOD, 0.5f, 0.5f, 0.435f, 1.24f, pose, spinDeg = -212f)
        assertEquals(-212f, pose.angleDeg, 1e-3f)
    }

    /** 阳性对照：旧口径归零，与「冻结在进入角」必须不同。 */
    @Test
    fun exitFreezeDiffersFromOldZeroing() {
        val pose = SailLensMotion.Pose()
        SailLensMotion.sample(TransitionEdge.LOCK_TO_AOD, 0.5f, 0.5f, 0.435f, 1.24f, pose, spinDeg = -137f)
        assertTrue("冻结值必须不等于旧的归零", kotlin.math.abs(pose.angleDeg) > 1f)
    }

    /** 锁屏↔桌面两端同等价角，全程零旋转（旧口径在 24ms 内多转一整圈）。 */
    @Test
    fun lockHomeEdgesHaveZeroPhotoRotation() {
        val pose = SailLensMotion.Pose()
        for (edge in listOf(TransitionEdge.LOCK_TO_HOME, TransitionEdge.HOME_TO_LOCK)) {
            var prev = Float.NaN
            for (i in 0..10) {
                val p = i / 10f
                SailLensMotion.sample(edge, p, 0.5f, 0.435f, 1.24f, pose, spinDeg = -360f)
                assertEquals(-360f, pose.angleDeg, 1e-3f)
                if (!prev.isNaN()) assertEquals(prev, pose.angleDeg, 1e-3f)
                prev = pose.angleDeg
            }
        }
    }

    @Test
    fun spinStopsWhenSpeedZero() {
        assertEquals(-137f, SailLensMotion.spin(-137f, 0.5f, 0f), 1e-3f)
        assertTrue(SailLensMotion.spin(-137f, 0.5f, 1f) < -137f)
    }
}
