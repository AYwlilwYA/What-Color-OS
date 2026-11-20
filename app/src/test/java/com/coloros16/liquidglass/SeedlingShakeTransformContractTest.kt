package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SeedlingCardTransform
import com.coloros16.liquidglass.engine.SeedlingShake
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 晃动弹簧 + 大卡入场/按压变换契约，含阳性对照。 */
class SeedlingShakeTransformContractTest {

    // ---------- 晃动 ----------

    @Test
    fun shakeStartsAtInitial() {
        val s = SeedlingShake(initialPx = 16f)
        assertEquals(16f, s.offsetAt(0L), 0.01f)
    }

    @Test
    fun shakeOscillates_positiveControl() {
        val s = SeedlingShake()
        var sawPositive = false
        var sawNegative = false
        for (t in 0..600 step 5) {
            val o = s.offsetAt(t.toLong())
            if (o > 1f) sawPositive = true
            if (o < -1f) sawNegative = true
        }
        assertTrue("阳性对照：欠阻尼必双向振荡（非单向衰减）", sawPositive && sawNegative)
    }

    @Test
    fun shakeDecaysToZero() {
        val s = SeedlingShake()
        assertTrue("2s 内必收敛", s.settled(2000L))
        assertEquals(0f, s.offsetAt(2000L), 1e-4f)
        assertEquals(0f, s.offsetAt(10000L), 1e-4f)
    }

    @Test
    fun shakeEnvelopeMonotonic() {
        val s = SeedlingShake()
        val e100 = kotlin.math.abs(s.offsetAt(100L))
        val e800 = kotlin.math.abs(s.offsetAt(800L))
        // 包络指数衰减：晚时刻振幅上限必更小（取多帧最大避免过零点误判）
        var maxEarly = 0f
        var maxLate = 0f
        for (t in 0..200 step 5) maxEarly = maxOf(maxEarly, kotlin.math.abs(s.offsetAt(t.toLong())))
        for (t in 600..800 step 5) maxLate = maxOf(maxLate, kotlin.math.abs(s.offsetAt(t.toLong())))
        assertTrue("晚段振幅 < 早段", maxLate < maxEarly)
    }

    @Test
    fun negativeTimeZero() {
        assertEquals(0f, SeedlingShake().offsetAt(-5L), 1e-4f)
    }

    // ---------- 入场/按压 ----------

    @Test
    fun enterScaleEndpoints() {
        assertEquals(0.96f, SeedlingCardTransform.enterScale(0f), 1e-4f)
        assertEquals(1f, SeedlingCardTransform.enterScale(1f), 1e-4f)
        assertEquals(0.98f, SeedlingCardTransform.enterScale(0.5f), 1e-4f)
    }

    @Test
    fun enterDropEndpoints() {
        assertEquals(12f, SeedlingCardTransform.enterDropPx(0f, 4f, 3f), 1e-4f)
        assertEquals(0f, SeedlingCardTransform.enterDropPx(1f, 4f, 3f), 1e-4f)
    }

    @Test
    fun pressScaleClamped() {
        assertEquals(0.96f, SeedlingCardTransform.pressScale(true), 1e-4f)
        assertEquals(1f, SeedlingCardTransform.pressScale(false), 1e-4f)
        assertEquals("越界钳到 0.9", 0.9f, SeedlingCardTransform.pressScale(true, 0.5f), 1e-4f)
        assertEquals(1f, SeedlingCardTransform.pressScale(true, 1.2f), 1e-4f)
    }

    @Test
    fun capsulePhaseNoCardTransform() {
        for (u in listOf(0f, 0.2f, 0.5f)) {
            val t = SeedlingCardTransform.enterProgress(u)
            assertEquals("胶囊相位入场完成度必为 1", 1f, t, 1e-4f)
            assertEquals("胶囊态入场 scale 必为 1", 1f, SeedlingCardTransform.enterScale(t), 1e-4f)
            assertEquals("胶囊态 drop 必为 0", 0f, SeedlingCardTransform.enterDropPx(t, 4f, 3f), 1e-4f)
        }
        val t = SeedlingCardTransform.enterProgress(0.75f)
        assertEquals("过渡中点 scale=0.98", 0.98f, SeedlingCardTransform.enterScale(t), 1e-4f)
        assertEquals("过渡中点 drop=6px", 6f, SeedlingCardTransform.enterDropPx(t, 4f, 3f), 1e-4f)
        assertEquals("满展开 scale=1", 1f, SeedlingCardTransform.enterScale(SeedlingCardTransform.enterProgress(1f)), 1e-4f)
        assertEquals("满展开 drop=0", 0f, SeedlingCardTransform.enterDropPx(SeedlingCardTransform.enterProgress(1f), 4f, 3f), 1e-4f)
    }

    @Test
    fun morphClamped_positiveControl() {
        assertEquals("阳性对照：morph 越界钳位不出怪值", 1f, SeedlingCardTransform.enterScale(2f), 1e-4f)
        assertEquals(0.96f, SeedlingCardTransform.enterScale(-1f), 1e-4f)
    }
}
