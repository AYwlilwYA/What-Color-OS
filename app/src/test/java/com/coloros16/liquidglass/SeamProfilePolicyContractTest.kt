package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.ContentLayerConfig
import com.coloros16.liquidglass.engine.MaskLayerConfig
import com.coloros16.liquidglass.engine.ScreenMetrics
import com.coloros16.liquidglass.engine.SeamProfilePolicy
import com.coloros16.liquidglass.engine.SlideDirection
import com.coloros16.liquidglass.engine.StyleCapabilities
import com.coloros16.liquidglass.engine.TransitionBridge
import com.coloros16.liquidglass.engine.TransitionEdge
import com.coloros16.liquidglass.engine.TransitionEvaluator
import com.coloros16.liquidglass.engine.TransitionParams
import com.coloros16.liquidglass.engine.TransitionProfile
import com.coloros16.liquidglass.engine.from
import com.coloros16.liquidglass.engine.to
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 非 SCENE 接缝配方契约：揭开边圆外不淡出，其余边整屏推移不溶解。
 */
class SeamProfilePolicyContractTest {

    private fun hook(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/hook/AodHandoffHook.kt",
    ).readText()

    private fun bridgeSrc(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/engine/TransitionBridge.kt",
    ).readText()

    private fun body(text: String, name: String): String {
        val at = text.indexOf("fun $name(")
        if (at < 0) return ""
        var depth = 0
        var started = false
        val sb = StringBuilder()
        for (i in at until text.length) {
            val c = text[i]
            sb.append(c)
            if (c == '{') { depth++; started = true }
            if (c == '}') { depth--; if (started && depth == 0) break }
        }
        return sb.toString()
    }

    private fun profileFor(edge: TransitionEdge): TransitionProfile =
        TransitionProfile(mask = MaskLayerConfig(enabled = true), content = ContentLayerConfig())
            .also { SeamProfilePolicy.apply(edge, it) }

    private val screen = ScreenMetrics(1080f, 2400f, 3f)
    private val caps = StyleCapabilities(mask = true, crossfade = true)

    private fun evalWith(edge: TransitionEdge, t: Float): TransitionParams {
        val ev = TransitionEvaluator()
        ev.setProfile(edge, profileFor(edge))
        val out = TransitionParams()
        ev.evaluate(edge, t, screen, caps, caps, out)
        SeamProfilePolicy.opaqueSlide(ev.profileOf(edge).content, out)
        return out
    }

    @Test
    fun revealEdgesAreTheTwoAodLockOnes() {
        assertTrue(SeamProfilePolicy.revealEdge(TransitionEdge.AOD_TO_LOCK))
        assertTrue(SeamProfilePolicy.revealEdge(TransitionEdge.LOCK_TO_AOD))
        for (e in TransitionEdge.values()) {
            if (e == TransitionEdge.AOD_TO_LOCK || e == TransitionEdge.LOCK_TO_AOD) continue
            assertFalse("边 $e 不是揭开边", SeamProfilePolicy.revealEdge(e))
            assertTrue("其余边都推移", SeamProfilePolicy.slideEdge(e))
        }
    }

    /** 方向按三态由内到外：息屏0 锁屏1 桌面2，向外向上、向内向下。 */
    @Test
    fun slideDirectionFollowsStateRank() {
        assertEquals(SlideDirection.UP, SeamProfilePolicy.slideDirection(TransitionEdge.LOCK_TO_HOME))
        assertEquals(SlideDirection.UP, SeamProfilePolicy.slideDirection(TransitionEdge.AOD_TO_HOME))
        assertEquals(SlideDirection.DOWN, SeamProfilePolicy.slideDirection(TransitionEdge.HOME_TO_LOCK))
        assertEquals(SlideDirection.DOWN, SeamProfilePolicy.slideDirection(TransitionEdge.HOME_TO_AOD))
    }

    @Test
    fun revealProfileKeepsSourceOpaque() {
        val p = profileFor(TransitionEdge.AOD_TO_LOCK)
        assertTrue(p.mask.enabled)
        assertEquals(1f, p.mask.outerFadeStart, 0f)
        assertEquals(1f, p.mask.outerScaleTo, 0f)
        assertFalse("揭开边不开内容层", p.content.enabled)
    }

    @Test
    fun slideProfileDisablesMask() {
        for (e in arrayOf(
            TransitionEdge.LOCK_TO_HOME, TransitionEdge.HOME_TO_LOCK,
            TransitionEdge.AOD_TO_HOME, TransitionEdge.HOME_TO_AOD,
        )) {
            val p = profileFor(e)
            assertFalse("推移边无圆", p.mask.enabled)
            assertTrue(p.content.enabled)
            assertEquals(0.22f, p.content.slideFraction, 1e-6f)
        }
    }

    /** ★核心：揭开全程源层恒不透明（修前 e>0.35 就开始淡出）。 */
    @Test
    fun sourceStaysOpaqueAcrossWholeReveal() {
        for (i in 0..10) {
            val t = i / 10f
            val p = evalWith(TransitionEdge.AOD_TO_LOCK, t)
            assertEquals("t=$t 源层必须恒不透明", 1f, p.srcAlpha, 1e-6f)
            assertEquals("t=$t 源层不放大", 1f, p.srcScale, 1e-6f)
        }
    }

    /** ★阳性对照：旧默认配方在 t=0.5 已明显淡出且放大。 */
    @Test
    fun positiveControlOldProfileFadedAndScaled() {
        val ev = TransitionEvaluator()
        val old = ev.profileOf(TransitionEdge.AOD_TO_LOCK)
        old.mask.enabled = true
        old.content.enabled = true
        val out = TransitionParams()
        ev.evaluate(TransitionEdge.AOD_TO_LOCK, 0.5f, screen, caps, caps, out)
        assertTrue("旧配方此刻已淡出", out.srcAlpha < 0.5f)
        assertTrue("旧配方此刻已放大", out.srcScale > 1.02f)
    }

    /** 推移期两端都不透明：目标滑入盖住源，不是溶解。 */
    @Test
    fun slideKeepsBothLayersOpaque() {
        for (i in 1..9) {
            val t = i / 10f
            val p = evalWith(TransitionEdge.LOCK_TO_HOME, t)
            assertEquals("t=$t 源恒不透明", 1f, p.srcAlpha, 1e-6f)
            assertEquals("t=$t 目标恒不透明", 1f, p.dstAlpha, 1e-6f)
        }
    }

    /** ★阳性对照：不做 opaqueSlide 时就是 crossfade。 */
    @Test
    fun positiveControlWithoutOpaqueSlideIsCrossfade() {
        val ev = TransitionEvaluator()
        ev.setProfile(TransitionEdge.LOCK_TO_HOME, profileFor(TransitionEdge.LOCK_TO_HOME))
        val out = TransitionParams()
        ev.evaluate(TransitionEdge.LOCK_TO_HOME, 0.5f, screen, caps, caps, out)
        assertTrue("求值器默认给的是溶解", out.srcAlpha < 0.9f && out.dstAlpha < 0.9f)
        SeamProfilePolicy.opaqueSlide(ev.profileOf(TransitionEdge.LOCK_TO_HOME).content, out)
        assertEquals(1f, out.srcAlpha, 1e-6f)
        assertEquals(1f, out.dstAlpha, 1e-6f)
    }

    /**
     * 位移真值：0.22 屏高 = 528px，两层同向。
     *
     * 求值器 UP 的 slideY 为负，src=`slideY*e`、dst=`slideY*(1-e)`
     * ⇒ 起点目标在上方 -528、源在 0；终点目标落位 0、源移到 -528。
     */
    @Test
    fun slideTravelIsFractionOfHeight() {
        val start = evalWith(TransitionEdge.LOCK_TO_HOME, 0f)
        val end = evalWith(TransitionEdge.LOCK_TO_HOME, 1f)
        assertEquals("起点目标在上方行程处", -528f, start.dstDy, 1f)
        assertEquals("起点源在原位", 0f, start.srcDy, 1f)
        assertEquals("终点目标落位", 0f, end.dstDy, 1f)
        assertEquals("终点源被推上去", -528f, end.srcDy, 1f)
    }

    @Test
    fun downwardEdgeMovesOpposite() {
        val start = evalWith(TransitionEdge.HOME_TO_LOCK, 0f)
        val end = evalWith(TransitionEdge.HOME_TO_LOCK, 1f)
        assertEquals("向内推目标从下方来", 528f, start.dstDy, 1f)
        assertEquals("向内推源被推下去", 528f, end.srcDy, 1f)
        assertEquals(0f, end.dstDy, 1f)
    }

    /** 揭开边不得被 opaqueSlide 误改（内容层未启用）。 */
    @Test
    fun opaqueSlideIgnoresRevealEdges() {
        val p = profileFor(TransitionEdge.AOD_TO_LOCK)
        val out = TransitionParams().apply { srcAlpha = 0.4f; dstAlpha = 0.6f }
        SeamProfilePolicy.opaqueSlide(p.content, out)
        assertEquals(0.4f, out.srcAlpha, 1e-6f)
        assertEquals(0.6f, out.dstAlpha, 1e-6f)
    }

    @Test
    fun opaqueSlideIgnoresZeroTravel() {
        val c = ContentLayerConfig(enabled = true, direction = SlideDirection.UP, slideFraction = 0f)
        val out = TransitionParams().apply { srcAlpha = 0.4f }
        SeamProfilePolicy.opaqueSlide(c, out)
        assertEquals(0.4f, out.srcAlpha, 1e-6f)
    }

    @Test
    fun hookSetsProfilePerEdge() {
        val b = body(hook(), "createBridge")
        assertTrue("必须逐边建配方", b.contains("for (edge in TransitionEdge.values())"))
        assertTrue("必须走共用策略", b.contains("SeamProfilePolicy.apply(edge, profile)"))
        assertTrue("必须写进求值器", b.contains("evaluator.setProfile(edge, profile)"))
    }

    @Test
    fun bridgeAppliesOpaqueSlide() {
        val b = body(bridgeSrc(), "evaluateNow")
        assertTrue(
            "求值后必须改写成推移口径",
            b.contains("SeamProfilePolicy.opaqueSlide(evaluator.profileOf(edge).content, params)"),
        )
    }

    @Test
    fun bridgeLogsSeamMode() {
        assertTrue(hook().contains("seam=reveal(noOuterFade)+slide("))
    }

    /**
     * ★非 SCENE 样式必须走配方而不是分层真值。
     *
     * 分层通道没有位移，走它会把推移整个丢掉（本轮抓到的二次缺口）。
     */
    @Test
    fun nonSceneStyleUsesParamsNotLayers() {
        assertEquals(
            com.coloros16.liquidglass.engine.ContentMotionSource.Source.PARAMS,
            com.coloros16.liquidglass.engine.ContentMotionSource.choose(true, hasWorld = false),
        )
        assertEquals(
            com.coloros16.liquidglass.engine.ContentMotionSource.Source.LAYERS,
            com.coloros16.liquidglass.engine.ContentMotionSource.choose(true, hasWorld = true),
        )
    }

    /** 阳性对照：分层填充的位移恒 0，证明推移只能从配方来。 */
    @Test
    fun positiveControlLayersHaveNoTravel() {
        val l = com.coloros16.liquidglass.engine.SystemSceneMotion.Layers()
        com.coloros16.liquidglass.engine.SystemSceneMotion.sample(TransitionEdge.LOCK_TO_HOME, 0.5f, l)
        val poses = com.coloros16.liquidglass.engine.ContentLayerMapper.Poses()
        com.coloros16.liquidglass.engine.ContentMotionSource.fill(l, TransitionEdge.LOCK_TO_HOME, 1f, poses)
        assertEquals(0f, poses.lock.dy, 0f)
        assertEquals(0f, poses.home.dy, 0f)
        assertTrue("配方才有位移", kotlin.math.abs(evalWith(TransitionEdge.LOCK_TO_HOME, 0.5f).srcDy) > 1f)
    }

    /** hook 必须按有无世界层分流，不能只看有没有分层。 */
    @Test
    fun hookBranchesOnWorldPresence() {
        val s = File("src/main/kotlin/com/coloros16/liquidglass/hook/aod/AodStageHook.kt").readText()
        val b = body(s, "applyContentLayers")
        assertTrue("必须取世界契约", b.contains("val hasWorld = worldSpec() != null"))
        assertTrue("必须传两个条件", b.contains("choose(layers != null, hasWorld)"))
        assertTrue("日志用实际来源", b.contains("logContentSource(useLayers, edge)"))
    }

    /** 默认配方仍在（未识别边兜底），不被逐边配方取代。 */
    @Test
    fun defaultProfileStillPresent() {
        assertTrue(body(hook(), "createBridge").contains("defaultProfile = TransitionProfile("))
    }

    /** 六边端点：推移边起点目标在屏外行程处、终点落位，无跳变。 */
    @Test
    fun slideEndpointsAreContinuous() {
        for (e in TransitionEdge.values()) {
            if (!SeamProfilePolicy.slideEdge(e)) continue
            val a = evalWith(e, 0f)
            val b = evalWith(e, 1f)
            assertEquals("边 $e 起点目标未落位", 0f, b.dstDy, 1f)
            assertTrue("边 $e 起点目标有行程", kotlin.math.abs(a.dstDy) > 100f)
            assertEquals("边 $e 起点源在原位", 0f, a.srcDy, 1f)
        }
    }

    /** 方向与端点一致性：UP 的目标起点在上方（负 y），DOWN 在下方（正 y）。 */
    @Test
    fun directionMatchesStartOffsetSign() {
        for (e in TransitionEdge.values()) {
            if (!SeamProfilePolicy.slideEdge(e)) continue
            val a = evalWith(e, 0f)
            val expectedNegative = SeamProfilePolicy.slideDirection(e) == SlideDirection.UP
            assertEquals("边 $e 起点偏移符号", expectedNegative, a.dstDy < 0f)
        }
    }

    /** ★求值器口径：fadeStart>=1 必须整程不淡出（修前被 coerce 到 0.999 导致末帧突降 0）。 */
    @Test
    fun fadeStartOneMeansNoFadeAtAll() {
        val ev = TransitionEvaluator()
        val p = ev.profileOf(TransitionEdge.AOD_TO_LOCK)
        p.mask.enabled = true
        p.content.enabled = false
        p.mask.outerFadeStart = 1f
        for (i in 0..20) {
            val out = TransitionParams()
            ev.evaluate(TransitionEdge.AOD_TO_LOCK, i / 20f, screen, caps, caps, out)
            assertEquals("t=${i / 20f} 恒不透明", 1f, out.srcAlpha, 1e-6f)
        }
    }

    /** 阳性对照：0.999 仍在末帧淡出，证明 1f 必须走独立分支而不是钳位。 */
    @Test
    fun positiveControlJustBelowOneStillFades() {
        val ev = TransitionEvaluator()
        val p = ev.profileOf(TransitionEdge.AOD_TO_LOCK)
        p.mask.enabled = true
        p.content.enabled = false
        p.mask.outerFadeStart = 0.999f
        val out = TransitionParams()
        ev.evaluate(TransitionEdge.AOD_TO_LOCK, 1f, screen, caps, caps, out)
        assertTrue("0.999 末帧仍会淡出", out.srcAlpha < 0.5f)
    }

    /** 旧默认 0.35 行为不变（零回归）。 */
    @Test
    fun defaultFadeStartUnchanged() {
        val ev = TransitionEvaluator()
        val p = ev.profileOf(TransitionEdge.AOD_TO_LOCK)
        p.mask.enabled = true
        p.content.enabled = false
        assertEquals(0.35f, p.mask.outerFadeStart, 1e-6f)
        val out = TransitionParams()
        ev.evaluate(TransitionEdge.AOD_TO_LOCK, 0.5f, screen, caps, caps, out)
        assertTrue("默认仍在淡出", out.srcAlpha < 0.9f)
    }
}
