package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.HandoffReplayGuard
import com.coloros16.liquidglass.hook.normalizeProgress
import com.coloros16.liquidglass.hook.progressText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AodHandoffHookTest {

    // ---------- 防重放代次 ----------

    @Test
    fun screenOnArmsWindowAndReturnsNewGeneration() {
        val g = HandoffReplayGuard(200L)
        assertEquals(1L, g.onScreenOn(1000L))
        assertEquals(1L, g.generation())
        assertEquals(1L, g.wakeSerial())
        assertTrue(g.startWithinWindow(1100L))
        assertTrue(g.startWithinWindow(1200L))
    }

    @Test
    fun startAfterWaitWindowIsNotInWindow() {
        val g = HandoffReplayGuard(200L)
        g.onScreenOn(1000L)
        assertFalse(g.startWithinWindow(1201L))
    }

    @Test
    fun screenOffAdvancesGenerationAndClosesWindow() {
        val g = HandoffReplayGuard(200L)
        val token = g.onScreenOn(1000L)
        assertTrue(g.stillCurrent(token))
        assertEquals(2L, g.onScreenOff())
        assertFalse(g.stillCurrent(token))
        assertFalse(g.startWithinWindow(1100L))
        assertEquals(1L, g.wakeSerial())
    }

    @Test
    fun staleTokenRejectedAfterNextWake() {
        val g = HandoffReplayGuard(200L)
        val first = g.onScreenOn(1000L)
        val second = g.onScreenOn(5000L)
        assertEquals(first + 1, second)
        assertFalse(g.stillCurrent(first))
        assertTrue(g.stillCurrent(second))
        assertEquals(2L, g.wakeSerial())
        assertEquals(2L, g.generation())
    }

    @Test
    fun wakeAdvancesBothCounters() {
        val g = HandoffReplayGuard(200L)
        g.onScreenOn(1000L)
        g.onScreenOff()
        g.onScreenOn(3000L)
        assertEquals(3L, g.generation())
        assertEquals(2L, g.wakeSerial())
    }

    @Test
    fun defaultWaitWindowIsTwoHundredMs() {
        assertEquals(200L, HandoffReplayGuard.DEFAULT_MAX_WAIT_MS)
        val g = HandoffReplayGuard()
        g.onScreenOn(1000L)
        assertTrue(g.startWithinWindow(1200L))
        assertFalse(g.startWithinWindow(1201L))
    }

    // ---------- 进度解析 ----------

    @Test
    fun progressClampedToUnitRange() {
        assertEquals(0f, normalizeProgress(-0.5f))
        assertEquals(1f, normalizeProgress(1.5f))
        assertEquals(0.42f, normalizeProgress(0.42f), 0.0001f)
    }

    @Test
    fun nanProgressPreservedAsMissing() {
        assertTrue(normalizeProgress(Float.NaN).isNaN())
        assertEquals("na", progressText(Float.NaN))
    }

    @Test
    fun progressTextThreeDecimals() {
        assertEquals("0.420", progressText(0.42f))
        assertEquals("1.000", progressText(1f))
        assertEquals("0.000", progressText(0f))
    }
}
