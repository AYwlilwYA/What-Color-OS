package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.Easing
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.SceneTimeline
import com.coloros16.liquidglass.engine.TransitionEdge
import org.junit.Assert.assertEquals
import org.junit.Test

class SceneTimelineTest {

    private fun u(edge: TransitionEdge?, t: Float, state: SceneState = SceneState.AOD): Float =
        SceneTimeline.uOf(edge, t, state, Easing.LINEAR)

    @Test
    fun staticStatesSitOnTheirAnchors() {
        assertEquals(0f, u(null, 0f, SceneState.AOD), 1e-6f)
        assertEquals(0.5f, u(null, 0f, SceneState.LOCK), 1e-6f)
        assertEquals(1f, u(null, 0f, SceneState.HOME), 1e-6f)
    }

    @Test
    fun aodToLockCoversFirstHalf() {
        assertEquals(0f, u(TransitionEdge.AOD_TO_LOCK, 0f), 1e-6f)
        assertEquals(0.25f, u(TransitionEdge.AOD_TO_LOCK, 0.5f), 1e-6f)
        assertEquals(0.5f, u(TransitionEdge.AOD_TO_LOCK, 1f), 1e-6f)
    }

    @Test
    fun lockToAodIsTheReverseHalf() {
        assertEquals(0.5f, u(TransitionEdge.LOCK_TO_AOD, 0f), 1e-6f)
        assertEquals(0.25f, u(TransitionEdge.LOCK_TO_AOD, 0.5f), 1e-6f)
        assertEquals(0f, u(TransitionEdge.LOCK_TO_AOD, 1f), 1e-6f)
    }

    @Test
    fun lockToHomeCoversSecondHalf() {
        assertEquals(0.5f, u(TransitionEdge.LOCK_TO_HOME, 0f), 1e-6f)
        assertEquals(0.75f, u(TransitionEdge.LOCK_TO_HOME, 0.5f), 1e-6f)
        assertEquals(1f, u(TransitionEdge.LOCK_TO_HOME, 1f), 1e-6f)
    }

    @Test
    fun homeToLockIsTheReverseHalf() {
        assertEquals(1f, u(TransitionEdge.HOME_TO_LOCK, 0f), 1e-6f)
        assertEquals(0.75f, u(TransitionEdge.HOME_TO_LOCK, 0.5f), 1e-6f)
        assertEquals(0.5f, u(TransitionEdge.HOME_TO_LOCK, 1f), 1e-6f)
    }

    @Test
    fun aodToHomeSpansWholeTimeline() {
        assertEquals(0f, u(TransitionEdge.AOD_TO_HOME, 0f), 1e-6f)
        assertEquals(0.5f, u(TransitionEdge.AOD_TO_HOME, 0.5f), 1e-6f)
        assertEquals(1f, u(TransitionEdge.AOD_TO_HOME, 1f), 1e-6f)
    }

    @Test
    fun homeToAodSpansWholeTimelineBackwards() {
        assertEquals(1f, u(TransitionEdge.HOME_TO_AOD, 0f), 1e-6f)
        assertEquals(0.5f, u(TransitionEdge.HOME_TO_AOD, 0.5f), 1e-6f)
        assertEquals(0f, u(TransitionEdge.HOME_TO_AOD, 1f), 1e-6f)
    }

    @Test
    fun joiningEdgesMeetAtLockAnchor() {
        assertEquals(
            u(TransitionEdge.AOD_TO_LOCK, 1f),
            u(TransitionEdge.LOCK_TO_HOME, 0f),
            1e-6f,
        )
        assertEquals(
            u(TransitionEdge.HOME_TO_LOCK, 1f),
            u(TransitionEdge.LOCK_TO_AOD, 0f),
            1e-6f,
        )
    }

    @Test
    fun progressIsClamped() {
        assertEquals(0f, u(TransitionEdge.AOD_TO_LOCK, -1f), 1e-6f)
        assertEquals(0.5f, u(TransitionEdge.AOD_TO_LOCK, 2f), 1e-6f)
    }
}
