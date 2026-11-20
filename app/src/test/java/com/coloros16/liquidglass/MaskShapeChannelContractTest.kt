package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.FeatherPainter
import com.coloros16.liquidglass.engine.MaskGeometry
import com.coloros16.liquidglass.engine.MaskLayerConfig
import com.coloros16.liquidglass.engine.MaskShape
import com.coloros16.liquidglass.engine.ScreenMetrics
import com.coloros16.liquidglass.engine.StyleCapabilities
import com.coloros16.liquidglass.engine.TransitionEdge
import com.coloros16.liquidglass.engine.TransitionEvaluator
import com.coloros16.liquidglass.engine.TransitionParams
import com.coloros16.liquidglass.engine.TransitionProfile
import com.coloros16.liquidglass.engine.ContentLayerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 遮罩形状与羽化通道完备性契约：两种形状两侧渲染都在，默认圆形零回归。
 */
class MaskShapeChannelContractTest {

    private val screen = ScreenMetrics(1080f, 2400f, 3f)
    private val caps = StyleCapabilities(mask = true, crossfade = true)

    private fun src(rel: String): String = File("src/main/kotlin/com/coloros16/liquidglass/$rel").readText()

    private fun evalWith(shape: MaskShape, t: Float): TransitionParams {
        val ev = TransitionEvaluator()
        val p = ev.profileOf(TransitionEdge.AOD_TO_LOCK)
        p.mask.enabled = true
        p.mask.shape = shape
        p.content.enabled = false
        val out = TransitionParams()
        ev.evaluate(TransitionEdge.AOD_TO_LOCK, t, screen, caps, caps, out)
        return out
    }

    /** 默认形状是圆（零回归基准）。 */
    @Test
    fun defaultShapeIsCircle() {
        assertEquals(MaskShape.CIRCLE, MaskLayerConfig().shape)
        assertEquals(MaskShape.CIRCLE, evalWith(MaskLayerConfig().shape, 0.5f).maskShape)
    }

    /** ★引擎把两种形状都如实输出，不在求值层吞掉。 */
    @Test
    fun evaluatorPassesBothShapesThrough() {
        for (s in MaskShape.values()) {
            assertEquals("形状 $s 必须原样输出", s, evalWith(s, 0.5f).maskShape)
        }
    }

    /** 羽化按短边比例算并钳在上下限内。 */
    @Test
    fun featherFollowsConfigClamp() {
        val c = MaskLayerConfig()
        val expected = (screen.shortSide * c.featherFraction).coerceIn(c.featherMinPx, c.featherMaxPx)
        assertEquals(expected, evalWith(MaskShape.CIRCLE, 0.5f).maskFeather, 1e-3f)
        assertTrue("默认落在上下限之间", expected >= c.featherMinPx && expected <= c.featherMaxPx)
    }

    /** ★两侧渲染都必须有 ROUNDED_RECT 分支（缺一侧就是反向不对称）。 */
    @Test
    fun bothRenderersHandleRoundedRect() {
        assertTrue(
            "预览必须有圆角矩形分支",
            src("ui/aod/AodPreviewView.kt").contains("MaskShape.ROUNDED_RECT ->"),
        )
        assertTrue(
            "hook 必须有圆角矩形分支",
            src("hook/aod/AodTransitionLayer.kt").contains("MaskShape.ROUNDED_RECT ->"),
        )
    }

    /** 两侧都走同一份几何件，不自算。 */
    @Test
    fun bothUseSharedGeometry() {
        for (f in listOf("ui/aod/AodPreviewView.kt", "hook/aod/AodTransitionLayer.kt")) {
            val s = src(f)
            assertTrue("$f 必须用等面积半边长", s.contains("MaskGeometry.roundedRectHalfExtents("))
            assertTrue("$f 必须用圆角换算", s.contains("MaskGeometry.roundedRectCornerPx("))
        }
    }

    /** 羽化分派：圆走径向、圆角矩形走环带，两条都不是 NONE。 */
    @Test
    fun featherModeDispatchesByShape() {
        assertEquals(FeatherPainter.Mode.RADIAL, FeatherPainter.mode(MaskShape.CIRCLE, 300f, 40f))
        assertEquals(FeatherPainter.Mode.PATH_RING, FeatherPainter.mode(MaskShape.ROUNDED_RECT, 300f, 40f))
    }

    /** 阳性对照：两种形状必须分派到不同画法，否则形状被忽略。 */
    @Test
    fun positiveControlShapesMustNotShareMode() {
        val a = FeatherPainter.mode(MaskShape.CIRCLE, 300f, 40f)
        val b = FeatherPainter.mode(MaskShape.ROUNDED_RECT, 300f, 40f)
        assertFalse("同画法=形状被忽略（历史缺陷形态）", a == b)
    }

    /** 半径亚像素或无羽化时两条形状都退回硬边。 */
    @Test
    fun degenerateInputsFallBackToNone() {
        for (s in MaskShape.values()) {
            assertEquals(FeatherPainter.Mode.NONE, FeatherPainter.mode(s, 0.2f, 40f))
            assertEquals(FeatherPainter.Mode.NONE, FeatherPainter.mode(s, 300f, 0.2f))
        }
    }

    /** 等面积折算：矩形面积等于同半径圆面积（未触边界时）。 */
    @Test
    fun roundedRectKeepsCircleArea() {
        val r = 200f
        val half = MaskGeometry.roundedRectHalfExtents(r, 1080f, 2400f)
        val rectArea = (2f * half[0]) * (2f * half[1])
        val circleArea = Math.PI.toFloat() * r * r
        assertEquals(circleArea, rectArea, circleArea * 0.01f)
    }

    /** ★shape 无生产赋值点：这是「无需求」不是「通道断」，钉死现状防误判为缺口。 */
    @Test
    fun shapeHasNoProductionAssignmentByDesign() {
        var assignments = 0
        val root = File("src/main/kotlin/com/coloros16/liquidglass")
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            f.readLines().forEach { line ->
                if (Regex("""\bshape\s*=\s*MaskShape\.""").containsMatchIn(line)) assignments++
            }
        }
        assertEquals(
            "alive 与系统都只有圆形/素材形状，无方形揭开需求 ⇒ 不得凭空的给样式设形状",
            0, assignments,
        )
    }

    /** 阳性对照：消费分支存在 ≠ 有人赋值，两者必须分开数。 */
    @Test
    fun positiveControlConsumersExistWhileAssignmentsZero() {
        val consumers = listOf(
            "engine/FeatherPainter.kt",
            "hook/aod/AodTransitionLayer.kt",
            "ui/aod/AodPreviewView.kt",
        ).count { src(it).contains("MaskShape.ROUNDED_RECT") }
        assertEquals("三处消费方都在", 3, consumers)
        assertFalse("但没有任何生产赋值", src("engine/LayerConfigs.kt").contains("shape = MaskShape.ROUNDED_RECT"))
    }

    /** 羽化参数同样走默认：alive 无对应真值，不得凭推测改。 */
    @Test
    fun featherParamsStayAtDesignDefaults() {
        val c = MaskLayerConfig()
        assertEquals(1.05f, c.endRadiusScale, 1e-6f)
        assertEquals(0.08f, c.featherFraction, 1e-6f)
        assertEquals(24f, c.featherMinPx, 1e-6f)
        assertEquals(160f, c.featherMaxPx, 1e-6f)
    }
}