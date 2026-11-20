package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SeedlingTimerText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 计时文案契约：格式/钳位/到点/未配置，含阳性对照。 */
class SeedlingTimerContractTest {

    @Test
    fun formatMmSs() {
        assertEquals("02:30", SeedlingTimerText.format(150_000L))
        assertEquals("00:00", SeedlingTimerText.format(0L))
        assertEquals("00:01", SeedlingTimerText.format(1L))
        assertEquals("01:00", SeedlingTimerText.format(60_000L))
    }

    @Test
    fun formatHourRoll() {
        assertEquals("1:00:00", SeedlingTimerText.format(3_600_000L))
        assertEquals("2:03:04", SeedlingTimerText.format(7_384_000L))
    }

    @Test
    fun ceilToNextSecond() {
        assertEquals("剩余 1ms 仍显示 00:01（向上取整防提前归零）", "00:01", SeedlingTimerText.format(1L))
        assertEquals("00:02", SeedlingTimerText.format(1001L))
    }

    @Test
    fun countdownClampsZero() {
        assertEquals("计时 00:00", SeedlingTimerText.countdownLabel(1000L, 5000L))
    }

    @Test
    fun countdownTicks() {
        val end = 100_000L
        assertEquals("计时 01:40", SeedlingTimerText.countdownLabel(end, 0L))
        assertEquals("计时 00:30", SeedlingTimerText.countdownLabel(end, 70_000L))
        assertEquals("计时 00:00", SeedlingTimerText.countdownLabel(end, 100_000L))
    }

    @Test
    fun countdownDoneBoundary() {
        assertFalse(SeedlingTimerText.countdownDone(1000L, 999L))
        assertTrue(SeedlingTimerText.countdownDone(1000L, 1000L))
        assertTrue(SeedlingTimerText.countdownDone(1000L, 2000L))
        assertFalse("未配置不算到点", SeedlingTimerText.countdownDone(0L, 99999L))
    }

    @Test
    fun unconfiguredReturnsNull() {
        assertNull(SeedlingTimerText.countdownLabel(0L, 100L))
        assertNull(SeedlingTimerText.countUpLabel(0L, 100L, "录音中"))
    }

    @Test
    fun countUpFromBase() {
        assertEquals("录音中 00:12", SeedlingTimerText.countUpLabel(1000L, 13_000L, "录音中"))
        assertEquals("录音中 01:05", SeedlingTimerText.countUpLabel(0L + 1L, 65_001L, "录音中"))
    }

    @Test
    fun negativeRemainingClamped_positiveControl() {
        assertEquals("阳性对照：负剩余钳 0 不出负号", "00:00", SeedlingTimerText.format(-5000L))
    }
}
