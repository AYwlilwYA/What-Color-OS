package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.AodClockEntry
import com.coloros16.liquidglass.engine.ClockAnchor
import com.coloros16.liquidglass.engine.ClockStateMachine
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.SystemSceneMotion
import com.coloros16.liquidglass.engine.TransitionEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LockClockSurfaceTest {

    private fun machine(): ClockStateMachine {
        val m = ClockStateMachine()
        m.setAnchor(SceneState.AOD, ClockAnchor(0.50f, 0.45f, 1.0f), 0L, follow = false)
        m.setAnchor(SceneState.LOCK, ClockAnchor(0.36f, 0.28f, 1.2f), 0L, follow = false)
        return m
    }

    private fun entryScale(edge: TransitionEdge, t: Float): Float {
        val layers = SystemSceneMotion.Layers()
        SystemSceneMotion.sample(edge, t, layers)
        return layers.aodClockScale
    }

    @Test
    fun lockSurfaceIgnoresAodEntryFactor() {
        val m = machine()
        m.onTransition(TransitionEdge.AOD_TO_LOCK, 0.5f)
        val sys = entryScale(TransitionEdge.AOD_TO_LOCK, 0.5f)
        val lockApplied = m.pose.scale * 1f
        assertEquals(m.pose.scale, lockApplied, 0.0001f)
        assertEquals(1f, sys, 0.0001f)
    }

    @Test
    fun aodSurfaceAppliesEntryFactorOnEnteringEdges() {
        val m = machine()
        m.onTransition(TransitionEdge.LOCK_TO_AOD, 0f, com.coloros16.liquidglass.engine.Easing.LINEAR)
        val sys = entryScale(TransitionEdge.LOCK_TO_AOD, 0f)
        val applied = m.pose.scale * sys
        assertEquals(1.2f * 0.85f, applied, 0.0001f)
    }

    @Test
    fun lockSurfaceScaleNeverShrinksBelowAnchor() {
        val m = machine()
        for (i in 0..10) {
            val t = i / 10f
            m.onTransition(TransitionEdge.LOCK_TO_AOD, t, com.coloros16.liquidglass.engine.Easing.LINEAR)
            val lockApplied = m.pose.scale * 1f
            assertTrue(lockApplied >= 1.0f - 0.0001f)
        }
    }

    @Test
    fun aodAndLockAnchorsAreDistinctHosts() {
        val m = machine()
        assertNotEquals(m.anchorOf(SceneState.AOD).centerX, m.anchorOf(SceneState.LOCK).centerX)
        assertNotEquals(m.anchorOf(SceneState.AOD).centerY, m.anchorOf(SceneState.LOCK).centerY)
    }

    @Test
    fun staticLockUsesLockAnchorNotAod() {
        val m = machine()
        val lock = m.anchorOf(SceneState.LOCK)
        assertEquals(0.36f, lock.centerX, 0.0001f)
        assertEquals(0.28f, lock.centerY, 0.0001f)
        assertEquals(1.2f, lock.scale, 0.0001f)
    }

    @Test
    fun tailOnlyAppliesToAodSurface() {
        assertTrue(AodClockEntry.entering(TransitionEdge.LOCK_TO_AOD))
        assertTrue(AodClockEntry.entering(TransitionEdge.HOME_TO_AOD))
        val m = machine()
        val lock = m.anchorOf(SceneState.LOCK)
        val tailScale = AodClockEntry.scaleAt(0.5f)
        assertEquals(lock.scale, lock.scale * 1f, 0.0001f)
        assertTrue(tailScale < 1f)
    }

    @Test
    fun entryFactorIsOneOnNonEnteringEdges() {
        for (edge in listOf(
            TransitionEdge.AOD_TO_LOCK,
            TransitionEdge.LOCK_TO_HOME,
            TransitionEdge.HOME_TO_LOCK,
            TransitionEdge.AOD_TO_HOME,
        )) {
            for (i in 0..5) {
                assertEquals(edge.name, 1f, entryScale(edge, i / 5f), 0.0001f)
            }
        }
    }

    @Test
    fun lockAnchorSurvivesAodEntryAnimation() {
        val m = machine()
        val before = m.anchorOf(SceneState.LOCK)
        m.onTransition(TransitionEdge.LOCK_TO_AOD, 1f)
        assertEquals(before, m.anchorOf(SceneState.LOCK))
    }
}