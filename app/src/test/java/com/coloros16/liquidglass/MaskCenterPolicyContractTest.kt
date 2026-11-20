package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.MaskCenterPolicy
import com.coloros16.liquidglass.engine.ScreenMetrics
import com.coloros16.liquidglass.engine.StyleCapabilities
import com.coloros16.liquidglass.engine.TransitionEdge
import com.coloros16.liquidglass.engine.TransitionEvaluator
import com.coloros16.liquidglass.engine.TransitionParams
import com.coloros16.liquidglass.ui.aod.AodStyleCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 样式遮罩中心契约：预览与 hook 同源，未声明时保持正中，声明后圆心真移动。
 */
class MaskCenterPolicyContractTest {

    private fun hook(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/hook/AodHandoffHook.kt",
    ).readText()

    private fun preview(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodPreviewView.kt",
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

    private fun out(x: Float, y: Float): FloatArray =
        FloatArray(2).also { MaskCenterPolicy.resolve(x, y, it) }

    @Test
    fun centerDefaultsToScreenMiddle() {
        val r = out(0.5f, 0.5f)
        assertEquals(0.5f, r[0], 0f)
        assertEquals(0.5f, r[1], 0f)
    }

    @Test
    fun declaredCenterPassesThrough() {
        val r = out(0.2f, 0.8f)
        assertEquals(0.2f, r[0], 1e-6f)
        assertEquals(0.8f, r[1], 1e-6f)
    }

    @Test
    fun outOfRangeIsClamped() {
        val r = out(-1f, 3f)
        assertEquals(0f, r[0], 0f)
        assertEquals(1f, r[1], 0f)
    }

    @Test
    fun nanFallsBackToCenter() {
        val r = out(Float.NaN, Float.NaN)
        assertEquals(0.5f, r[0], 0f)
        assertEquals(0.5f, r[1], 0f)
    }

    @Test
    fun shortOutArrayIsNotWritten() {
        val one = floatArrayOf(9f)
        MaskCenterPolicy.resolve(0.1f, 0.2f, one)
        assertEquals("长度不足不写，避免越界", 9f, one[0], 0f)
    }

    @Test
    fun declaredIgnoresExactCenter() {
        assertFalse(MaskCenterPolicy.declared(0.5f, 0.5f))
    }

    @Test
    fun declaredOnEitherAxisCounts() {
        assertTrue("只偏 x 也算声明", MaskCenterPolicy.declared(0.5f, 0.4f))
        assertTrue("只偏 y 也算声明", MaskCenterPolicy.declared(0.6f, 0.5f))
    }

    @Test
    fun withinEpsilonIsNotChanged() {
        assertFalse(MaskCenterPolicy.changed(0.5f, 0.5f, 0.5f + 1e-6f, 0.5f))
        assertTrue(MaskCenterPolicy.changed(0.5f, 0.5f, 0.5f + 1e-3f, 0.5f))
    }

    /** 阳性对照：容差若放到 0.01，真实的小幅声明会被判成未变，圆心不动。 */
    @Test
    fun positiveControlTooLooseToleranceHidesRealChange() {
        val loose = 0.01f
        val dx = kotlin.math.abs(0.5f - 0.505f)
        assertTrue("小幅声明必须被认为变化", MaskCenterPolicy.changed(0.5f, 0.5f, 0.505f, 0.5f))
        assertFalse("松容差会吞掉它", dx > loose)
    }

    /** 现状记录：十款样式全用默认正中 ⇒ 本轮接线是能力补齐，当前零可见差异。 */
    @Test
    fun allCurrentStylesUseDefaultCenter() {
        val declaredStyles = AodStyleCatalog.styles.filter {
            MaskCenterPolicy.declared(it.maskCenterX, it.maskCenterY)
        }
        assertEquals("当前无样式显式声明中心", 0, declaredStyles.size)
        assertEquals(10, AodStyleCatalog.styles.size)
    }

    /** 求值器必须真消费中心：非正中声明要让圆心像素真移动。 */
    @Test
    fun evaluatorConsumesCenter() {
        val screen = ScreenMetrics(1080f, 2400f, 3f)
        val caps = StyleCapabilities(mask = true, crossfade = true)
        val evaluator = TransitionEvaluator()
        val profile = evaluator.profileOf(TransitionEdge.AOD_TO_LOCK)
        profile.mask.enabled = true
        profile.content.enabled = false
        val mid = TransitionParams()
        val off = TransitionParams()
        evaluator.evaluate(TransitionEdge.AOD_TO_LOCK, 0.5f, screen, caps, caps, mid)
        profile.mask.centerXFraction = 0.2f
        profile.mask.centerYFraction = 0.8f
        evaluator.evaluate(TransitionEdge.AOD_TO_LOCK, 0.5f, screen, caps, caps, off)
        assertEquals(540f, mid.maskCenterX, 0.5f)
        assertEquals(1200f, mid.maskCenterY, 0.5f)
        assertEquals(216f, off.maskCenterX, 0.5f)
        assertEquals(1920f, off.maskCenterY, 0.5f)
    }

    /** 阳性对照：中心偏移会改变终止半径（离角更远），不是只挪圆心。 */
    @Test
    fun positiveControlOffCenterChangesEndRadius() {
        val screen = ScreenMetrics(1080f, 2400f, 3f)
        val caps = StyleCapabilities(mask = true, crossfade = true)
        val evaluator = TransitionEvaluator()
        val profile = evaluator.profileOf(TransitionEdge.AOD_TO_LOCK)
        profile.mask.enabled = true
        profile.content.enabled = false
        val mid = TransitionParams()
        val corner = TransitionParams()
        evaluator.evaluate(TransitionEdge.AOD_TO_LOCK, 1f, screen, caps, caps, mid)
        profile.mask.centerXFraction = 0f
        profile.mask.centerYFraction = 0f
        evaluator.evaluate(TransitionEdge.AOD_TO_LOCK, 1f, screen, caps, caps, corner)
        assertTrue("角上圆心终止半径更大", corner.maskRadius > mid.maskRadius)
    }

    @Test
    fun previewConsumesStyleCenter() {
        val src = preview()
        assertTrue(src.contains("maskProfile.mask.centerXFraction = value.maskCenterX"))
        assertTrue(src.contains("maskProfile.mask.centerYFraction = value.maskCenterY"))
    }

    @Test
    fun hookConsumesStyleCenter() {
        val src = body(hook(), "applyMaskCenter")
        assertTrue("hook 必须走同一份策略", src.contains("MaskCenterPolicy.resolve"))
        assertTrue("必须取样式声明", src.contains("AodStyleCatalog.find"))
        assertTrue("必须写 x", src.contains("mask.centerXFraction = center[0]"))
        assertTrue("必须写 y", src.contains("mask.centerYFraction = center[1]"))
    }

    /** 阳性对照：只写一条边会漏掉反向揭开，必须两条都写。 */
    @Test
    fun bothRevealEdgesAreWritten() {
        val src = hook()
        assertTrue(src.contains("TransitionEdge.AOD_TO_LOCK"))
        assertTrue(src.contains("TransitionEdge.LOCK_TO_AOD"))
        val loop = body(src, "applyMaskCenter")
        assertTrue("中心必须按边循环写", loop.contains("for (edge in MASK_CENTER_EDGES)"))
    }

    @Test
    fun centerWriteIsChangeGated() {
        val src = body(hook(), "applyMaskCenter")
        assertTrue("未变不写，避免逐帧改配方", src.contains("MaskCenterPolicy.changed"))
        assertTrue(src.contains("return"))
    }

    @Test
    fun styleOffResetsToCenter() {
        val src = body(hook(), "applyMaskCenter")
        assertTrue("样式关闭回正中", src.contains("style?.maskCenterX ?: MaskCenterPolicy.SCREEN_CENTER"))
        val refresh = body(hook(), "refreshRevealCapability")
        assertTrue("样式开关决定传不传 id", refresh.contains("if (styleOn) id else null"))
    }

    @Test
    fun centerRefreshSharesRevealRead() {
        val src = body(hook(), "refreshRevealCapability")
        assertTrue("共用一次读键，不另起轮询", src.contains("applyMaskCenter(b, styleId)"))
        assertTrue(src.contains("GATE_TTL_MS"))
        assertFalse("禁止新增独立计时器", src.contains("postDelayed"))
        assertFalse("禁止新增独立计时器", src.contains("Timer("))
    }

    @Test
    fun hotReloadResetsCenterCache() {
        val src = body(hook(), "shutdownForHotReload")
        assertTrue("热重载须清中心缓存，否则新一代写不进去", src.contains("maskCenterX = MaskCenterPolicy.SCREEN_CENTER"))
        assertTrue(src.contains("maskCenterY = MaskCenterPolicy.SCREEN_CENTER"))
    }

    @Test
    fun centerChangeIsLogged() {
        val src = body(hook(), "applyMaskCenter")
        assertTrue("中心变更必须有诊断日志", src.contains("aod-mask-center"))
    }
}
