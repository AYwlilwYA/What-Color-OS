package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SailLensMotion
import com.coloros16.liquidglass.engine.TransitionEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 预览侧镜片两条零消费通道接线契约：整屏轻模糊层与息屏时钟淡入淡出。
 *
 * 引擎件与渲染器件都在、每次还在算，但预览重构后调用点丢了 ⇒ 算了没人用。
 * 这里既钉接线，也钉真值曲线本身。
 */
class PreviewLensOrphanWiringContractTest {

    private val text =
        File("src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodPreviewView.kt").readText()

    private fun body(fn: String): String {
        val start = Regex("""^\s*(override |internal |private )*fun $fn\b""", RegexOption.MULTILINE)
            .find(text)?.range?.first ?: error("取不到 $fn")
        var depth = 0
        var i = text.indexOf('{', start)
        val from = i
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(from, i + 1)
                }
            }
            i++
        }
        error("$fn 括号不闭合")
    }

    private fun soft(edge: TransitionEdge?, t: Float) = SailLensMotion.blurClearAlpha(edge, t)

    private fun clockFade(edge: TransitionEdge?, t: Float): Float =
        SailLensMotion.Fade().also { SailLensMotion.fade(edge, t, it) }.aodClockAlpha

    /** 预览必须真的调 drawSoftBackdrop，不能只有渲染器里定义着。 */
    @Test
    fun previewCallsSoftBackdrop() {
        assertTrue("预览缺 drawSoftBackdrop 调用", text.contains("lensRenderer.drawSoftBackdrop("))
        assertTrue("必须读 blurClearAlpha 真值", text.contains("SailLensMotion.blurClearAlpha("))
    }

    /** 轻模糊层必须画在锐利底图之后、压暗层之前，两者才被同等压暗。 */
    @Test
    fun softLayerSitsBetweenBackdropAndDim() {
        val b = body("drawSailScene")
        val backdropAt = b.indexOf("drawEffectBackdrop(")
        val softAt = b.indexOf("drawBlurClearLayer(")
        val dimAt = b.indexOf("layers.dim")
        assertTrue("缺底图", backdropAt >= 0)
        assertTrue("缺轻模糊层", softAt >= 0)
        assertTrue("缺压暗层", dimAt >= 0)
        assertTrue("轻模糊必须在底图之后", backdropAt < softAt)
        assertTrue("轻模糊必须在压暗之前", softAt < dimAt)
    }

    /** 轻模糊层必须跟随壁纸缩放，否则与底图错框。 */
    @Test
    fun softLayerFollowsWallpaperScale() {
        val b = body("drawBlurClearLayer")
        // 缩放统一走取值件：静止/边内取引擎分层，落位补间期取补间值
        assertTrue("必须传壁纸缩放", b.contains("wallpaperZoom()"))
        assertTrue("取值件静止态仍读引擎分层", body("wallpaperZoom").contains("return layers.wallpaperScale"))
        assertTrue("必须有诊断日志", b.contains("logBlurClear("))
    }

    /** 时钟必须乘镜片淡出系数，否则转场期恒全亮。 */
    @Test
    fun clockMultipliesLensFade() {
        val b = body("drawSceneClock")
        assertTrue("时钟 alpha 必须乘镜片淡出", b.contains("pose.alpha * lensClockFade()"))
        assertTrue("必须读时钟专用通道", body("lensClockFade").contains("sailFade.aodClockAlpha"))
    }

    /** 淡出只作用镜片款，其它款恒 1（防治过头）。 */
    @Test
    fun fadeOnlyAppliesToLensStyle() {
        val b = body("lensClockFade")
        assertTrue("必须按镜片标志门控", b.contains("sceneSpec?.lens != true"))
        assertTrue("静止态必须恒 1", b.contains("if (effEdge == null) return 1f"))
    }

    /** 真值：解锁边前段满糊、55% 后清晰；反向边 45% 起糊。 */
    @Test
    fun blurClearTruthOnUnlockEdges() {
        assertEquals(1f, soft(TransitionEdge.LOCK_TO_HOME, 0f), 1e-4f)
        assertEquals(0f, soft(TransitionEdge.LOCK_TO_HOME, 0.60f), 1e-4f)
        assertEquals(0f, soft(TransitionEdge.LOCK_TO_HOME, 1f), 1e-4f)
        assertEquals(0f, soft(TransitionEdge.HOME_TO_LOCK, 0.40f), 1e-4f)
        assertEquals(1f, soft(TransitionEdge.HOME_TO_LOCK, 1f), 1e-4f)
    }

    /** 中间点必须单调，且两端之间确有过渡（不是硬切）。 */
    @Test
    fun blurClearIsMonotonicBothDirections() {
        var prev = 1f
        for (step in 0..20) {
            val v = soft(TransitionEdge.LOCK_TO_HOME, step / 20f)
            assertTrue("解锁边必须单调不增", v <= prev + 1e-4f)
            prev = v
        }
        prev = 0f
        for (step in 0..20) {
            val v = soft(TransitionEdge.HOME_TO_LOCK, step / 20f)
            assertTrue("回锁边必须单调不减", v >= prev - 1e-4f)
            prev = v
        }
        val mid = soft(TransitionEdge.LOCK_TO_HOME, 0.30f)
        assertTrue("中段必须在 0 与 1 之间", mid > 0.05f && mid < 0.95f)
    }

    /** 息屏相关的四条边不参与轻模糊（那是镜片交叠，不是整屏模糊）。 */
    @Test
    fun blurClearZeroOnAodEdges() {
        for (e in listOf(
            TransitionEdge.AOD_TO_LOCK, TransitionEdge.LOCK_TO_AOD,
            TransitionEdge.AOD_TO_HOME, TransitionEdge.HOME_TO_AOD,
        )) {
            for (step in 0..10) {
                assertEquals("边 $e 不该有整屏轻模糊", 0f, soft(e, step / 10f), 1e-6f)
            }
        }
        assertEquals("静止态不该有", 0f, soft(null, 0f), 1e-6f)
    }

    /** 时钟淡出与息屏层同窗同曲线（官方口径）。 */
    @Test
    fun clockFadeMatchesLayerFade() {
        for (e in listOf(
            TransitionEdge.AOD_TO_LOCK, TransitionEdge.AOD_TO_HOME,
            TransitionEdge.LOCK_TO_AOD, TransitionEdge.HOME_TO_AOD,
        )) {
            for (step in 0..10) {
                val t = step / 10f
                val f = SailLensMotion.Fade().also { SailLensMotion.fade(e, t, it) }
                assertEquals("边 $e t=$t 时钟与层同值", f.aodAlpha, f.aodClockAlpha, 1e-6f)
            }
        }
    }

    /** 定量：出场 t=0.72 时钟应已淡到约一半，旧行为恒 1 ⇒ 差近 0.48。 */
    @Test
    fun clockFadeQuantifiedAtMidExit() {
        val a = clockFade(TransitionEdge.AOD_TO_LOCK, 0.72f)
        assertTrue("t=0.72 应在 0.45~0.60（实测 $a）", a in 0.45f..0.60f)
        assertTrue("与旧恒 1 的差必须 >0.4", 1f - a > 0.4f)
        assertEquals("边末必须归零", 0f, clockFade(TransitionEdge.AOD_TO_LOCK, 1f), 1e-4f)
    }

    /** 解锁两边时钟不淡（锁屏面时钟归内容层管）。 */
    @Test
    fun clockFadeUnityOnUnlockEdges() {
        for (e in listOf(TransitionEdge.LOCK_TO_HOME, TransitionEdge.HOME_TO_LOCK)) {
            for (step in 0..10) {
                assertEquals("边 $e 时钟不该淡", 1f, clockFade(e, step / 10f), 1e-6f)
            }
        }
        assertEquals("静止态恒 1", 1f, clockFade(null, 0f), 1e-6f)
    }

    /** 阳性对照：不乘淡出的旧写法，检测器必须能抓到。 */
    @Test
    fun positiveControlOldClockIgnoresFade() {
        val legacy = "val a = pose.alpha.coerceIn(0f, 1f)"
        assertFalse("阳性样本不含淡出", legacy.contains("lensClockFade"))
        assertTrue("现实现含淡出", body("drawSceneClock").contains("lensClockFade()"))
    }

    /** 阳性对照：只有渲染器定义、预览零调用的孤儿态，检测器必须判红。 */
    @Test
    fun positiveControlOrphanRendererIsDetectable() {
        val renderer =
            File("src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodLensRenderer.kt").readText()
        assertTrue("渲染器必须有该能力", renderer.contains("fun drawSoftBackdrop("))
        assertTrue("渲染器必须仍在算预模糊", renderer.contains("lightSoft ="))
        val orphanPreview = "lensRenderer.setSource(...)\nlensRenderer.draw(...)"
        assertFalse("孤儿样本无该调用", orphanPreview.contains("drawSoftBackdrop"))
        assertTrue("现预览有该调用", text.contains("lensRenderer.drawSoftBackdrop("))
    }
}
