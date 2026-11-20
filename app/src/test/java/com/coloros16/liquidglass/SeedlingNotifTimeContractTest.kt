package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SeedlingNotifTimePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 通知计时/进度换算契约（墙钟→elapsed 基准），含阳性对照。 */
class SeedlingNotifTimeContractTest {

    // ---------- 墙钟→elapsed ----------

    @Test
    fun wallToElapsedFuture() {
        // when 在当前墙钟之后 5s（倒计时目标）→ elapsed 基准 = nowElapsed + 5000
        val r = SeedlingNotifTimePolicy.wallToElapsed(whenWallMs = 10_000L, nowWallMs = 5_000L, nowElapsedMs = 100_000L)
        assertEquals(105_000L, r)
    }

    @Test
    fun wallToElapsedPast() {
        // when 在当前墙钟之前 3s（正计时起点）→ elapsed 基准 = nowElapsed - 3000
        val r = SeedlingNotifTimePolicy.wallToElapsed(whenWallMs = 2_000L, nowWallMs = 5_000L, nowElapsedMs = 100_000L)
        assertEquals(97_000L, r)
    }

    @Test
    fun wallToElapsedNow() {
        assertEquals(100_000L, SeedlingNotifTimePolicy.wallToElapsed(5_000L, 5_000L, 100_000L))
    }

    // ---------- 倒计时/正计时 ----------

    @Test
    fun countdownEndConfigured() {
        assertEquals(105_000L, SeedlingNotifTimePolicy.countdownEndElapsed(10_000L, 5_000L, 100_000L))
    }

    @Test
    fun countdownEndUnconfigured_positiveControl() {
        assertEquals("阳性对照：when<=0 未配置返回 0", 0L, SeedlingNotifTimePolicy.countdownEndElapsed(0L, 5_000L, 100_000L))
        assertEquals(0L, SeedlingNotifTimePolicy.countdownEndElapsed(-1L, 5_000L, 100_000L))
    }

    @Test
    fun countUpBaseConfigured() {
        assertEquals(97_000L, SeedlingNotifTimePolicy.countUpBaseElapsed(2_000L, 5_000L, 100_000L))
    }

    @Test
    fun countUpBaseUnconfigured_positiveControl() {
        assertEquals(0L, SeedlingNotifTimePolicy.countUpBaseElapsed(0L, 5_000L, 100_000L))
    }

    // ---------- 进度分数 ----------

    @Test
    fun progressFractionNormal() {
        assertEquals(0.5f, SeedlingNotifTimePolicy.progressFraction(50, 100, false)!!, 1e-4f)
        assertEquals(0f, SeedlingNotifTimePolicy.progressFraction(0, 100, false)!!, 1e-4f)
        assertEquals(1f, SeedlingNotifTimePolicy.progressFraction(100, 100, false)!!, 1e-4f)
    }

    @Test
    fun progressClamped() {
        assertEquals("越界钳 1", 1f, SeedlingNotifTimePolicy.progressFraction(150, 100, false)!!, 1e-4f)
        assertEquals("负值钳 0", 0f, SeedlingNotifTimePolicy.progressFraction(-5, 100, false)!!, 1e-4f)
    }

    @Test
    fun progressInvalid_positiveControl() {
        assertNull("阳性对照：max<=0 不画进度", SeedlingNotifTimePolicy.progressFraction(50, 0, false))
        assertNull("阳性对照：indeterminate 不画进度", SeedlingNotifTimePolicy.progressFraction(50, 100, true))
    }
}
