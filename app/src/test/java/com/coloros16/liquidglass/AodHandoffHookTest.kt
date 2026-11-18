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

    // ---------- 状态变更汇聚点取值 ----------

    private class FakeStep(
        private val from: Any,
        private val to: Any,
        private val value: Float,
        private val state: Any,
    ) {
        fun getFrom(): Any = from
        fun getTo(): Any = to
        fun getValue(): Float = value
        fun getTransitionState(): Any = state
    }

    @Test
    fun stepGettersReadAllFourFields() {
        val step = FakeStep("DOZING", "GONE", 0.375f, "RUNNING")
        val cls = FakeStep::class.java
        assertEquals("DOZING", cls.getMethod("getFrom").invoke(step))
        assertEquals("GONE", cls.getMethod("getTo").invoke(step))
        assertEquals(0.375f, cls.getMethod("getValue").invoke(step) as Float, 0.0001f)
        assertEquals("RUNNING", cls.getMethod("getTransitionState").invoke(step))
    }

    @Test
    fun runningFrameValueIsPrimitiveFloatNotBoxed() {
        val m = FakeStep::class.java.getMethod("getValue")
        assertEquals(java.lang.Float.TYPE, m.returnType)
        assertTrue(m.invoke(FakeStep("A", "B", 0f, "RUNNING")) is Float)
    }

    @Test
    fun missingValueGetterDegradesToNaNAndBlocksFeed() {
        val missing: java.lang.reflect.Method? = null
        val progress = normalizeProgress(missing?.invoke(FakeStep("A", "B", 0.5f, "RUNNING")) as? Float ?: Float.NaN)
        assertTrue(progress.isNaN())
    }

    @Test
    fun runningFrameKeepsProgressInsideUnitRange() {
        val cls = FakeStep::class.java.getMethod("getValue")
        val step = FakeStep("DOZING", "LOCKSCREEN", 1.4f, "RUNNING")
        assertEquals(1f, normalizeProgress(cls.invoke(step) as Float))
    }
}
