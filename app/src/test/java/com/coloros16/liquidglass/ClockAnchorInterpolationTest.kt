package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.ClockAnchor
import com.coloros16.liquidglass.engine.ClockStateMachine
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.SystemSceneMotion
import com.coloros16.liquidglass.engine.TransitionEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockAnchorInterpolationTest {

    private fun machine(): ClockStateMachine {
        val m = ClockStateMachine()
        m.setAnchor(SceneState.AOD, ClockAnchor(0.50f, 0.45f, 1.0f), 0L, follow = false)
        m.setAnchor(SceneState.LOCK, ClockAnchor(0.36f, 0.28f, 1.2f), 0L, follow = false)
        return m
    }

    @Test
    fun defaultAnchorsAreScreenCenterBeforeSync() {
        val m = ClockStateMachine()
        assertEquals(0.5f, m.anchorOf(SceneState.AOD).centerX, 0.0001f)
        assertEquals(0.5f, m.anchorOf(SceneState.AOD).centerY, 0.0001f)
        assertEquals(1f, m.anchorOf(SceneState.AOD).scale, 0.0001f)
    }

    @Test
    fun positionActuallyInterpolatesAfterSync() {
        val m = machine()
        m.onTransition(TransitionEdge.AOD_TO_LOCK, 0f)
        assertEquals(0.50f, m.pose.centerX, 0.0001f)
        assertEquals(0.45f, m.pose.centerY, 0.0001f)
        m.onTransition(TransitionEdge.AOD_TO_LOCK, 1f)
        assertEquals(0.36f, m.pose.centerX, 0.0001f)
        assertEquals(0.28f, m.pose.centerY, 0.0001f)
    }

    @Test
    fun midpointIsBetweenBothAnchors() {
        val m = machine()
        m.onTransition(TransitionEdge.AOD_TO_LOCK, 0.5f, com.coloros16.liquidglass.engine.Easing.LINEAR)
        assertEquals(0.43f, m.pose.centerX, 0.0001f)
        assertEquals(0.365f, m.pose.centerY, 0.0001f)
    }

    @Test
    fun userScaleIsCarriedByAnchorNotAppliedTwice() {
        val m = machine()
        m.onTransition(TransitionEdge.AOD_TO_LOCK, 1f)
        assertEquals(1.2f, m.pose.scale, 0.0001f)
        val systemScale = 1f
        assertEquals(1.2f, m.pose.scale * systemScale, 0.0001f)
    }

    @Test
    fun systemEntryFactorMultipliesUserScaleOnce() {
        val m = machine()
        m.onTransition(TransitionEdge.LOCK_TO_AOD, 1f)
        val entry = SystemSceneMotion.Layers().also {
            SystemSceneMotion.sample(TransitionEdge.LOCK_TO_AOD, 1f, it)
        }
        val combined = m.pose.scale * entry.aodClockScale
        assertTrue("用户 1.0 × 系统进场末值", combined <= 1f)
        assertTrue(combined > 0.9f)
    }

    @Test
    fun reverseEdgeInterpolatesBackToAodAnchor() {
        val m = machine()
        m.onTransition(TransitionEdge.LOCK_TO_AOD, 0f)
        assertEquals(0.36f, m.pose.centerX, 0.0001f)
        m.onTransition(TransitionEdge.LOCK_TO_AOD, 1f)
        assertEquals(0.50f, m.pose.centerX, 0.0001f)
        assertEquals(0.45f, m.pose.centerY, 0.0001f)
    }

    @Test
    fun anchorUpdateWithFollowOffDoesNotStartTween() {
        val m = machine()
        m.snapTo(SceneState.AOD, 0L)
        m.setAnchor(SceneState.AOD, ClockAnchor(0.6f, 0.45f, 1f), 100L, follow = false)
        assertEquals(false, m.animating)
        assertEquals(0.5f, m.pose.centerX, 0.0001f)
    }

    @Test
    fun sameAnchorValuesDoNotNeedRewrite() {
        val m = machine()
        val before = m.anchorOf(SceneState.LOCK)
        m.setAnchor(SceneState.LOCK, ClockAnchor(0.36f, 0.28f, 1.2f), 500L, follow = false)
        assertEquals(before, m.anchorOf(SceneState.LOCK))
    }

    @Test
    fun homeAnchorStaysIndependentOfAodLockSync() {
        val m = machine()
        val home = ClockAnchor(0.13f, 0.021f, 0.16f, 1f)
        m.setAnchor(SceneState.HOME, home, 0L, follow = false)
        m.setAnchor(SceneState.AOD, ClockAnchor(0.7f, 0.45f, 1f), 0L, follow = false)
        assertEquals(home, m.anchorOf(SceneState.HOME))
    }

    @Test
    fun aodToHomeStillInterpolatesTowardsHomeAnchor() {
        val m = machine()
        m.setAnchor(SceneState.HOME, ClockAnchor(0.13f, 0.021f, 0.16f, 0f), 0L, follow = false)
        m.onTransition(TransitionEdge.AOD_TO_HOME, 1f)
        assertEquals(0.13f, m.pose.centerX, 0.0001f)
        assertEquals(0f, m.pose.alpha, 0.0001f)
    }

    @Test
    fun scaleInterpolatesBetweenUserPreferences() {
        val m = machine()
        m.onTransition(TransitionEdge.AOD_TO_LOCK, 0.5f, com.coloros16.liquidglass.engine.Easing.LINEAR)
        assertEquals(1.1f, m.pose.scale, 0.0001f)
    }
}