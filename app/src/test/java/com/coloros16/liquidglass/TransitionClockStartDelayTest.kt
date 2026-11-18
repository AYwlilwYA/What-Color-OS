package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.ClockSource
import com.coloros16.liquidglass.engine.TransitionClock
import com.coloros16.liquidglass.engine.TransitionEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitionClockStartDelayTest {

    private var fakeNow = 1000L
    private fun clock() = TransitionClock { fakeNow }

    private val edge = TransitionEdge.AOD_TO_LOCK

    @Test
    fun zeroDelayKeepsOldBehaviour() {
        val c = clock()
        c.play(edge, 600L)
        fakeNow = 1300L
        assertTrue(c.advance())
        assertEquals(0.5f, c.state().t, 0.001f)
    }

    @Test
    fun progressHoldsDuringDelay() {
        val c = clock()
        c.play(edge, 600L, startDelayMs = 150L)
        fakeNow = 1000L
        assertTrue(c.advance())
        assertEquals(0f, c.state().t, 0.0001f)
        fakeNow = 1100L
        assertTrue(c.advance())
        assertEquals(0f, c.state().t, 0.0001f)
        fakeNow = 1149L
        assertTrue(c.advance())
        assertEquals(0f, c.state().t, 0.0001f)
    }

    @Test
    fun delayBoundaryStartsMoving() {
        val c = clock()
        c.play(edge, 600L, startDelayMs = 150L)
        fakeNow = 1150L
        assertTrue(c.advance())
        assertEquals(0f, c.state().t, 0.0001f)
        fakeNow = 1450L
        assertTrue(c.advance())
        assertEquals(0.5f, c.state().t, 0.001f)
    }

    @Test
    fun totalWallTimeIsDelayPlusDuration() {
        val c = clock()
        c.play(edge, 600L, startDelayMs = 150L)
        fakeNow = 1749L
        assertTrue(c.advance())
        fakeNow = 1750L
        assertFalse(c.advance())
        assertEquals(1f, c.state().t, 0.0001f)
    }

    @Test
    fun delayDoesNotDiluteChannelFractions() {
        val withDelay = clock()
        withDelay.play(edge, 600L, startDelayMs = 150L)
        fakeNow = 1150L + 300L
        withDelay.advance()
        val a = withDelay.state().t

        fakeNow = 2000L
        val noDelay = clock()
        noDelay.play(edge, 600L)
        fakeNow = 2300L
        noDelay.advance()
        assertEquals(noDelay.state().t, a, 0.0001f)
    }

    @Test
    fun fromIsHeldDuringDelay() {
        val c = clock()
        c.play(edge, 600L, from = 0.25f, startDelayMs = 150L)
        fakeNow = 1100L
        c.advance()
        assertEquals(0.25f, c.state().t, 0.0001f)
        fakeNow = 1450L
        c.advance()
        assertEquals(0.75f, c.state().t, 0.001f)
    }

    @Test
    fun negativeDelayIsClampedToZero() {
        val c = clock()
        c.play(edge, 600L, startDelayMs = -500L)
        fakeNow = 1300L
        c.advance()
        assertEquals(0.5f, c.state().t, 0.001f)
    }

    @Test
    fun replayResetsDelay() {
        val c = clock()
        c.play(edge, 600L, startDelayMs = 150L)
        fakeNow = 1500L
        c.advance()
        val mid = c.state().t
        assertTrue(mid > 0f)
        fakeNow = 2000L
        c.play(edge, 600L)
        fakeNow = 2300L
        c.advance()
        assertEquals(0.5f, c.state().t, 0.001f)
    }

    @Test
    fun setTOverridesDelayPlayback() {
        val c = clock()
        c.play(edge, 600L, startDelayMs = 150L)
        c.setT(edge, 0.8f, ClockSource.SYSTEM_PUMP)
        assertFalse(c.state().playing)
        assertEquals(0.8f, c.state().t, 0.0001f)
        assertFalse(c.advance())
    }

    @Test
    fun pauseDuringDelayKeepsFrom() {
        val c = clock()
        c.play(edge, 600L, startDelayMs = 150L)
        fakeNow = 1100L
        c.advance()
        c.pause()
        fakeNow = 1600L
        assertFalse(c.advance())
        assertEquals(0f, c.state().t, 0.0001f)
    }

    @Test
    fun delayLongerThanDurationStillCompletes() {
        val c = clock()
        c.play(edge, 100L, startDelayMs = 2000L)
        fakeNow = 2500L
        assertTrue(c.advance())
        assertEquals(0f, c.state().t, 0.0001f)
        fakeNow = 3100L
        assertFalse(c.advance())
        assertEquals(1f, c.state().t, 0.0001f)
    }
}