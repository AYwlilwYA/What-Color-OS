package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SeedlingCrossfade
import com.coloros16.liquidglass.engine.SeedlingOvershootEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S2 双段过冲包络 + S5 交叉淡化契约：峰值/回落/内容脉冲/边界，含阳性对照。 */
class SeedlingOvershootContractTest {

    private val env = SeedlingOvershootEnvelope(
        fromWidth = 300f, toWidth = 430f,
        fromHeight = 100f, toHeight = 120f,
        extraWidthPx = 48f, extraHeightPx = 9f,
    )

    @Test
    fun phase1PeaksAboveTarget() {
        val s = env.sample(300L)
        assertEquals("第一段末=目标+过冲量", 478f, s.width, 0.5f)
        assertEquals(129f, s.height, 0.5f)
        assertEquals(1.1f, s.contentScale, 1e-3f)
        assertEquals(0f, s.contentBlurDp, 1e-3f)
    }

    @Test
    fun phase2SettlesExact() {
        val s = env.sample(800L)
        assertEquals(430f, s.width, 0.01f)
        assertEquals(120f, s.height, 0.01f)
        assertEquals(1f, s.contentScale, 1e-3f)
        assertTrue(s.done)
    }

    @Test
    fun contentBlurPulseStarts9() {
        val s = env.sample(0L)
        assertEquals("起始内容模糊 9dp", 9f, s.contentBlurDp, 1e-3f)
        assertEquals(1f, s.contentScale, 1e-3f)
    }

    @Test
    fun midPhase1Monotonic() {
        val a = env.sample(100L)
        val b = env.sample(200L)
        assertTrue("第一段宽度单调增", b.width > a.width)
        assertTrue("模糊单调减", b.contentBlurDp < a.contentBlurDp)
    }

    @Test
    fun midPhase2FallsBack() {
        val a = env.sample(400L)
        val b = env.sample(600L)
        assertTrue("第二段宽度回落", b.width < a.width)
        assertTrue("仍高于目标（未到底）", b.width > 430f)
    }

    @Test
    fun easeEndpoints_positiveControl() {
        assertEquals(0f, SeedlingOvershootEnvelope.ease(0f), 1e-3f)
        assertEquals(1f, SeedlingOvershootEnvelope.ease(1f), 1e-3f)
        val mid = SeedlingOvershootEnvelope.ease(0.5f)
        assertTrue("阳性对照：(0.3,0,0.1,1) 中点应 >0.5（前快后缓）", mid > 0.5f)
        assertTrue(mid < 1f)
    }

    @Test
    fun easeClamped() {
        assertEquals(0f, SeedlingOvershootEnvelope.ease(-1f), 1e-3f)
        assertEquals(1f, SeedlingOvershootEnvelope.ease(2f), 1e-3f)
    }

    @Test
    fun crossfadeMidpoint() {
        val cf = SeedlingCrossfade(200L)
        val s = cf.sample(100L)
        assertFalse(s.done)
        assertEquals("新旧 alpha 互补", 1f, s.fromAlpha + s.toAlpha, 1e-3f)
        assertTrue("中点新内容已过半（前快曲线）", s.toAlpha > 0.5f)
    }

    @Test
    fun crossfadeDone() {
        val cf = SeedlingCrossfade(200L)
        val s = cf.sample(200L)
        assertTrue(s.done)
        assertEquals(0f, s.fromAlpha, 1e-4f)
        assertEquals(1f, s.toAlpha, 1e-4f)
    }

    @Test
    fun zeroExtraNoOvershoot_positiveControl() {
        val flat = SeedlingOvershootEnvelope(300f, 430f, 100f, 120f, 0f, 0f)
        val peak = flat.sample(300L)
        assertEquals("阳性对照：零过冲量时峰值=目标（证明过冲真的来自 extra）", 430f, peak.width, 0.01f)
    }
}