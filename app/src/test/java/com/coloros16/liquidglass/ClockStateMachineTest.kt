package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.ClockAnchor
import com.coloros16.liquidglass.engine.ClockStateMachine
import com.coloros16.liquidglass.engine.Easing
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.TransitionEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockStateMachineTest {

    private fun machine(): ClockStateMachine = ClockStateMachine(easing = Easing.LINEAR)

    @Test
    fun stateChangeTweensToTargetAnchor() {
        val m = machine()
        m.setAnchor(SceneState.AOD, ClockAnchor(centerX = 0.5f, centerY = 0.2f), 0L, follow = false)
        m.setAnchor(SceneState.LOCK, ClockAnchor(centerX = 0.5f, centerY = 0.6f, scale = 1.4f), 0L, follow = false)
        m.snapTo(SceneState.AOD, 0L)

        m.setState(SceneState.LOCK, 0L)
        assertEquals(0.2f, m.pose.centerY, 0.001f)
        assertTrue(m.animating)

        m.tick(160L)
        assertEquals(0.4f, m.pose.centerY, 0.01f)
        m.tick(320L)
        assertEquals(0.6f, m.pose.centerY, 0.001f)
        assertEquals(1.4f, m.pose.scale, 0.001f)
        assertFalse(m.animating)
    }

    @Test
    fun anchorChangeWhileIdleFollowsFromCurrentPoseWithoutJump() {
        val m = machine()
        m.setAnchor(SceneState.LOCK, ClockAnchor(centerX = 0.5f, centerY = 0.4f), 0L, follow = false)
        m.snapTo(SceneState.LOCK, 0L)

        m.setAnchor(SceneState.LOCK, ClockAnchor(centerX = 0.5f, centerY = 0.6f), 1_000L)
        m.tick(1_000L)
        assertEquals(0.4f, m.pose.centerY, 0.001f)

        m.tick(1_090L)
        assertEquals(0.5f, m.pose.centerY, 0.01f)
        m.tick(1_180L)
        assertEquals(0.6f, m.pose.centerY, 0.001f)
    }

    @Test
    fun retargetDuringTweenKeepsContinuity() {
        val m = machine()
        m.setAnchor(SceneState.AOD, ClockAnchor(centerY = 0.2f), 0L, follow = false)
        m.setAnchor(SceneState.LOCK, ClockAnchor(centerY = 0.6f), 0L, follow = false)
        m.snapTo(SceneState.AOD, 0L)
        m.setState(SceneState.LOCK, 0L)

        m.tick(160L)
        val before = m.pose.centerY

        m.setAnchor(SceneState.LOCK, ClockAnchor(centerY = 0.8f), 160L)
        m.tick(160L)
        assertEquals(before, m.pose.centerY, 0.001f)

        m.tick(250L)
        assertTrue(m.pose.centerY > before)
        m.tick(340L)
        assertEquals(0.8f, m.pose.centerY, 0.001f)
    }

    @Test
    fun changingStateMidTweenStartsFromCurrentPose() {
        val m = machine()
        m.setAnchor(SceneState.AOD, ClockAnchor(centerY = 0.2f), 0L, follow = false)
        m.setAnchor(SceneState.LOCK, ClockAnchor(centerY = 0.6f), 0L, follow = false)
        m.snapTo(SceneState.AOD, 0L)
        m.setState(SceneState.LOCK, 0L)
        m.tick(160L)
        val before = m.pose.centerY

        m.setState(SceneState.AOD, 160L)
        m.tick(160L)
        assertEquals(before, m.pose.centerY, 0.001f)
        m.tick(480L)
        assertEquals(0.2f, m.pose.centerY, 0.001f)
    }

    @Test
    fun transitionProgressInterpolatesBothAnchors() {
        val m = machine()
        m.setAnchor(SceneState.AOD, ClockAnchor(centerX = 0.3f, centerY = 0.2f, scale = 0.8f, alpha = 0.7f), 0L, follow = false)
        m.setAnchor(SceneState.LOCK, ClockAnchor(centerX = 0.5f, centerY = 0.6f, scale = 1.2f, alpha = 1f), 0L, follow = false)

        m.onTransition(TransitionEdge.AOD_TO_LOCK, 0.5f)
        assertEquals(0.4f, m.pose.centerX, 0.001f)
        assertEquals(0.4f, m.pose.centerY, 0.001f)
        assertEquals(1.0f, m.pose.scale, 0.001f)
        assertEquals(0.85f, m.pose.alpha, 0.001f)
        assertEquals(TransitionEdge.AOD_TO_LOCK, m.transitionEdge)
    }

    @Test
    fun transitionEndpointsMatchAnchors() {
        val m = machine()
        m.setAnchor(SceneState.LOCK, ClockAnchor(centerY = 0.6f), 0L, follow = false)
        m.setAnchor(SceneState.AOD, ClockAnchor(centerY = 0.2f), 0L, follow = false)

        m.onTransition(TransitionEdge.LOCK_TO_AOD, 0f)
        assertEquals(0.6f, m.pose.centerY, 0.001f)
        m.onTransition(TransitionEdge.LOCK_TO_AOD, 1f)
        assertEquals(0.2f, m.pose.centerY, 0.001f)
    }

    @Test
    fun homeAnchorCanHideClock() {
        val m = machine()
        m.setAnchor(SceneState.LOCK, ClockAnchor(alpha = 1f), 0L, follow = false)
        m.snapTo(SceneState.LOCK, 0L)
        assertTrue(m.pose.visible)

        m.setState(SceneState.HOME, 0L)
        m.tick(320L)
        assertEquals(0f, m.pose.alpha, 0.001f)
        assertFalse(m.pose.visible)
    }

    @Test
    fun homeAnchorWithDesktopWidgetKeepsClockVisible() {
        val m = machine()
        m.setAnchor(SceneState.LOCK, ClockAnchor(centerX = 0.5f, centerY = 0.35f), 0L, follow = false)
        m.setAnchor(SceneState.HOME, ClockAnchor(centerX = 0.5f, centerY = 0.18f, scale = 1.6f), 0L, follow = false)
        m.snapTo(SceneState.LOCK, 0L)

        m.setState(SceneState.HOME, 0L)
        m.tick(320L)
        assertEquals(0.18f, m.pose.centerY, 0.001f)
        assertEquals(1.6f, m.pose.scale, 0.001f)
        assertTrue(m.pose.visible)
    }

    @Test
    fun homeWidgetMovesWhileIdleFollowsWithoutJump() {
        val m = machine()
        m.setAnchor(SceneState.HOME, ClockAnchor(centerY = 0.2f), 0L, follow = false)
        m.snapTo(SceneState.HOME, 0L)

        m.setAnchor(SceneState.HOME, ClockAnchor(centerY = 0.3f), 1_000L)
        m.tick(1_000L)
        assertEquals(0.2f, m.pose.centerY, 0.001f)
        m.tick(1_180L)
        assertEquals(0.3f, m.pose.centerY, 0.001f)
    }

    @Test
    fun transitionEndReturnsToCurrentStateAnchor() {
        val m = machine()
        m.setAnchor(SceneState.LOCK, ClockAnchor(centerY = 0.6f), 0L, follow = false)
        m.setAnchor(SceneState.AOD, ClockAnchor(centerY = 0.2f), 0L, follow = false)
        m.snapTo(SceneState.LOCK, 0L)

        m.onTransition(TransitionEdge.LOCK_TO_AOD, 1f)
        m.endTransition(0L, animate = false)
        assertEquals(0.6f, m.pose.centerY, 0.001f)
    }

    @Test
    fun anchorUpdateForOtherStateDoesNotMoveCurrent() {
        val m = machine()
        m.setAnchor(SceneState.LOCK, ClockAnchor(centerY = 0.4f), 0L, follow = false)
        m.snapTo(SceneState.LOCK, 0L)

        m.setAnchor(SceneState.AOD, ClockAnchor(centerY = 0.1f), 0L)
        m.tick(0L)
        assertEquals(0.4f, m.pose.centerY, 0.001f)
        assertFalse(m.animating)
    }
}
