package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.ClockSource
import com.coloros16.liquidglass.engine.ScreenMetrics
import com.coloros16.liquidglass.engine.TransitionBridge
import com.coloros16.liquidglass.engine.TransitionEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitionBridgeEnsurePlayingTest {

    private var fakeNow = 1000L

    private fun bridge() = TransitionBridge(
        ScreenMetrics(1080f, 2400f, 3f),
        clock = com.coloros16.liquidglass.engine.TransitionClock { fakeNow },
    )

    @Test
    fun firstCallStartsPlayback() {
        fakeNow = 1000L
        val b = bridge()
        assertTrue(b.ensurePlaying(TransitionEdge.LOCK_TO_HOME, 800L))
        assertTrue(b.clock.state().playing)
        assertEquals(TransitionEdge.LOCK_TO_HOME, b.clock.state().edge)
    }

    @Test
    fun secondCallWhilePlayingDoesNotRestart() {
        fakeNow = 1000L
        val b = bridge()
        assertTrue(b.ensurePlaying(TransitionEdge.LOCK_TO_HOME, 800L))
        fakeNow = 1400L; b.advance()
        val mid = b.clock.state().t
        assertFalse(b.ensurePlaying(TransitionEdge.LOCK_TO_HOME, 800L))
        assertEquals(mid, b.clock.state().t, 0.0001f)
    }

    @Test
    fun systemPumpOwnershipIsNotStolen() {
        fakeNow = 1000L
        val b = bridge()
        b.onSystemProgress(TransitionEdge.AOD_TO_LOCK, 0.2f)
        b.onSystemProgress(TransitionEdge.AOD_TO_LOCK, 0.4f)
        assertFalse(b.ensurePlaying(TransitionEdge.AOD_TO_LOCK, 600L))
        assertEquals(ClockSource.SYSTEM_PUMP, b.clock.state().source)
        assertEquals(0.4f, b.clock.state().t, 0.0001f)
    }

    @Test
    fun systemPumpOnOtherEdgeAllowsSelfTimed() {
        fakeNow = 1000L
        val b = bridge()
        b.onSystemProgress(TransitionEdge.AOD_TO_LOCK, 0.4f)
        assertTrue(b.ensurePlaying(TransitionEdge.LOCK_TO_HOME, 800L))
        assertEquals(TransitionEdge.LOCK_TO_HOME, b.clock.state().edge)
    }

    @Test
    fun finishedEdgeDoesNotReplay() {
        fakeNow = 1000L
        val b = bridge()
        b.ensurePlaying(TransitionEdge.LOCK_TO_HOME, 800L)
        fakeNow = 2000L; b.advance()
        assertFalse(b.clock.state().playing)
        assertEquals(1f, b.clock.state().t, 0.0001f)
        assertFalse(b.ensurePlaying(TransitionEdge.LOCK_TO_HOME, 800L))
    }

    @Test
    fun oppositeEdgeStartsFresh() {
        fakeNow = 1000L
        val b = bridge()
        b.ensurePlaying(TransitionEdge.LOCK_TO_HOME, 800L)
        fakeNow = 2000L; b.advance()
        assertTrue(b.ensurePlaying(TransitionEdge.HOME_TO_LOCK, 600L))
        assertEquals(TransitionEdge.HOME_TO_LOCK, b.clock.state().edge)
        assertEquals(0f, b.clock.state().t, 0.0001f)
    }

    @Test
    fun advanceReachesEndAndStops() {
        fakeNow = 1000L
        val b = bridge()
        b.ensurePlaying(TransitionEdge.LOCK_TO_HOME, 800L)
        fakeNow = 1400L
        assertTrue(b.advance())
        assertEquals(0.5f, b.clock.state().t, 0.001f)
        fakeNow = 1800L
        assertFalse(b.advance())
        assertEquals(1f, b.clock.state().t, 0.0001f)
    }

    @Test
    fun evaluateProducesActiveParamsWhilePlaying() {
        fakeNow = 1000L
        val b = bridge()
        b.setCapabilities(
            com.coloros16.liquidglass.engine.StyleCapabilities(mask = true, crossfade = true),
            com.coloros16.liquidglass.engine.StyleCapabilities(mask = true, crossfade = true),
        )
        b.ensurePlaying(TransitionEdge.LOCK_TO_HOME, 800L)
        fakeNow = 1400L; b.advance()
        val p = b.evaluateNow()
        assertTrue(p.active)
        assertEquals(TransitionEdge.LOCK_TO_HOME, p.edge)
        assertEquals(0.5f, p.t, 0.001f)
    }
}
