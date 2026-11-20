package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.FeatherPainter
import com.coloros16.liquidglass.engine.MaskGeometry
import com.coloros16.liquidglass.engine.MaskShape
import com.coloros16.liquidglass.engine.SystemSceneMotion
import com.coloros16.liquidglass.engine.WorldMaskPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * 遮罩羽化与压暗两侧同口径：分派同源、veil 取 dim、压暗只画一次。
 *
 * 修前预览三处偏离 hook：羽化恒走径向渐变（圆角矩形被涂黑）、veil 硬编码 0x66、
 * 底图整屏 dim 与 veil 相乘造成双重压暗；无羽化时预览干脆不画 veil（外侧零压暗）。
 */
class MaskVeilParityContractTest {

    private fun preview(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodPreviewView.kt",
    ).readText()

    private fun layer(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/hook/aod/AodTransitionLayer.kt",
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

    /** 手算圆角矩形 45° 角点到中心的距离，与实现无关地重算一遍。 */
    private fun roundedRectCornerDistance(r: Float, w: Float, h: Float): Float {
        val aspect = w / h
        val area = Math.PI.toFloat() * r * r
        var halfH = kotlin.math.sqrt(area / (4f * aspect))
        var halfW = halfH * aspect
        halfW = min(halfW, w)
        halfH = min(halfH, h)
        val corner = min(maxOf(0f, r), maxOf(0f, min(w, h) * 0.12f))
        val ax = w / 2f + halfW - corner
        val ay = h / 2f + halfH - corner
        val rad = Math.toRadians(45.0)
        val px = ax + corner * cos(rad).toFloat()
        val py = ay + corner * sin(rad).toFloat()
        return hypot(px - w / 2f, py - h / 2f)
    }

    // ---------------------------------------------------------------- 缺陷A：形状内被涂黑

    /** 圆角矩形角点到中心距离恒大于外径 ⇒ 径向渐变会把形状内部涂成不透明。 */
    @Test
    fun roundedRectCornersAlwaysFallOutsideRadialOuter() {
        val w = 1080f
        val h = 2400f
        val radii = listOf(200f, 400f, 600f, 900f, 1200f)
        for (r in radii) {
            val d = roundedRectCornerDistance(r, w, h)
            assertTrue("r=$r 角点距 $d 必须大于外径 $r（故径向渐变必涂黑形状内）", d > r)
        }
        assertEquals("五组", 5, radii.size)
    }

    /** 手算角点距对得上：r=600 时应为 819.25px（t=1.3654）。 */
    @Test
    fun cornerDistanceMatchesHandComputedValue() {
        assertEquals(819.25f, roundedRectCornerDistance(600f, 1080f, 2400f), 0.05f)
        assertEquals(240.30f, roundedRectCornerDistance(200f, 1080f, 2400f), 0.05f)
        assertEquals(1688.37f, roundedRectCornerDistance(1200f, 1080f, 2400f), 0.05f)
    }

    /** 分派件本身必须把圆角矩形判给路径环带，不能给径向。 */
    @Test
    fun dispatcherSendsRoundedRectToPathRing() {
        assertEquals(
            FeatherPainter.Mode.PATH_RING,
            FeatherPainter.mode(MaskShape.ROUNDED_RECT, 600f, 86.4f),
        )
        assertEquals(
            FeatherPainter.Mode.RADIAL,
            FeatherPainter.mode(MaskShape.CIRCLE, 600f, 86.4f),
        )
    }

    /** 阳性对照：修前预览无条件走径向 ⇒ 圆角矩形必然被涂黑。 */
    @Test
    fun positiveControlOldPreviewAlwaysUsedRadial() {
        val src = body(preview(), "drawMaskFeather")
        assertTrue("修后必须过分派", src.contains("FeatherPainter.mode(p.maskShape, p.maskRadius, p.maskFeather)"))
        assertTrue("修后有路径环带分支", src.contains("FeatherPainter.Mode.PATH_RING ->"))
        assertTrue("修后调用环带", src.contains("drawFeatherRing(canvas, w, h, p, veil)"))
    }

    @Test
    fun previewRingUsesSameStepSolverAsHook() {
        val pv = body(preview(), "drawFeatherRing")
        val hk = body(layer(), "drawFeatherRing")
        for (frag in listOf(
            "FeatherPainter.RING_STEPS",
            "FeatherPainter.ringStep(i, steps)",
            "inner + (outer - inner) * step[0]",
            "(baseAlpha * step[1]).toInt().coerceIn(0, 255)",
            "for (i in steps - 1 downTo 0)",
        )) {
            assertTrue("预览缺 $frag", pv.contains(frag))
            assertTrue("hook 缺 $frag", hk.contains(frag))
        }
    }

    /** 形状构造两侧同几何：都走 MaskGeometry 两函数，不自算。 */
    @Test
    fun shapeConstructionSharesGeometryOnBothSides() {
        val pv = body(preview(), "addMaskShape")
        val hk = body(layer(), "addShape")
        for (frag in listOf(
            "MaskGeometry.roundedRectHalfExtents(",
            "MaskGeometry.roundedRectCornerPx(",
        )) {
            assertTrue("预览缺 $frag", pv.contains(frag))
            assertTrue("hook 缺 $frag", hk.contains(frag))
        }
    }

    // ---------------------------------------------------------------- 缺陷B：veil 取 dim

    /** veil 不透明度必须取分层 dim，不得硬编码。 */
    @Test
    fun veilAlphaComesFromLayerDimNotConstant() {
        val src = body(preview(), "drawMaskFeather")
        assertTrue(
            "取 layers.dim",
            src.contains("Color.argb((layers.dim.coerceIn(0f, 1f) * 255f).toInt(), 0, 0, 0)"),
        )
        assertFalse("不得残留硬编码 0x66", preview().contains("0x66000000"))
    }

    /** 阳性对照：硬编码 0x66=102 与三态 dim 字节全都不同，最大差 4.08 倍。 */
    @Test
    fun positiveControlHardcodedVeilDivergesFromEveryStateDim() {
        val fixed = 0x66
        assertEquals(102, fixed)
        val cases = listOf(
            Triple("AOD", SystemSceneMotion.DIM_AOD, 158),
            Triple("LOCK", SystemSceneMotion.DIM_LOCK, 76),
            Triple("HOME", SystemSceneMotion.DIM_HOME, 25),
        )
        for ((name, dim, byte) in cases) {
            assertEquals("$name dim 字节", byte, (dim * 255f).toInt())
            assertTrue("$name 硬编码与真值必须不同", fixed != byte)
        }
        assertEquals("三态", 3, cases.size)
        assertEquals("桌面态差 4.08 倍", 4.08f, fixed.toFloat() / 25f, 0.01f)
    }

    @Test
    fun hookVeilAlsoTakesDim() {
        val src = body(layer(), "drawMaskVeil")
        assertTrue(src.contains("Color.argb((dim.coerceIn(0f, 1f) * 255f).toInt(), 0, 0, 0)"))
    }

    // ---------------------------------------------------------------- 缺陷C：双重压暗

    /** 遮罩承担压暗时底图不再画整屏 dim，与 hook WorldMaskPolicy 同口径。 */
    @Test
    fun previewSkipsBackdropDimWhenVeilOwnsIt() {
        val src = preview()
        assertTrue(
            "走共用门控",
            src.contains("WorldMaskPolicy.veilDrawable("),
        )
        assertTrue(
            "底图按门控给 0",
            src.contains("drawWallpaperOnce(canvas, pw, ph, if (veilOwnsDim) 0f else layers.dim)"),
        )
    }

    /** 手算：底 dim 与 veil 相乘会多暗，修后单次恰好等于 dim。 */
    @Test
    fun doubleDimQuantifiedAndSingleDimMatchesHook() {
        val cases = listOf(
            Triple("AOD", SystemSceneMotion.DIM_AOD, 0.8556f),
            Triple("LOCK", SystemSceneMotion.DIM_LOCK, 0.5100f),
            Triple("HOME", SystemSceneMotion.DIM_HOME, 0.1900f),
        )
        for ((name, dim, doubled) in cases) {
            val two = 1f - (1f - dim) * (1f - dim)
            assertEquals("$name 两次叠加", doubled, two, 0.0005f)
            assertTrue("$name 两次必然大于单次", two > dim)
            val single = (dim * 255f).toInt() / 255f
            assertEquals("$name 单次等于 dim（字节量化内）", dim, single, 0.0021f)
        }
        assertEquals("三态", 3, cases.size)
    }

    /** 阳性对照：修前硬编码 veil 与底 dim 相乘，桌面态多暗 0.36。 */
    @Test
    fun positiveControlOldPreviewOverDimmedHomeByThirtySixPoints() {
        val dim = SystemSceneMotion.DIM_HOME
        val oldVeil = 0x66 / 255f
        val eff = 1f - (1f - dim) * (1f - oldVeil)
        assertEquals(0.46f, eff, 0.0005f)
        assertEquals("多暗 36 个百分点", 0.36f, eff - dim, 0.0005f)
    }

    /** 门控四项必须与世界层/色键档互斥，不能只看 maskEnabled。 */
    @Test
    fun veilGateCarriesAllFourInputs() {
        val src = preview()
        val at = src.indexOf("WorldMaskPolicy.veilDrawable(")
        assertTrue(at >= 0)
        var depth = 0
        var end = at
        for (i in at until src.length) {
            val c = src[i]
            if (c == '(') depth++
            if (c == ')') { depth--; if (depth == 0) { end = i + 1; break } }
        }
        val call = src.substring(at, end)
        assertTrue("世界层让位", call.contains("worldDrawn = false"))
        assertTrue("遮罩开关", call.contains("maskEnabled = params.maskEnabled"))
        assertTrue("半径可画", call.contains("radiusDrawable = MaskGeometry.drawable(params.maskRadius)"))
        assertTrue("色键档让位", call.contains("maskByFrame = params.maskByFrame"))
    }

    /** 非场景路径无世界层，故 worldDrawn 恒 false 是正确的（不是漏读）。 */
    @Test
    fun nonScenePathHasNoWorldLayerSoWorldDrawnIsFalse() {
        assertTrue("策略件对 NONE 分支不 ownsDim", !WorldMaskPolicy.ownsDim(com.coloros16.liquidglass.engine.SkyBranchPolicy.Branch.NONE))
        assertTrue("色键档 veil 不画", !WorldMaskPolicy.veilDrawable(false, true, true, true))
        assertTrue("色键档 dim 也不画", !WorldMaskPolicy.dimDrawable(false, true, false))
    }

    // ---------------------------------------------------------------- 缺陷D：无羽化不画 veil

    /** 无羽化时也必须画硬边 veil（外侧要有压暗），不能整段跳过。 */
    @Test
    fun hardEdgeVeilIsDrawnEvenWithoutFeather() {
        val src = body(preview(), "drawMaskFeather")
        assertTrue("NONE 分支后仍有硬边绘制", src.contains("FeatherPainter.Mode.NONE -> Unit"))
        assertTrue("硬边走差集", src.contains("Path.Op.DIFFERENCE"))
        assertTrue("硬边也画", src.contains("canvas.drawPath(maskVeilPath, fill)"))
        assertTrue("硬边有诊断", src.contains("logFeather(\"hard\", p, outer, veil)"))
    }

    /** 阳性对照：修前调用点用 hasFeather 包住 ⇒ 无羽化时预览外侧零压暗。 */
    @Test
    fun positiveControlOldCallSiteGatedOnHasFeather() {
        val src = preview()
        assertFalse(
            "调用点不得再被 hasFeather 包住",
            src.contains("if (MaskGeometry.hasFeather(params.maskFeather)) {\n                        drawMaskFeather"),
        )
        assertTrue("现在无条件调", src.contains("drawMaskFeather(canvas, pw, ph, params)"))
    }

    /** 无羽化圆角矩形外侧占屏比例：小半径时高达 95.7%，零压暗差别极大。 */
    @Test
    fun hardEdgeOutsideAreaIsLargeForSmallRadius() {
        val w = 1080f
        val h = 2400f
        val r = 200f
        val half = MaskGeometry.roundedRectHalfExtents(r, w, h)
        val corner = MaskGeometry.roundedRectCornerPx(r, min(w, h))
        val area = (2f * half[0]) * (2f * half[1]) - (4f - Math.PI.toFloat()) * corner * corner
        val outside = 1f - area / (w * h)
        assertEquals("外侧占屏 95.7%", 0.957f, outside, 0.002f)
    }

    // ---------------------------------------------------------------- 器材与诊断

    /** 遮罩路径不得复用通用 path（别处也在用，会互相踩）。 */
    @Test
    fun veilUsesDedicatedPathNotSharedOne() {
        val pv = preview()
        assertTrue("专用遮罩路径", pv.contains("private val maskVeilPath = Path()"))
        assertTrue("专用整屏路径", pv.contains("private val maskFramePath = Path()"))
        val veil = body(pv, "drawMaskFeather")
        assertFalse("veil 不得用通用 path", veil.contains("addMaskShape(path,"))
        val ring = body(pv, "drawFeatherRing")
        assertFalse("环带不得用通用 path", ring.contains("addMaskShape(path,"))
    }

    @Test
    fun featherDiagnosticCarriesModeShapeAndDim() {
        val src = body(preview(), "logFeather")
        assertTrue("含画法", src.contains("mode="))
        assertTrue("含形状", src.contains("shape="))
        assertTrue("含外径", src.contains("outer="))
        assertTrue("含 dim", src.contains("dim="))
        assertTrue("含字节 alpha", src.contains("a="))
        assertTrue("节流", src.contains("now - featherLogAt < 800L"))
    }

    /** hook 侧羽化诊断补齐：画法与形状决定外侧压暗观感，早退也必须能分辨原因。 */
    @Test
    fun hookVeilDiagnosticCarriesModeShapeAndBounds() {
        val lay = layer()
        val fn = body(lay, "logMaskVeil")
        assertTrue("含画法", fn.contains("mode="))
        assertTrue("含形状", fn.contains("shape="))
        assertTrue("含内径", fn.contains("inner="))
        assertTrue("含外径", fn.contains("outer="))
        assertTrue("含 dim", fn.contains("dim="))
        assertTrue("含原因", fn.contains("reason="))
        val veil = body(lay, "drawMaskVeil")
        assertEquals(
            "画法只求值一次（分派与诊断必须同源）",
            1,
            Regex("""FeatherPainter\.mode\(""").findAll(veil).count(),
        )
        assertEquals("画与早退两处都要诊断", 2, Regex("""logMaskVeil\(""").findAll(veil).count())
        assertTrue("分派复用同一次求值", veil.contains("when (mode)"))
    }

    @Test
    fun hookVeilHasNoPolling() {
        for (name in listOf("drawMaskVeil", "drawFeatherRing", "logMaskVeil")) {
            val src = body(layer(), name)
            assertFalse("$name 不得 sleep", src.contains("Thread.sleep"))
            assertFalse("$name 不得自调度", src.contains("postDelayed"))
        }
    }

    @Test
    fun previewFeatherHasNoPolling() {
        for (name in listOf("drawMaskFeather", "drawFeatherRing", "logFeather")) {
            val src = body(preview(), name)
            assertFalse("$name 不得 sleep", src.contains("Thread.sleep"))
            assertFalse("$name 不得自调度", src.contains("postDelayed"))
        }
    }

    /** 羽化宽度仍走设计值，本轮不动真值（第15/16轮已裁决 alive 无对应物）。 */
    @Test
    fun featherWidthConstantsUnchanged() {
        val cfg = File("src/main/kotlin/com/coloros16/liquidglass/engine/LayerConfigs.kt").readText()
        assertTrue("featherFraction 仍 0.08", cfg.contains("featherFraction: Float = 0.08f"))
        assertTrue("下限仍 24", cfg.contains("featherMinPx: Float = 24f"))
        assertTrue("上限仍 160", cfg.contains("featherMaxPx: Float = 160f"))
        assertEquals("1080 短边羽化 86.4px", 86.4f, 1080f * 0.08f, 0.001f)
    }
}