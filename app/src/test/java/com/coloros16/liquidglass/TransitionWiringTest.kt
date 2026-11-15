package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.ClockSource
import com.coloros16.liquidglass.engine.ContentLayerConfig
import com.coloros16.liquidglass.engine.Easing
import com.coloros16.liquidglass.engine.MaskLayerConfig
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.SceneStateMapper
import com.coloros16.liquidglass.engine.ScreenMetrics
import com.coloros16.liquidglass.engine.SegmentSample
import com.coloros16.liquidglass.engine.SegmentedProgress
import com.coloros16.liquidglass.engine.StyleCapabilities
import com.coloros16.liquidglass.engine.TransitionBridge
import com.coloros16.liquidglass.engine.TransitionClock
import com.coloros16.liquidglass.engine.TransitionEdge
import com.coloros16.liquidglass.engine.TransitionEvaluator
import com.coloros16.liquidglass.engine.TransitionProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitionWiringTest {

    // ---- SceneStateMapper ----

    @Test
    fun mapsHostStateNamesToScenes() {
        assertEquals(SceneState.AOD, SceneStateMapper.fromTransitionStateName("DOZING"))
        assertEquals(SceneState.AOD, SceneStateMapper.fromTransitionStateName("Aod"))
        assertEquals(SceneState.LOCK, SceneStateMapper.fromTransitionStateName("LOCKSCREEN"))
        assertEquals(SceneState.HOME, SceneStateMapper.fromTransitionStateName("UNLOCKED"))
        assertEquals(SceneState.HOME, SceneStateMapper.fromTransitionStateName("GONE_TO_HOME"))
        assertNull(SceneStateMapper.fromTransitionStateName("OFF"))
        assertNull(SceneStateMapper.fromTransitionStateName(null))
        assertNull(SceneStateMapper.fromTransitionStateName(""))
    }

    @Test
    fun resolvesEdgeFromHostNames() {
        assertEquals(
            TransitionEdge.AOD_TO_LOCK,
            SceneStateMapper.resolveEdge("DOZING", "LOCKSCREEN"),
        )
        assertEquals(
            TransitionEdge.LOCK_TO_HOME,
            SceneStateMapper.resolveEdge("LOCKSCREEN", "UNLOCKED"),
        )
        assertEquals(
            TransitionEdge.HOME_TO_AOD,
            SceneStateMapper.resolveEdge("UNLOCKED", "DOZING"),
        )
        assertNull(SceneStateMapper.resolveEdge("DOZING", "DOZING"))
        assertNull(SceneStateMapper.resolveEdge("OFF", "LOCKSCREEN"))
    }

    // ---- SegmentedProgress ----

    @Test
    fun splitsDirectEdgeIntoTwoSegments() {
        val mapper = SegmentedProgress(0.5f)
        val out = SegmentSample()

        mapper.sample(TransitionEdge.AOD_TO_HOME, 0.25f, out)
        assertEquals(TransitionEdge.AOD_TO_LOCK, out.edge)
        assertEquals(0.5f, out.t, 0.001f)

        mapper.sample(TransitionEdge.AOD_TO_HOME, 0.75f, out)
        assertEquals(TransitionEdge.LOCK_TO_HOME, out.edge)
        assertEquals(0.5f, out.t, 0.001f)

        mapper.sample(TransitionEdge.HOME_TO_AOD, 0.25f, out)
        assertEquals(TransitionEdge.HOME_TO_LOCK, out.edge)
        assertEquals(0.5f, out.t, 0.001f)

        mapper.sample(TransitionEdge.HOME_TO_AOD, 0.75f, out)
        assertEquals(TransitionEdge.LOCK_TO_AOD, out.edge)
        assertEquals(0.5f, out.t, 0.001f)
    }

    @Test
    fun passesThroughAdjacentEdgesAndClamps() {
        val mapper = SegmentedProgress()
        val out = SegmentSample()

        mapper.sample(TransitionEdge.LOCK_TO_AOD, 0.3f, out)
        assertEquals(TransitionEdge.LOCK_TO_AOD, out.edge)
        assertEquals(0.3f, out.t, 0.001f)

        mapper.sample(TransitionEdge.AOD_TO_HOME, 1.5f, out)
        assertEquals(TransitionEdge.LOCK_TO_HOME, out.edge)
        assertEquals(1f, out.t, 0.001f)

        mapper.sample(TransitionEdge.AOD_TO_HOME, -1f, out)
        assertEquals(TransitionEdge.AOD_TO_LOCK, out.edge)
        assertEquals(0f, out.t, 0.001f)
    }

    // ---- TransitionBridge ----

    private val screen = ScreenMetrics(1080f, 2400f)
    private val caps = StyleCapabilities(mask = true, crossfade = true)

    @Test
    fun systemProgressFlowsIntoEvaluatedParams() {
        val evaluator = TransitionEvaluator(
            defaultProfile = TransitionProfile(
                mask = MaskLayerConfig(enabled = true),
                content = ContentLayerConfig(easing = Easing.LINEAR),
            ),
        )
        val bridge = TransitionBridge(screen, evaluator = evaluator)
        bridge.setCapabilities(caps, caps)

        bridge.onSystemProgress(TransitionEdge.AOD_TO_LOCK, 0.5f)
        val params = bridge.evaluateNow()

        assertTrue(params.active)
        assertEquals(TransitionEdge.AOD_TO_LOCK, params.edge)
        assertEquals(0.5f, params.t, 0.001f)
        assertEquals(ClockSource.SYSTEM_PUMP, bridge.clock.state().source)
        assertTrue(params.maskEnabled)
        assertTrue(params.maskRadius > 0f)
        assertTrue(params.dstAlpha > 0f)
    }

    @Test
    fun selfTimedEdgeAdvancesOnlyWhilePlaying() {
        var now = 1_000L
        val clock = TransitionClock { now }
        val bridge = TransitionBridge(screen, clock = clock)
        bridge.setCapabilities(caps, caps)

        bridge.startSelfTimed(TransitionEdge.HOME_TO_AOD, 200L, now)
        assertEquals(ClockSource.INTERNAL_TIMELINE, bridge.clock.state().source)

        now = 1_100L
        assertTrue(bridge.advance(now))
        assertEquals(0.5f, bridge.clock.state().t, 0.01f)

        now = 1_200L
        assertFalse(bridge.advance(now))
        assertEquals(1f, bridge.clock.state().t, 0.001f)
        assertFalse(bridge.clock.state().playing)
    }

    @Test
    fun bridgeWithoutProgressReturnsInactiveParams() {
        val bridge = TransitionBridge(screen)
        val params = bridge.evaluateNow()
        assertFalse(params.active)
        assertNull(params.edge)
    }

    @Test
    fun resetClearsClockAndParams() {
        val bridge = TransitionBridge(screen)
        bridge.setCapabilities(caps, caps)
        bridge.onSystemProgress(TransitionEdge.LOCK_TO_HOME, 0.7f)
        bridge.evaluateNow()
        bridge.reset()
        assertNull(bridge.clock.state().edge)
        assertFalse(bridge.evaluateNow().active)
    }
}
