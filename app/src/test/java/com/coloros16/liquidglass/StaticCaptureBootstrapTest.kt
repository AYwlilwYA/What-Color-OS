package com.coloros16.liquidglass

import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.hook.capture.StaticCaptureBootstrap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaticCaptureBootstrapTest {

    private fun allow(
        mode: Int = Prefs.BG_CAPTURE_MODE_STATIC,
        hasSnapshot: Boolean = false,
        sf: Boolean = false,
        failures: Int = 0,
    ) = StaticCaptureBootstrap.shouldBootstrap(
        captureMode = mode,
        staticMode = Prefs.BG_CAPTURE_MODE_STATIC,
        hasSnapshot = hasSnapshot,
        sfRenderMode = sf,
        failures = failures,
    )

    @Test
    fun staticWithNoSnapshotBootstraps() {
        assertTrue("静态档一帧都没有必须放行首帧", allow())
    }

    @Test
    fun staticWithSnapshotStaysFrozen() {
        assertFalse("有旧帧就冻结，不得再抓", allow(hasSnapshot = true))
    }

    @Test
    fun sfRenderModeNeverBootstraps() {
        assertFalse("SF 档背景由系统直供，模块不抓", allow(sf = true))
    }

    @Test
    fun nonStaticModesAreNotHandledHere() {
        assertFalse("变化档走原有入队路径", allow(mode = Prefs.BG_CAPTURE_MODE_ON_CHANGE))
        assertFalse("即时档走原有入队路径", allow(mode = Prefs.BG_CAPTURE_MODE_IMMEDIATE))
    }

    @Test
    fun failuresBelowLimitStillAllowed() {
        assertTrue(allow(failures = StaticCaptureBootstrap.MAX_FAILURES - 1))
    }

    @Test
    fun failuresAtLimitStopTrying() {
        assertFalse("达上限本窗口不再尝试", allow(failures = StaticCaptureBootstrap.MAX_FAILURES))
        assertTrue(StaticCaptureBootstrap.exhausted(StaticCaptureBootstrap.MAX_FAILURES))
    }

    @Test
    fun failuresAboveLimitStayStopped() {
        assertFalse(allow(failures = StaticCaptureBootstrap.MAX_FAILURES + 5))
    }

    @Test
    fun zeroFailuresNotExhausted() {
        assertFalse(StaticCaptureBootstrap.exhausted(0))
    }

    @Test
    fun limitIsBoundedAndSmall() {
        assertTrue("上限必须是有界小值，不能变成周期抓屏", StaticCaptureBootstrap.MAX_FAILURES in 1..5)
    }

    @Test
    fun positiveControlSnapshotBeatsEverythingElse() {
        assertTrue("无快照 + 未达上限 = 放行", allow())
        assertFalse("同一组条件只加上「已有快照」就必须拒绝", allow(hasSnapshot = true))
    }

    @Test
    fun positiveControlSfBeatsEverythingElse() {
        assertTrue(allow())
        assertFalse("同一组条件只加上「SF 档」就必须拒绝", allow(sf = true))
    }

    @Test
    fun staticConstantMatchesPrefs() {
        assertEquals(2, Prefs.BG_CAPTURE_MODE_STATIC)
    }
}
