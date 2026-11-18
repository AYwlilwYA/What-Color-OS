package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.effect.AodDisplayGate
import com.coloros16.liquidglass.engine.effect.AodDisplayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AodDisplayGateTest {

    @Test
    fun alwaysShowsAndFollowsDynamicSwitch() {
        val on = AodDisplayGate.decide(AodDisplayMode.ALWAYS, 0, 0, 3 * 60, 0L, 30_000L, allowDynamic = true)
        assertTrue(on.visible)
        assertTrue(on.animate)
        assertEquals(1f, on.dim, 0f)
        assertEquals("always", on.reason)

        val off = AodDisplayGate.decide(AodDisplayMode.ALWAYS, 0, 0, 3 * 60, 0L, 30_000L, allowDynamic = false)
        assertTrue(off.visible)
        assertFalse(off.animate)
    }

    @Test
    fun scheduleWindowInsideAndOutside() {
        // 22:00 - 07:30
        assertTrue(AodDisplayGate.inWindow(22 * 60, 7 * 60 + 30, 23 * 60))
        assertTrue(AodDisplayGate.inWindow(22 * 60, 7 * 60 + 30, 3 * 60))
        assertTrue(AodDisplayGate.inWindow(22 * 60, 7 * 60 + 30, 22 * 60))
        assertFalse(AodDisplayGate.inWindow(22 * 60, 7 * 60 + 30, 7 * 60 + 30))
        assertFalse(AodDisplayGate.inWindow(22 * 60, 7 * 60 + 30, 12 * 60))
    }

    @Test
    fun sameStartEndMeansAllDay() {
        assertTrue(AodDisplayGate.inWindow(8 * 60, 8 * 60, 3 * 60))
        assertTrue(AodDisplayGate.inWindow(8 * 60, 8 * 60, 23 * 60))
    }

    @Test
    fun scheduleModeHidesOutsideWindow() {
        val outside = AodDisplayGate.decide(
            AodDisplayMode.SCHEDULED, 22 * 60, 7 * 60 + 30, 12 * 60, 0L, 30_000L, allowDynamic = true,
        )
        assertFalse(outside.visible)
        assertFalse(outside.animate)
        assertEquals(0f, outside.dim, 0f)
        assertEquals("schedule-out", outside.reason)

        val inside = AodDisplayGate.decide(
            AodDisplayMode.SCHEDULED, 22 * 60, 7 * 60 + 30, 23 * 60, 0L, 30_000L, allowDynamic = true,
        )
        assertTrue(inside.visible)
        assertTrue(inside.animate)
    }

    @Test
    fun powerSaveHidesAfterTimeout() {
        val before = AodDisplayGate.decide(AodDisplayMode.POWER_SAVE, 0, 0, 0, 29_999L, 30_000L, true)
        assertTrue(before.visible)
        assertEquals("power-save-on", before.reason)

        val after = AodDisplayGate.decide(AodDisplayMode.POWER_SAVE, 0, 0, 0, 30_000L, 30_000L, true)
        assertFalse(after.visible)
        assertFalse(after.animate)
        assertEquals("power-save-timeout", after.reason)
    }

    @Test
    fun powerSaveFallsBackToDefaultTimeout() {
        val decision = AodDisplayGate.decide(AodDisplayMode.POWER_SAVE, 0, 0, 0, 10_000L, 0L, true)
        assertTrue(decision.visible)
        val expired = AodDisplayGate.decide(
            AodDisplayMode.POWER_SAVE, 0, 0, 0, AodDisplayGate.DEFAULT_POWER_SAVE_TIMEOUT_MS, 0L, true,
        )
        assertFalse(expired.visible)
    }

    @Test
    fun negativeIdleTreatedAsActive() {
        val decision = AodDisplayGate.decide(AodDisplayMode.POWER_SAVE, 0, 0, 0, -500L, 30_000L, true)
        assertTrue(decision.visible)
    }

    @Test
    fun alwaysModeNeverSchedulesAWake() {
        assertEquals(-1L, AodDisplayGate.nextFlipDelayMs(AodDisplayMode.ALWAYS, 22 * 60, 7 * 60, 12 * 60, 0L, 30_000L))
        assertEquals(-1L, AodDisplayGate.nextFlipDelayMs(AodDisplayMode.ALWAYS, 0, 0, 0, 999_999L, 30_000L))
    }

    @Test
    fun sameStartEndScheduleNeverSchedulesAWake() {
        assertEquals(-1L, AodDisplayGate.nextFlipDelayMs(AodDisplayMode.SCHEDULED, 8 * 60, 8 * 60, 3 * 60, 0L, 30_000L))
    }

    @Test
    fun scheduleInsideWindowWakesAtWindowEnd() {
        val delay = AodDisplayGate.nextFlipDelayMs(
            AodDisplayMode.SCHEDULED, 22 * 60, 7 * 60 + 30, 23 * 60, 0L, 30_000L,
        )
        assertEquals("23:00 → 次日 07:30 = 8.5 小时", 8 * 3_600_000L + 1_800_000L, delay)
    }

    @Test
    fun scheduleOutsideWindowWakesAtWindowStart() {
        val delay = AodDisplayGate.nextFlipDelayMs(
            AodDisplayMode.SCHEDULED, 22 * 60, 7 * 60 + 30, 12 * 60, 0L, 30_000L,
        )
        assertEquals("12:00 → 22:00 = 10 小时", 10 * 3_600_000L, delay)
    }

    @Test
    fun scheduleAtClosingBoundaryWakesAtNextOpening() {
        val delay = AodDisplayGate.nextFlipDelayMs(
            AodDisplayMode.SCHEDULED, 22 * 60, 7 * 60, 7 * 60, 0L, 30_000L,
        )
        assertEquals("07:00 是右开端点已出窗口，下次翻转是 15 小时后的 22:00", 15 * 3_600_000L, delay)
        assertTrue("延迟必须恒正，否则会退化成忙循环", delay > 0L)
    }

    @Test
    fun scheduleAtOpeningBoundaryWakesAtClosing() {
        val delay = AodDisplayGate.nextFlipDelayMs(
            AodDisplayMode.SCHEDULED, 22 * 60, 7 * 60, 22 * 60, 0L, 30_000L,
        )
        assertEquals("22:00 刚进窗口，到次日 07:00 关窗共 9 小时", 9 * 3_600_000L, delay)
    }

    @Test
    fun scheduleDelayAlwaysPositiveAcrossWholeDay() {
        val start = 22 * 60
        val end = 7 * 60 + 30
        for (m in 0 until 1440) {
            val d = AodDisplayGate.nextFlipDelayMs(AodDisplayMode.SCHEDULED, start, end, m, 0L, 30_000L)
            assertTrue("minute=$m delay=$d 必须恒正", d > 0L)
            assertTrue("minute=$m delay=$d 不得超过一天", d <= 24 * 3_600_000L)
        }
    }

    @Test
    fun scheduleDelayAlwaysFlipsVisibilityAcrossWholeDay() {
        val start = 22 * 60
        val end = 7 * 60 + 30
        for (m in 0 until 1440) {
            val d = AodDisplayGate.nextFlipDelayMs(AodDisplayMode.SCHEDULED, start, end, m, 0L, 30_000L)
            val now = AodDisplayGate.decide(AodDisplayMode.SCHEDULED, start, end, m, 0L, 30_000L, true)
            val next = ((m + d / 60_000L) % 1440L).toInt()
            val after = AodDisplayGate.decide(AodDisplayMode.SCHEDULED, start, end, next, 0L, 30_000L, true)
            assertTrue("minute=$m 醒来后可见性必须翻转", now.visible != after.visible)
        }
    }

    @Test
    fun scheduleNonCrossingWindowWakesAtEnd() {
        val delay = AodDisplayGate.nextFlipDelayMs(
            AodDisplayMode.SCHEDULED, 9 * 60, 17 * 60, 12 * 60, 0L, 30_000L,
        )
        assertEquals(5 * 3_600_000L, delay)
    }

    @Test
    fun powerSaveWakeIsRemainingTimeout() {
        assertEquals(20_000L, AodDisplayGate.nextFlipDelayMs(AodDisplayMode.POWER_SAVE, 0, 0, 0, 10_000L, 30_000L))
        assertEquals(1L, AodDisplayGate.nextFlipDelayMs(AodDisplayMode.POWER_SAVE, 0, 0, 0, 29_999L, 30_000L))
    }

    @Test
    fun powerSaveAfterTimeoutHasNoNaturalWake() {
        assertEquals(-1L, AodDisplayGate.nextFlipDelayMs(AodDisplayMode.POWER_SAVE, 0, 0, 0, 30_000L, 30_000L))
        assertEquals(-1L, AodDisplayGate.nextFlipDelayMs(AodDisplayMode.POWER_SAVE, 0, 0, 0, 90_000L, 30_000L))
    }

    @Test
    fun powerSaveWakeUsesDefaultWhenTimeoutNonPositive() {
        assertEquals(
            AodDisplayGate.DEFAULT_POWER_SAVE_TIMEOUT_MS,
            AodDisplayGate.nextFlipDelayMs(AodDisplayMode.POWER_SAVE, 0, 0, 0, 0L, 0L),
        )
        assertEquals(
            AodDisplayGate.DEFAULT_POWER_SAVE_TIMEOUT_MS,
            AodDisplayGate.nextFlipDelayMs(AodDisplayMode.POWER_SAVE, 0, 0, 0, 0L, -5L),
        )
    }

    @Test
    fun negativeIdleWakeIsFullTimeout() {
        assertEquals(30_000L, AodDisplayGate.nextFlipDelayMs(AodDisplayMode.POWER_SAVE, 0, 0, 0, -500L, 30_000L))
    }

    @Test
    fun wakeDelayLandsExactlyOnVisibilityFlip() {
        val start = 22 * 60
        val end = 7 * 60 + 30
        val nowMin = 23 * 60
        val before = AodDisplayGate.decide(AodDisplayMode.SCHEDULED, start, end, nowMin, 0L, 30_000L, true)
        val delay = AodDisplayGate.nextFlipDelayMs(AodDisplayMode.SCHEDULED, start, end, nowMin, 0L, 30_000L)
        val afterMin = ((nowMin + delay / 60_000L) % 1440L).toInt()
        val after = AodDisplayGate.decide(AodDisplayMode.SCHEDULED, start, end, afterMin, 0L, 30_000L, true)
        assertTrue(before.visible)
        assertFalse("醒来那一刻必须已翻转，否则会一直判否", after.visible)
    }

    @Test
    fun powerSaveWakeLandsExactlyOnTimeout() {
        val idle = 10_000L
        val timeout = 30_000L
        val delay = AodDisplayGate.nextFlipDelayMs(AodDisplayMode.POWER_SAVE, 0, 0, 0, idle, timeout)
        assertTrue(AodDisplayGate.decide(AodDisplayMode.POWER_SAVE, 0, 0, 0, idle, timeout, true).visible)
        assertFalse(AodDisplayGate.decide(AodDisplayMode.POWER_SAVE, 0, 0, 0, idle + delay, timeout, true).visible)
    }

    @Test
    fun effectiveTimeoutMatchesDecide() {
        assertEquals(30_000L, AodDisplayGate.effectiveTimeout(0L))
        assertEquals(30_000L, AodDisplayGate.effectiveTimeout(-1L))
        assertEquals(5_000L, AodDisplayGate.effectiveTimeout(5_000L))
    }

    @Test
    fun positiveControlGateWouldHaveChangedRealBehaviour() {
        val noon = 12 * 60
        val always = AodDisplayGate.decide(AodDisplayMode.ALWAYS, 22 * 60, 7 * 60, noon, 0L, 30_000L, true)
        val scheduled = AodDisplayGate.decide(AodDisplayMode.SCHEDULED, 22 * 60, 7 * 60, noon, 0L, 30_000L, true)
        assertTrue("不接线时等价于恒全天：中午照样显示", always.visible)
        assertFalse("接线后定时档中午必须撤效果", scheduled.visible)
        val ecoFresh = AodDisplayGate.decide(AodDisplayMode.POWER_SAVE, 0, 0, noon, 0L, 30_000L, true)
        val ecoIdle = AodDisplayGate.decide(AodDisplayMode.POWER_SAVE, 0, 0, noon, 31_000L, 30_000L, true)
        assertTrue(ecoFresh.visible)
        assertFalse("节能档静置 31s 必须消失", ecoIdle.visible)
    }
}
