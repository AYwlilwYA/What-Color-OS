package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SceneCameraLerp
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.SceneTimeline
import com.coloros16.liquidglass.engine.SnowMountainCamera
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneCameraLerpContractTest {

    private fun pose(state: SceneState): SceneCameraLerp.Pose {
        val p = SceneCameraLerp.Pose()
        SnowMountainCamera.target(state, p)
        return p
    }

    @Test
    fun aodCameraIsFartherThanLock() {
        assertEquals(180.0f, SnowMountainCamera.AOD_POS[2], 0.001f)
        assertEquals(64.5f, SnowMountainCamera.LOCK_POS[2], 0.001f)
        assertEquals(2.7907f, SnowMountainCamera.aodToLockZoom(), 0.001f)
    }

    @Test
    fun stateValuesMatchSceneTimelineAnchors() {
        assertEquals(SceneTimeline.AOD, pose(SceneState.AOD).state, 0f)
        assertEquals(SceneTimeline.LOCK, pose(SceneState.LOCK).state, 0f)
        assertEquals(SceneTimeline.HOME, pose(SceneState.HOME).state, 0f)
    }

    @Test
    fun darkStateOnlyOnAod() {
        assertEquals(1f, pose(SceneState.AOD).dark, 0f)
        assertEquals(0f, pose(SceneState.LOCK).dark, 0f)
        assertEquals(0f, pose(SceneState.HOME).dark, 0f)
    }

    @Test
    fun rotValueOnlyOnHome() {
        assertEquals(0f, pose(SceneState.AOD).rot, 0f)
        assertEquals(0f, pose(SceneState.LOCK).rot, 0f)
        assertEquals(1f, pose(SceneState.HOME).rot, 0f)
    }

    @Test
    fun heightZeroOnAodOneElsewhere() {
        assertEquals(0f, pose(SceneState.AOD).height, 0f)
        assertEquals(1f, pose(SceneState.LOCK).height, 0f)
        assertEquals(1f, pose(SceneState.HOME).height, 0f)
    }

    @Test
    fun forwardIsLookMinusPosNotEuler() {
        val p = pose(SceneState.AOD)
        val f = FloatArray(3)
        SnowMountainCamera.forward(p, f)
        assertEquals(2.18f - 79.3f, f[0], 0.001f)
        assertEquals(-9.7f + 36.95f, f[1], 0.001f)
        assertEquals(0.671f - 180.0f, f[2], 0.001f)
        assertNotEquals(-7.9f, f[0])
    }

    @Test
    fun stepMovesTowardTargetByRemainingRatio() {
        val next = SceneCameraLerp.step(0f, 100f, 0.1f)
        assertEquals(25f, next, 0.001f)
    }

    @Test
    fun stepNeverOvershoots() {
        var cur = 0f
        repeat(500) { cur = SceneCameraLerp.step(cur, 100f, 0.016f) }
        assertTrue("cur=$cur", cur <= 100f)
        assertTrue("cur=$cur", cur > 99.9f)
    }

    @Test
    fun iterationMatchesAnalyticalSolution() {
        val dt = 0.001f
        var cur = 180f
        var t = 0f
        repeat(1000) {
            cur = SceneCameraLerp.step(cur, 64.5f, dt)
            t += dt
        }
        val exact = SceneCameraLerp.analytical(180f, 64.5f, t)
        assertEquals(exact, cur, 0.05f)
    }

    @Test
    fun exponentialIsNotLinear() {
        val half = SceneCameraLerp.analytical(0f, 100f, 0.2f)
        assertTrue("half=$half", half > 30f && half < 50f)
        assertNotEquals(50f, half)
    }

    @Test
    fun abnormalDtFallsBack() {
        assertEquals(SceneCameraLerp.DT_FALLBACK, SceneCameraLerp.guardDt(2f), 0.0001f)
        assertEquals(SceneCameraLerp.DT_FALLBACK, SceneCameraLerp.guardDt(Float.NaN), 0.0001f)
        assertEquals(0f, SceneCameraLerp.guardDt(-1f), 0f)
        assertEquals(0.016f, SceneCameraLerp.guardDt(0.016f), 0.0001f)
    }

    @Test
    fun advanceAccumulatesElapsed() {
        val cur = pose(SceneState.AOD)
        val tgt = pose(SceneState.LOCK)
        repeat(10) { SceneCameraLerp.advance(cur, tgt, 0.016f) }
        assertEquals(0.16f, cur.elapsed, 0.0001f)
        assertFalse(SceneCameraLerp.settled(cur))
    }

    @Test
    fun settledAfterThreshold() {
        val cur = pose(SceneState.AOD)
        val tgt = pose(SceneState.LOCK)
        repeat(80) { SceneCameraLerp.advance(cur, tgt, 0.016f) }
        assertTrue("elapsed=${cur.elapsed}", SceneCameraLerp.settled(cur))
    }

    @Test
    fun snapAssignsTargetAndClearsElapsed() {
        val cur = pose(SceneState.AOD)
        val tgt = pose(SceneState.HOME)
        SceneCameraLerp.advance(cur, tgt, 0.016f)
        SceneCameraLerp.advance(cur, tgt, 0.016f, snap = true)
        assertEquals(tgt.px, cur.px, 0f)
        assertEquals(tgt.pz, cur.pz, 0f)
        assertEquals(0f, cur.elapsed, 0f)
    }

    @Test
    fun anyStatePairConvergesWithoutJump() {
        val states = listOf(SceneState.AOD, SceneState.LOCK, SceneState.HOME)
        for (from in states) {
            for (to in states) {
                if (from == to) continue
                val cur = pose(from)
                val tgt = pose(to)
                var last = cur.pz
                repeat(200) {
                    SceneCameraLerp.advance(cur, tgt, 0.016f)
                    assertTrue("$from->$to jumped", kotlin.math.abs(cur.pz - last) < 20f)
                    last = cur.pz
                }
                assertEquals(tgt.pz, cur.pz, 0.05f)
            }
        }
    }

    @Test
    fun remainingRatioShrinksMonotonically() {
        val start = 180f
        val target = 64.5f
        var cur = start
        var last = 1f
        repeat(100) {
            cur = SceneCameraLerp.step(cur, target, 0.016f)
            val r = SceneCameraLerp.remainingRatio(cur, target, start)
            assertTrue("r=$r last=$last", r <= last)
            last = r
        }
        assertTrue(last < 0.05f)
    }

    @Test
    fun remainingRatioGuardsZeroSpan() {
        assertEquals(0f, SceneCameraLerp.remainingRatio(5f, 5f, 5f), 0f)
    }

    @Test
    fun daylightWindowMatchesVendorSeconds() {
        assertFalse(SnowMountainCamera.daylight(21599))
        assertTrue(SnowMountainCamera.daylight(21600))
        assertTrue(SnowMountainCamera.daylight(64799))
        assertFalse(SnowMountainCamera.daylight(64800))
    }

    @Test
    fun officialClockAnchorIsSharedAcrossPacks() {
        assertEquals(0.34f, SnowMountainCamera.CLOCK_X, 0f)
        assertEquals(0.12f, SnowMountainCamera.CLOCK_Y, 0f)
        assertEquals(0.65f, SnowMountainCamera.DUAL_CLOCK_X_RIGHT, 0f)
        assertEquals(0.6f, SnowMountainCamera.DUAL_CLOCK_Y, 0f)
    }
}