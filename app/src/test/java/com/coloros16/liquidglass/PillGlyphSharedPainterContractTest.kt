package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.edit.EditLayoutSolver
import com.coloros16.liquidglass.engine.edit.LockLayoutSettings
import com.coloros16.liquidglass.engine.edit.PillPlacement
import com.coloros16.liquidglass.engine.edit.PillType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 底部大丸内容图标必须只有一份绘制实现，两套编辑画布都走它。
 *
 * 契约分两半：几何半纯 JVM 复算（大丸矩形与图标缩放），源码半直读三份源码
 * 断言「分派齐全 / 无私有图标残留 / 不按槽位猜内容」，每条判据都配阳性对照。
 */
class PillGlyphSharedPainterContractTest {

    private fun root(): File {
        var dir = File(System.getProperty("user.dir"))
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        return dir
    }

    private fun source(rel: String): String {
        val f = File(root(), "app/src/main/kotlin/com/coloros16/liquidglass/$rel")
        assertTrue("找不到源码 $rel", f.isFile)
        val s = f.readText()
        assertTrue("源码读空，判据失效 $rel", s.length > 400)
        return s
    }

    private fun painter() = source("ui/aod/PillGlyphPainter.kt")
    private fun editView() = source("ui/aod/AodEditView.kt")
    private fun delegate() = source("ui/aod/AodPreviewEditDelegate.kt")

    /** 取某函数体（大括号配平），找不到返回空串。 */
    private fun body(src: String, fn: String): String {
        val lines = src.split("\n")
        val start = lines.indexOfFirst { Regex("""^\s*(override |internal |private )*fun $fn\b""").containsMatchIn(it) }
        if (start < 0) return ""
        var depth = 0
        var opened = false
        val out = StringBuilder()
        for (i in start until lines.size) {
            val l = lines[i]
            out.append(l).append('\n')
            depth += l.count { it == '{' } - l.count { it == '}' }
            if (l.contains('{')) opened = true
            if (opened && depth <= 0) break
        }
        return out.toString()
    }

    private val glyphFns = listOf("torch", "camera", "dial", "recorder", "calculator", "scan", "pay")

    /** 图标基准直径（dp）：直读共用实现的常量，读不到返回 0 让断言失败。 */
    private fun glyphBaseDp(): Float =
        Regex("""const val GLYPH_BASE_DP = ([0-9.]+)f""").find(painter())?.groupValues?.get(1)?.toFloatOrNull() ?: 0f

    @Test
    fun painterDispatchesEveryPillType() {
        val d = body(painter(), "draw")
        assertTrue("取不到 draw 函数体，判据失效", d.length > 200)
        for (t in PillType.values()) {
            assertTrue("内容 ${t.id} 没有分派分支", d.contains("PillType.${t.name} ->"))
        }
    }

    @Test
    fun painterImplementsEveryNonEmptyGlyph() {
        val src = painter()
        for (fn in glyphFns) {
            val b = body(src, fn)
            assertTrue("图标 $fn 没实现", b.contains("canvas.draw"))
        }
        assertFalse("「无」不该有图标实现", body(src, "none").isNotEmpty())
    }

    @Test
    fun bothCanvasesCallTheSharedPainter() {
        for ((name, src) in listOf("AodEditView" to editView(), "AodPreviewEditDelegate" to delegate())) {
            val b = body(src, "drawPills")
            assertTrue("$name 取不到 drawPills，判据失效", b.length > 100)
            assertTrue("$name 没走共用图标实现", b.contains("PillGlyphPainter.draw("))
        }
    }

    @Test
    fun neitherCanvasKeepsPrivateGlyphImplementations() {
        for ((name, src) in listOf("AodEditView" to editView(), "AodPreviewEditDelegate" to delegate())) {
            for (fn in glyphFns) {
                assertTrue("$name 还留着私有图标 $fn", body(src, fn).isEmpty())
            }
        }
    }

    @Test
    fun neitherCanvasGuessesContentFromSlotId() {
        for ((name, src) in listOf("AodEditView" to editView(), "AodPreviewEditDelegate" to delegate())) {
            val b = body(src, "drawPills")
            assertFalse("$name 还在按槽位 id 猜内容", b.contains("contains(\"flash\")"))
            assertTrue("$name 没按配置内容取图标", b.contains("contentOf(pill.typeId)") || b.contains("pillContent(pill.typeId)"))
        }
    }

    @Test
    fun positiveControlDetectorCatchesSlotGuessingWhenPresent() {
        val buggy = """
            private fun drawPills(canvas: Canvas, s: LockLayoutSettings, w: Float, h: Float) {
                if (pill.typeId.contains("flash")) drawTorch(canvas, rect) else drawCamera(canvas, rect)
            }
        """.trimIndent()
        val b = body(buggy, "drawPills")
        assertTrue("检测器对旧写法失灵", b.contains("contains(\"flash\")"))
        assertFalse("检测器对旧写法失灵", b.contains("PillGlyphPainter.draw("))
    }

    @Test
    fun positiveControlDetectorCatchesPrivateGlyphWhenPresent() {
        val buggy = """
            private fun camera(canvas: Canvas, c: Float, cy: Float, u: Float, stroke: Paint) {
                canvas.drawCircle(c, cy, 4f, stroke)
            }
        """.trimIndent()
        assertTrue("检测器对私有图标失灵", body(buggy, "camera").contains("canvas.draw"))
    }

    @Test
    fun glyphUnitShrinksProportionallyWithPillDiameter() {
        val density = 3f
        val dp = glyphBaseDp()
        assertTrue("读不到图标基准直径，判据失效", dp > 0f)
        val base = dp * density
        val out = EditLayoutSolver.Rect()
        assertTrue(EditLayoutSolver.pillRect(PillPlacement(PillType.CAMERA.id), 1080f, 2376f, out))
        val unit = out.width / base
        assertTrue("图标单位必须为正且小于半径", unit > 0f)
        assertEquals(0.5f, (out.width / 2f / base) / unit, 1e-6f)
    }

    @Test
    fun glyphStaysInsidePillAtDefaultScale() {
        val density = 3f
        val dp = glyphBaseDp()
        assertTrue("读不到图标基准直径，判据失效", dp > 0f)
        val out = EditLayoutSolver.Rect()
        assertTrue(
            EditLayoutSolver.pillRect(PillPlacement(PillType.CAMERA.id), 1080f, 2376f, out),
        )
        val unit = out.width / (dp * density)
        val farthestStrokePx = 16f * unit
        assertTrue(
            "手电筒最远光束必须落在大丸内：$farthestStrokePx vs 半径 ${out.width / 2f}",
            farthestStrokePx < out.width / 2f,
        )
    }

    @Test
    fun positiveControlUnscaledGlyphOverflowsShrunkPill() {
        val density = 3f
        val out = EditLayoutSolver.Rect()
        assertTrue(
            EditLayoutSolver.pillRect(PillPlacement(PillType.CAMERA.id, scale = EditLayoutSolver.MIN_PILL_SCALE), 1080f, 2376f, out),
        )
        val radius = out.width / 2f
        assertTrue(
            "不按直径缩放的旧写法在缩小态必然画出大丸：${16f * density} vs 半径 $radius",
            16f * density > radius,
        )
        assertTrue(
            "按直径缩放后必须收回大丸内：${16f * out.width / (glyphBaseDp() * density)} vs 半径 $radius",
            16f * out.width / (glyphBaseDp() * density) < radius,
        )
    }

    @Test
    fun glyphShrinksWhenPillScaledDown() {
        val density = 3f
        val dp = glyphBaseDp()
        val full = EditLayoutSolver.Rect()
        val half = EditLayoutSolver.Rect()
        assertTrue(EditLayoutSolver.pillRect(PillPlacement(PillType.SCAN.id, scale = 1f), 1080f, 2376f, full))
        assertTrue(EditLayoutSolver.pillRect(PillPlacement(PillType.SCAN.id, scale = 0.6f), 1080f, 2376f, half))
        val kFull = full.width / (dp * density)
        val kHalf = half.width / (dp * density)
        assertTrue("缩小大丸必须同比缩小图标", kHalf < kFull)
        assertEquals(0.6f, kHalf / kFull, 1e-4f)
    }

    @Test
    fun glyphScaleStaysPositiveAcrossAllowedPillScales() {
        val density = 2.75f
        val dp = glyphBaseDp()
        assertTrue("读不到图标基准直径，判据失效", dp > 0f)
        val base = dp * density
        val out = EditLayoutSolver.Rect()
        for (scale in listOf(0.1f, EditLayoutSolver.MIN_PILL_SCALE, 1f, EditLayoutSolver.MAX_PILL_SCALE, 9f)) {
            assertTrue(
                "大丸直径为 0，scale=$scale",
                EditLayoutSolver.pillRect(PillPlacement(PillType.CAMERA.id, scale = scale), 1080f, 2376f, out),
            )
            assertTrue("图标缩放必须为正，scale=$scale", out.width / base > 0f)
        }
    }

    @Test
    fun zeroSizedPillYieldsNoRect() {
        val out = EditLayoutSolver.Rect()
        assertFalse(EditLayoutSolver.pillRect(PillPlacement(PillType.CAMERA.id), 0f, 2376f, out))
        assertFalse(EditLayoutSolver.pillRect(PillPlacement(PillType.CAMERA.id), 1080f, 0f, out))
    }

    @Test
    fun pillRectClampsOutOfRangeCenter() {
        val out = EditLayoutSolver.Rect()
        assertTrue(EditLayoutSolver.pillRect(PillPlacement(PillType.PAY.id, x = 3f, y = -2f), 1000f, 2000f, out))
        assertEquals(1000f, out.centerX, 1e-3f)
        assertEquals(0f, out.centerY, 1e-3f)
    }

    @Test
    fun everyPillContentIsDispatchableIncludingNone() {
        val s = LockLayoutSettings()
        for (slot in listOf(PillType.FLASHLIGHT.id, PillType.CAMERA.id)) {
            for (content in PillType.values()) {
                assertTrue("内容 ${content.id} 写不进去", s.setPillContent(slot, content.id))
                assertEquals(content.id, s.pillContent(slot))
                assertTrue("内容 ${content.id} 解析不出来", PillType.from(s.pillContent(slot)) != null)
            }
        }
    }

    @Test
    fun positiveControlUnknownContentIsRejectedByConfig() {
        val s = LockLayoutSettings()
        assertFalse(s.setPillContent(PillType.CAMERA.id, "pill.teleport"))
        assertEquals(PillType.CAMERA.id, s.pillContent(PillType.CAMERA.id))
    }

    @Test
    fun everyPillTypeCarriesAnExistingStringResource() {
        val strings = File(root(), "app/src/main/res/values/strings.xml").readText()
        assertTrue("字符串资源读空，判据失效", strings.length > 1000)
        for (t in PillType.values()) {
            assertTrue("内容 ${t.id} 没有名称键", t.nameKey.isNotEmpty())
            assertTrue("名称键 ${t.nameKey} 不在 strings.xml", strings.contains("name=\"${t.nameKey}\""))
        }
    }

    @Test
    fun pillChoicesCoverEveryTypeExactlyOnce() {
        val choices = PillType.choices()
        assertEquals(PillType.values().size, choices.size)
        assertEquals(PillType.values().size, choices.map { it.id }.toSet().size)
        assertEquals(PillType.NONE, choices.first())
    }

    @Test
    fun bothPillPanelsBuildOptionsFromTheEnum() {
        val controller = source("ui/aod/AodEditController.kt")
        val inline = source("ui/aod/AodInlineEditPanel.kt")
        for ((name, src) in listOf("AodEditController" to controller, "AodInlineEditPanel" to inline)) {
            val b = body(src, "showPillPanel")
            assertTrue("$name 取不到 showPillPanel，判据失效", b.length > 200)
            assertTrue("$name 的选项没走枚举", b.contains("PillType.choices()"))
            assertFalse("$name 还在硬编码内容 id", b.contains("\"pill.dial\""))
        }
    }

    @Test
    fun neitherPillPanelGuessesNameFromSlotId() {
        for (rel in listOf("ui/aod/AodEditController.kt", "ui/aod/AodInlineEditPanel.kt")) {
            val b = body(source(rel), "showPillPanel")
            assertFalse("$rel 还在按槽位猜名字", b.contains("contains(\"flash\")"))
        }
    }

    @Test
    fun editCanvasDispatchesTapForPillAndWidget() {
        val b = body(editView(), "onTouchEvent")
        assertTrue("取不到 onTouchEvent，判据失效", b.length > 500)
        assertTrue("大丸点按没分派", b.contains("tapped && target == Kind.PILL -> targetId?.let { listener?.onPillTapped(it) }"))
        assertTrue("小组件点按没分派", b.contains("tapped && target == Kind.WIDGET -> targetId?.let { listener?.onWidgetTapped(it) }"))
    }

    @Test
    fun editCanvasListenerDeclaresBothTapCallbacks() {
        val src = editView()
        assertTrue("接口缺大丸点按回调", src.contains("fun onPillTapped(typeId: String)"))
        assertTrue("接口缺小组件点按回调", src.contains("fun onWidgetTapped(typeId: String)"))
    }

    @Test
    fun editControllerImplementsBothTapCallbacks() {
        val src = source("ui/aod/AodEditController.kt")
        assertTrue("大丸点按未实现", src.contains("override fun onPillTapped(typeId: String) = showPillPanel(typeId)"))
        assertTrue("小组件点按未实现", src.contains("override fun onWidgetTapped(typeId: String)"))
    }

    @Test
    fun positiveControlDetectorCatchesHardcodedOptionList() {
        val buggy = """
            fun showPillPanel(typeId: String, layout: LockLayoutSettings) {
                val options = listOf("pill.none" to 1, "pill.dial" to 2)
            }
        """.trimIndent()
        val b = body(buggy, "showPillPanel")
        assertTrue("检测器对硬编码选项失灵", b.contains("\"pill.dial\""))
        assertFalse("检测器对硬编码选项失灵", b.contains("PillType.choices()"))
    }
}
