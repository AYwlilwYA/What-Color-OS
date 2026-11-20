package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.ContentLayerConfig
import com.coloros16.liquidglass.engine.Easing
import com.coloros16.liquidglass.engine.MaskLayerConfig
import com.coloros16.liquidglass.engine.SceneLayerConfig
import com.coloros16.liquidglass.engine.ScreenMetrics
import com.coloros16.liquidglass.engine.SeamProfilePolicy
import com.coloros16.liquidglass.engine.SlideDirection
import com.coloros16.liquidglass.engine.StyleCapabilities
import com.coloros16.liquidglass.engine.TransitionEdge
import com.coloros16.liquidglass.engine.TransitionEvaluator
import com.coloros16.liquidglass.engine.TransitionParams
import com.coloros16.liquidglass.engine.TransitionProfile
import com.coloros16.liquidglass.ui.aod.AodSceneCatalog
import com.coloros16.liquidglass.ui.aod.AodStyleCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 预览侧非 SCENE 接缝配方接线契约：揭开圆外不淡出、推移边整屏纵向滑动无 crossfade。
 *
 * 与 hook 侧共用 SeamProfilePolicy，口径必须一致；SCENE 样式仍走交叠溶解不受影响。
 */
class PreviewSeamWiringContractTest {

    private val text =
        File("src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodPreviewView.kt").readText()

    private val screen = ScreenMetrics(1080f, 2400f, 3f)
    private val caps = StyleCapabilities(mask = true, crossfade = true)
    private val travel = SeamProfilePolicy.SLIDE_FRACTION * screen.heightPx

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

    private fun seamEval(edge: TransitionEdge, t: Float): TransitionParams {
        val ev = TransitionEvaluator()
        val p = TransitionProfile(
            scene = SceneLayerConfig(enabled = false),
            mask = MaskLayerConfig(enabled = false, easing = Easing.STANDARD),
            content = ContentLayerConfig(enabled = false, easing = Easing.STANDARD),
        ).also { SeamProfilePolicy.apply(edge, it) }
        ev.setProfile(edge, p)
        val out = TransitionParams()
        ev.evaluate(edge, t, screen, caps, caps, out)
        SeamProfilePolicy.opaqueSlide(ev.profileOf(edge).content, out)
        return out
    }

    /** 预览必须建接缝配方并调策略件，不能自己另写一套。 */
    @Test
    fun previewBuildsSeamProfilesViaPolicy() {
        assertTrue("必须 import 策略件", text.contains("import com.coloros16.liquidglass.engine.SeamProfilePolicy"))
        assertTrue("必须调 apply", text.contains("SeamProfilePolicy.apply(e, it)"))
        assertTrue("必须调 opaqueSlide", text.contains("SeamProfilePolicy.opaqueSlide(evaluator.profileOf(e).content, params)"))
    }

    /** 只有非 SCENE 样式吃接缝配方，SCENE 样式仍走交叠。 */
    @Test
    fun seamOnlyForNonSceneStyles() {
        val b = body("applyProfiles")
        assertTrue("必须按有无世界契约分流", b.contains("AodSceneCatalog.forStyle(value.id) != null"))
        assertTrue("SCENE 走 contentProfile", b.contains("if (scene) contentProfile else seamProfiles"))
        assertTrue("必须有诊断", b.contains("logSeam("))
    }

    /** 分流判据与目录一致：三款无契约样式吃接缝，六款有契约走交叠。 */
    @Test
    fun sceneClassificationMatchesCatalog() {
        val nonScene = listOf(
            AodStyleCatalog.ID_FULLSCREEN, AodStyleCatalog.ID_TEXT, AodStyleCatalog.ID_PORTRAIT,
        )
        val scene = listOf(
            AodStyleCatalog.ID_SAIL, AodStyleCatalog.ID_HANDPAINT, AodStyleCatalog.ID_LEAVE_LIGHT,
            AodStyleCatalog.ID_STARMOON, AodStyleCatalog.ID_MOUNTAIN, AodStyleCatalog.ID_GALAXY,
        )
        for (id in nonScene) assertTrue("款 $id 应无世界契约", AodSceneCatalog.forStyle(id) == null)
        for (id in scene) assertTrue("款 $id 应有世界契约", AodSceneCatalog.forStyle(id) != null)
    }

    /** ★揭开边源层整程恒不透明（含末帧），修前被钳成 0.999 会突降 0。 */
    @Test
    fun revealSourceStaysOpaqueThroughEnd() {
        for (e in listOf(TransitionEdge.AOD_TO_LOCK, TransitionEdge.LOCK_TO_AOD)) {
            for (step in 0..20) {
                val t = step / 20f
                assertEquals("边 $e t=$t 源层必须恒不透明", 1f, seamEval(e, t).srcAlpha, 1e-4f)
            }
            assertEquals("边 $e 源层不放大", 1f, seamEval(e, 1f).srcScale, 1e-4f)
        }
    }

    /** ★推移边两端恒不透明靠位移分离，无 crossfade。 */
    @Test
    fun slideEdgesHaveNoCrossfade() {
        val slide = listOf(
            TransitionEdge.LOCK_TO_HOME, TransitionEdge.HOME_TO_LOCK,
            TransitionEdge.AOD_TO_HOME, TransitionEdge.HOME_TO_AOD,
        )
        for (e in slide) {
            for (step in 0..10) {
                val p = seamEval(e, step / 10f)
                assertEquals("边 $e 源层恒不透明", 1f, p.srcAlpha, 1e-4f)
                assertEquals("边 $e 目标层恒不透明", 1f, p.dstAlpha, 1e-4f)
                assertEquals("边 $e 源层不缩放", 1f, p.srcScale, 1e-4f)
                assertEquals("边 $e 目标层不缩放", 1f, p.dstScale, 1e-4f)
                assertFalse("推移边不开遮罩", p.maskEnabled)
            }
        }
    }

    /**
     * ★符号约定：UP 的 slideY 为负，两层同向向上。
     * 起点目标在上方屏外(-travel)、源在原位；终点目标落位、源移到 -travel。
     */
    @Test
    fun slideSignConventionIsTwoLayersSameDirection() {
        val start = seamEval(TransitionEdge.LOCK_TO_HOME, 0f)
        val end = seamEval(TransitionEdge.LOCK_TO_HOME, 1f)
        assertEquals("起点目标在上方屏外", -travel, start.dstDy, 1f)
        assertEquals("起点源在原位", 0f, start.srcDy, 1f)
        assertEquals("终点目标落位", 0f, end.dstDy, 1f)
        assertEquals("终点源被推上去", -travel, end.srcDy, 1f)
    }

    /** 回内层方向相反：目标从下方来。 */
    @Test
    fun inwardEdgeComesFromBelow() {
        val start = seamEval(TransitionEdge.HOME_TO_LOCK, 0f)
        val end = seamEval(TransitionEdge.HOME_TO_LOCK, 1f)
        assertEquals("起点目标在下方屏外", travel, start.dstDy, 1f)
        assertEquals("终点源被推下去", travel, end.srcDy, 1f)
    }

    /** 行程恰为 0.22 屏高，六边一致。 */
    @Test
    fun travelIsExactFractionOfHeight() {
        assertEquals(528f, travel, 1e-3f)
        for (e in TransitionEdge.entries) {
            if (!SeamProfilePolicy.slideEdge(e)) continue
            val start = seamEval(e, 0f)
            assertEquals("边 $e 行程必须是 0.22 屏高", travel, kotlin.math.abs(start.dstDy), 1f)
        }
    }

    /** 手势条不再乘 0.7 二次衰减（需求第6项）。 */
    @Test
    fun gestureBarHasNoSecondaryFalloff() {
        assertFalse("不得再有 chrome * 0.7f", text.contains("chrome * 0.7f"))
        assertTrue("必须直接取内容进度", text.contains("drawGestureBar(canvas, w, h, chrome)"))
    }

    /**
     * 死字段清理前必须确认仍有读取点：三个字段现在都不是死字段。
     *
     * contentGate 在有窗口裁切的两个分支已置 1（改由几何承担），手绘与天空仍在消费。
     * sailFade 虽不再供 gate，但息屏层 alpha 与时钟淡入淡出仍读它。
     */
    @Test
    fun gateFieldsStillConsumedSoNotRemovable() {
        assertTrue("contentGate 仍被消费", text.contains("layers.lockAlpha * contentGate"))
        assertTrue("aodLayerAlpha 仍被消费", text.contains("if (aodLayerAlpha > 0.002f)"))
        assertTrue("sailFade 仍被消费", text.contains("sailFade.aodClockAlpha"))
        assertTrue("sailFade 息屏层仍在读", text.contains("sailFade.aodAlpha"))
        assertTrue("有窗口分支置 1", text.contains("contentGate = 1f"))
    }

    /** 阳性对照：默认配方（未接策略件）在末帧会淡出，证明接线真改变了行为。 */
    @Test
    fun positiveControlDefaultProfileFadesAtEnd() {
        val ev = TransitionEvaluator()
        val p = TransitionProfile(
            scene = SceneLayerConfig(enabled = false),
            mask = MaskLayerConfig(enabled = true, easing = Easing.STANDARD),
            content = ContentLayerConfig(enabled = false, easing = Easing.STANDARD),
        )
        ev.setProfile(TransitionEdge.AOD_TO_LOCK, p)
        val out = TransitionParams()
        ev.evaluate(TransitionEdge.AOD_TO_LOCK, 1f, screen, caps, caps, out)
        assertTrue("默认配方末帧源层必须已淡出（证明接缝配方非空操作）", out.srcAlpha < 0.01f)
        assertEquals("接缝配方末帧仍恒 1", 1f, seamEval(TransitionEdge.AOD_TO_LOCK, 1f).srcAlpha, 1e-4f)
    }

    /** 阳性对照：默认 content 配方是 crossfade，与推移口径必须不同。 */
    @Test
    fun positiveControlDefaultContentIsCrossfade() {
        val ev = TransitionEvaluator()
        val p = TransitionProfile(
            scene = SceneLayerConfig(enabled = false),
            mask = MaskLayerConfig(enabled = false),
            content = ContentLayerConfig(enabled = true, direction = SlideDirection.UP, slideFraction = 0.22f),
        )
        ev.setProfile(TransitionEdge.LOCK_TO_HOME, p)
        val out = TransitionParams()
        ev.evaluate(TransitionEdge.LOCK_TO_HOME, 0.5f, screen, caps, caps, out)
        assertTrue("未改写时是 crossfade（两端各半）", out.srcAlpha < 0.9f && out.dstAlpha < 0.9f)
        val seam = seamEval(TransitionEdge.LOCK_TO_HOME, 0.5f)
        assertEquals("改写后源恒 1", 1f, seam.srcAlpha, 1e-4f)
        assertEquals("改写后目标恒 1", 1f, seam.dstAlpha, 1e-4f)
    }
}
