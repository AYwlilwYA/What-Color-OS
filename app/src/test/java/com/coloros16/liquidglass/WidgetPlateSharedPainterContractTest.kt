package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.edit.EditLayoutSolver
import com.coloros16.liquidglass.engine.edit.EditSurface
import com.coloros16.liquidglass.engine.edit.LockLayoutSettings
import com.coloros16.liquidglass.engine.edit.WidgetCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 小组件卡片外观必须只有一份绘制实现，两套编辑画布都走它。
 *
 * 契约分两半：源码半直读三份源码断言「都调共用件 / 无私有卡片绘制残留」，
 * 字号半直读共用件常量复算收敛规则，每条判据都配阳性对照。
 */
class WidgetPlateSharedPainterContractTest {

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

    private fun painter() = source("ui/aod/WidgetPlatePainter.kt")
    private fun editView() = source("ui/aod/AodEditView.kt")
    private fun delegate() = source("ui/aod/AodPreviewEditDelegate.kt")

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

    private fun constOf(name: String): Float =
        Regex("""const val $name = ([0-9.]+)f""").find(painter())?.groupValues?.get(1)?.toFloatOrNull() ?: 0f

    @Test
    fun bothCanvasesCallTheSharedPainter() {
        for ((name, src) in listOf("AodEditView" to editView(), "AodPreviewEditDelegate" to delegate())) {
            val b = body(src, "drawWidgets")
            assertTrue("$name 取不到 drawWidgets，判据失效", b.length > 80)
            assertTrue("$name 没走共用卡片实现", b.contains("WidgetPlatePainter.draw("))
        }
    }

    @Test
    fun neitherCanvasDrawsItsOwnPlateOrName() {
        for ((name, src) in listOf("AodEditView" to editView(), "AodPreviewEditDelegate" to delegate())) {
            val b = body(src, "drawWidgets")
            assertFalse("$name 还自己画底板", b.contains("canvas.drawRoundRect"))
            assertFalse("$name 还自己画名牌", b.contains("canvas.drawText"))
            assertFalse("$name 还自己改字号", b.contains("textSize ="))
        }
    }

    @Test
    fun editCanvasNoLongerDrawsInnerPlaceholder() {
        val b = body(editView(), "drawWidgets")
        assertFalse("独立页还画内层占位框", b.contains("val inner"))
    }

    @Test
    fun positiveControlDetectorCatchesOldLeftAlignedName() {
        val buggy = """
            private fun drawWidgets(canvas: Canvas, s: LockLayoutSettings, w: Float, h: Float) {
                canvas.drawRoundRect(rect, radius, radius, platePaint)
                canvas.drawText(name, rect.left + 12f * density, rect.top + 22f * density, widgetTextPaint)
                val inner = RectF(rect.left, rect.top, rect.right, rect.bottom)
            }
        """.trimIndent()
        val b = body(buggy, "drawWidgets")
        assertTrue("检测器对左对齐名牌失灵", b.contains("canvas.drawText"))
        assertTrue("检测器对内层占位框失灵", b.contains("val inner"))
        assertFalse("检测器对旧写法失灵", b.contains("WidgetPlatePainter.draw("))
    }

    @Test
    fun painterCentersNameAndClonesTextPaint() {
        val b = body(painter(), "draw")
        assertTrue("取不到 draw，判据失效", b.length > 200)
        assertTrue("名牌没居中", b.contains("Paint.Align.CENTER"))
        assertTrue("名牌没画在卡片中心", b.contains("rect.centerX()"))
        assertTrue("直接改调用方 Paint 会污染后续绘制", b.contains("Paint(text)"))
        assertFalse("不许原地改传入画笔字号", b.contains("text.textSize ="))
    }

    @Test
    fun painterGuardsZeroSizeAndEmptyName() {
        val b = body(painter(), "draw")
        assertTrue("缺零尺寸守卫", b.contains("rect.width() <= 0f"))
        assertTrue("缺零密度守卫", b.contains("density <= 0f"))
        assertTrue("空名牌应只画底板", b.contains("if (name.isEmpty()) return"))
    }

    @Test
    fun nameSizeShrinksForLongNames() {
        val density = 3f
        val base = constOf("NAME_TEXT_DP")
        assertTrue("读不到名牌基准字号，判据失效", base > 0f)
        val widthPx = 200f
        val short = kotlin.math.min(base * density, widthPx / (2 + 1f))
        val long = kotlin.math.min(base * density, widthPx / (10 + 1f))
        assertEquals(base * density, short, 1e-4f)
        assertTrue("长名字必须缩字号", long < short)
        assertTrue("字号必须为正", long > 0f)
    }

    @Test
    fun nameSizeNeverExceedsCardWidth() {
        val density = 3f
        val base = constOf("NAME_TEXT_DP")
        assertTrue(base > 0f)
        for (len in listOf(1, 2, 4, 8, 16)) {
            for (widthPx in listOf(40f, 120f, 400f)) {
                val size = kotlin.math.min(base * density, widthPx / (len + 1f))
                assertTrue("名字总宽必须小于卡片宽 len=$len w=$widthPx", size * len < widthPx)
            }
        }
    }

    @Test
    fun positiveControlFixedFontSizeOverflowsNarrowCard() {
        val density = 3f
        val base = constOf("NAME_TEXT_DP")
        val widthPx = 40f
        val len = 8
        assertTrue("固定字号在窄卡片上必然溢出", base * density * len > widthPx)
        assertTrue("收敛后必须不溢出", kotlin.math.min(base * density, widthPx / (len + 1f)) * len < widthPx)
    }

    @Test
    fun nameSizeIsZeroForDegenerateInput() {
        val b = body(painter(), "nameSizePx")
        assertTrue("取不到 nameSizePx，判据失效", b.isNotEmpty())
        assertTrue("缺零宽守卫", b.contains("widthPx <= 0f"))
        assertTrue("缺零密度守卫", b.contains("density <= 0f"))
    }

    @Test
    fun cornerRadiusMatchesPreviewBaseline() {
        val corner = constOf("CORNER_DP")
        assertTrue("读不到圆角常量，判据失效", corner > 0f)
        assertEquals("圆角取预览侧基线", 12f, corner, 1e-4f)
    }

    @Test
    fun widgetRectStillComesFromSolver() {
        for ((name, src) in listOf("AodEditView" to editView(), "AodPreviewEditDelegate" to delegate())) {
            val b = body(src, "widgetRect")
            assertTrue("$name 取不到 widgetRect，判据失效", b.isNotEmpty())
            assertTrue("$name 矩形没走 solver", b.contains("EditLayoutSolver.widgetRect("))
            assertTrue("$name 缺 clampInto", b.contains("EditLayoutSolver.clampInto("))
        }
    }

    @Test
    fun everyCatalogWidgetHasAResolvableName() {
        val strings = File(root(), "app/src/main/res/values/strings.xml").readText()
        assertTrue("字符串资源读空，判据失效", strings.length > 1000)
        val defs = WidgetCatalog.all
        assertTrue("小组件目录读空，判据失效", defs.isNotEmpty())
        for (def in defs) {
            assertTrue("小组件 ${def.id} 名称键 ${def.nameKey} 不在 strings.xml", strings.contains("name=\"${def.nameKey}\""))
        }
    }

    @Test
    fun enabledWidgetsSurviveLayoutRoundTrip() {
        val s = LockLayoutSettings()
        val def = WidgetCatalog.all.first()
        assertTrue(s.setWidget(WidgetCatalog.create(def.id)!!))
        val parsed = LockLayoutSettings.parse(s.toText(), EditSurface.LOCK)
        assertEquals(1, EditLayoutSolver.enabledWidgets(parsed).size)
        assertEquals(def.id, EditLayoutSolver.enabledWidgets(parsed).first().typeId)
    }
}
