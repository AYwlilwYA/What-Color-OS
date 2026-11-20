package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.ClockSource
import com.coloros16.liquidglass.engine.TransitionClock
import com.coloros16.liquidglass.engine.TransitionEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系统泵只有端点值时不得顶掉内部时间轴。
 *
 * 端点直跳（0→1）不构成「逐帧喂」的证据：内部时间轴播放期间必须忽略这类写入，
 * 否则长时长的自绘转场会被瞬间推到落位帧。
 */
class TransitionClockPumpTakeoverTest {

    private val edge = TransitionEdge.LOCK_TO_HOME

    private fun clock(): Pair<TransitionClock, () -> Unit> {
        var now = 0L
        val c = TransitionClock { now }
        return c to { now += 16L }
    }

    @Test
    fun endpointJumpDoesNotStealSelfTimeline() {
        val (c, tick) = clock()
        c.play(edge, 3840L, 0f, ClockSource.INTERNAL_TIMELINE)
        c.setT(edge, 0f, ClockSource.SYSTEM_PUMP)
        c.setT(edge, 1f, ClockSource.SYSTEM_PUMP)

        val st = c.state()
        assertEquals(ClockSource.INTERNAL_TIMELINE, st.source)
        assertTrue("内部时间轴必须仍在播放", st.playing)
        assertEquals(0f, st.t, 0.0001f)

        tick()
        c.advance()
        assertTrue("推进后应仍是时间轴进度", c.state().t < 0.01f)
    }

    @Test
    fun interiorPumpValueTakesOver() {
        val (c, _) = clock()
        c.play(edge, 3840L, 0f, ClockSource.INTERNAL_TIMELINE)
        c.setT(edge, 0f, ClockSource.SYSTEM_PUMP)
        c.setT(edge, 0.42f, ClockSource.SYSTEM_PUMP)

        val st = c.state()
        assertEquals(ClockSource.SYSTEM_PUMP, st.source)
        assertFalse("交给系统泵后不再由内部时间轴推进", st.playing)
        assertEquals(0.42f, st.t, 0.0001f)
    }

    @Test
    fun movementDetectedWhileSelfTimingStillHandsOver() {
        val (c, _) = clock()
        c.play(edge, 3840L, 0f, ClockSource.INTERNAL_TIMELINE)
        c.setT(edge, 0f, ClockSource.SYSTEM_PUMP)
        c.setT(edge, 0.15f, ClockSource.SYSTEM_PUMP)
        assertEquals(ClockSource.SYSTEM_PUMP, c.state().source)
        assertEquals(0.15f, c.state().t, 0.0001f)
    }

    @Test
    fun newEdgePumpWritePreemptsRunningTimeline() {
        val (c, _) = clock()
        c.play(edge, 3840L, 0f, ClockSource.INTERNAL_TIMELINE)
        c.setT(TransitionEdge.HOME_TO_AOD, 0.2f, ClockSource.SYSTEM_PUMP)
        val st = c.state()
        assertEquals(TransitionEdge.HOME_TO_AOD, st.edge)
        assertEquals(ClockSource.SYSTEM_PUMP, st.source)
        assertEquals(0.2f, st.t, 0.0001f)
    }

    @Test
    fun staleMovementFromPreviousRunDoesNotStealNewTimeline() {
        val (c, _) = clock()
        c.setT(edge, 0f, ClockSource.SYSTEM_PUMP)
        c.setT(edge, 0.5f, ClockSource.SYSTEM_PUMP)
        assertTrue(c.pumpSawMovement())

        c.play(edge, 3840L, 0f, ClockSource.INTERNAL_TIMELINE)
        assertFalse("新一轮时间轴起播必须重置运动见证", c.pumpSawMovement())
        c.setT(edge, 1f, ClockSource.SYSTEM_PUMP)
        assertEquals(ClockSource.INTERNAL_TIMELINE, c.state().source)
        assertTrue(c.state().playing)
    }

    @Test
    fun resetDropsPumpSession() {
        val (c, _) = clock()
        c.setT(edge, 0f, ClockSource.SYSTEM_PUMP)
        c.setT(edge, 0.5f, ClockSource.SYSTEM_PUMP)
        assertTrue(c.pumpSawMovement())

        c.reset()
        assertFalse(c.pumpSawMovement())

        c.play(edge, 3840L, 0f, ClockSource.INTERNAL_TIMELINE)
        c.setT(edge, 0f, ClockSource.SYSTEM_PUMP)
        c.setT(edge, 1f, ClockSource.SYSTEM_PUMP)
        assertEquals(ClockSource.INTERNAL_TIMELINE, c.state().source)
        assertTrue(c.state().playing)
    }
}
