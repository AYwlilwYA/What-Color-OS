package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SeedlingRectSpring
import com.coloros16.liquidglass.engine.SeedlingSpring
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 接管岛弹簧积分器契约：收敛/过冲/超时/档位映射/参数换算，含阳性对照。 */
class SeedlingSpringContractTest {

    private fun runToSettle(s: SeedlingSpring, maxSec: Float = 5f): Float {
        var t = 0f
        while (t < maxSec && s.advance(1f / 60f)) t += 1f / 60f
        return t
    }

    @Test
    fun criticallyDampedNoOvershoot() {
        val s = SeedlingSpring(0f, 100f, stiffness = 380f, dampingRatio = 1.0f)
        var maxV = 0f
        var t = 0f
        while (t < 5f && s.advance(1f / 60f)) {
            maxV = maxOf(maxV, s.value)
            t += 1f / 60f
        }
        assertTrue("临界阻尼不得过冲", maxV <= 100.01f)
        assertEquals(100f, s.value, 0.5f)
    }

    @Test
    fun underdampedOvershoots_positiveControl() {
        val s = SeedlingSpring(0f, 100f, stiffness = 380f, dampingRatio = 0.42f)
        var maxV = 0f
        var t = 0f
        while (t < 5f && s.advance(1f / 60f)) {
            maxV = maxOf(maxV, s.value)
            t += 1f / 60f
        }
        assertTrue("阳性对照：欠阻尼必须过冲（证明测试真的在测过冲）", maxV > 101f)
    }

    @Test
    fun settlesToTarget() {
        val s = SeedlingSpring(0f, 430f, stiffness = 380f, dampingRatio = 0.62f)
        val t = runToSettle(s)
        assertTrue("应在超时前收敛 t=$t", t < 2f)
        assertEquals(430f, s.value, 0.35f)
        assertFalse(s.advance(1f / 60f))
    }

    @Test
    fun timeoutSnapsToTarget() {
        val s = SeedlingSpring(0f, 1000f, stiffness = 1f, dampingRatio = 1f, timeoutMs = 300L)
        var t = 0f
        while (t < 2f && s.advance(1f / 60f)) t += 1f / 60f
        assertEquals("超时必须直落目标", 1000f, s.value, 0.001f)
        assertTrue("超时应发生在 ~300ms", t in 0.25f..0.45f)
    }

    @Test
    fun retargetResetsElapsed() {
        val s = SeedlingSpring(0f, 100f, stiffness = 380f, dampingRatio = 0.62f)
        repeat(10) { s.advance(1f / 60f) }
        s.setTarget(50f)
        val t = runToSettle(s)
        assertEquals(50f, s.value, 0.35f)
        assertTrue(t < 2f)
    }

    @Test
    fun subStepStability_largeDt() {
        val a = SeedlingSpring(0f, 100f, stiffness = 1200f, dampingRatio = 0.18f)
        val b = SeedlingSpring(0f, 100f, stiffness = 1200f, dampingRatio = 0.18f)
        var t = 0f
        while (t < 0.5f) {
            a.advance(0.064f)
            repeat(4) { b.advance(0.016f) }
            t += 0.064f
        }
        assertEquals("大 dt 子步积分应与小 dt 路径一致", b.value, a.value, 2f)
    }

    @Test
    fun bounceStopsExact() {
        assertEquals(1.0f, SeedlingSpring.bounceDpToDamping(0f), 1e-4f)
        assertEquals(0.8f, SeedlingSpring.bounceDpToDamping(1f), 1e-4f)
        assertEquals(0.75f, SeedlingSpring.bounceDpToDamping(2f), 1e-4f)
        assertEquals(0.62f, SeedlingSpring.bounceDpToDamping(5f), 1e-4f)
        assertEquals(0.42f, SeedlingSpring.bounceDpToDamping(8f), 1e-4f)
    }

    @Test
    fun bounceInterpolatesAndClamps() {
        assertEquals(0.775f, SeedlingSpring.bounceDpToDamping(1.5f), 1e-3f)
        assertEquals(1.0f, SeedlingSpring.bounceDpToDamping(-3f), 1e-4f)
        assertEquals(0.42f, SeedlingSpring.bounceDpToDamping(99f), 1e-4f)
    }

    @Test
    fun stiffnessScalesWithSpeedSquared() {
        assertEquals(380f, SeedlingSpring.scaledStiffness(380f, 1f, 1f), 1e-3f)
        assertEquals(380f * 0.81f, SeedlingSpring.scaledStiffness(380f, 0.9f, 1f), 1e-2f)
        assertEquals(0f, SeedlingSpring.scaledStiffness(380f, 1f, 0f), 1e-4f)
    }

    @Test
    fun timeoutScalesInverseSpeed() {
        assertEquals(2000L, SeedlingSpring.scaledTimeoutMs(1f))
        assertEquals(2222L, SeedlingSpring.scaledTimeoutMs(0.9f))
        assertEquals(2000L, SeedlingSpring.scaledTimeoutMs(2f))
    }

    @Test
    fun rectSpringAllChannelsConverge() {
        val r = SeedlingRectSpring(0f, 0f, 360f, 120f)
        r.setTarget(100f, 20f, 900f, 480f)
        var t = 0f
        while (t < 5f && r.advance(1f / 60f)) t += 1f / 60f
        assertEquals(100f, r.x.value, 0.35f)
        assertEquals(20f, r.y.value, 0.35f)
        assertEquals(900f, r.w.value, 0.35f)
        assertEquals(480f, r.h.value, 0.35f)
        assertFalse(r.advance(1f / 60f))
    }

    @Test
    fun zeroAnimatorScaleMeansNoMotion_positiveControl() {
        val k = SeedlingSpring.scaledStiffness(380f, 1f, 0f)
        assertEquals(0f, k, 1e-4f)
        val s = SeedlingSpring(0f, 100f, stiffness = 0f, dampingRatio = 1f, timeoutMs = 50L)
        var t = 0f
        while (t < 1f && s.advance(1f / 60f)) t += 1f / 60f
        assertEquals("零刚度靠超时直落（系统动画关=直落语义）", 100f, s.value, 0.001f)
    }

    @Test
    fun shiftMovesValueAndTargetTogether() {
        val s = SeedlingSpring(10f, 10f)
        s.shift(5f)
        assertEquals(15f, s.value, 1e-4f)
        assertFalse("平移后仍收敛", s.advance(1f / 60f))
    }
}