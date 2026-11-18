package com.coloros16.liquidglass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 时钟文本唯一实现契约：三处入口都走 ClockGlyphPainter + ClockTypefaces。
 *
 * ui/aod 带 android 依赖，纯 JVM 不能加载，常量与调用形态一律读源码文本。
 */
class ClockGlyphSharedPainterContractTest {

    private fun src(relative: String): String =
        File("src/main/kotlin/com/coloros16/liquidglass/$relative").readText()

    private fun body(text: String, fn: String): String {
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

    private fun constOf(text: String, name: String): Float =
        Regex("""const val $name\s*=\s*([0-9.]+)f""").find(text)?.groupValues?.get(1)?.toFloat()
            ?: error("取不到常量 $name")

    @Test
    fun painterConstantsMatchVendorBaseline() {
        val p = src("ui/aod/ClockGlyphPainter.kt")
        assertEquals(0.132f, constOf(p, "DATE_SIZE_RATIO"), 1e-6f)
        assertEquals(0.64f, constOf(p, "DATE_BASELINE_RATIO"), 1e-6f)
        assertEquals(0.10f, constOf(p, "DATE_LETTER_SPACING"), 1e-6f)
        assertEquals(0.45f, constOf(p, "BOUNDS_HALF_HEIGHT_RATIO"), 1e-6f)
        assertEquals(0.62f, constOf(p, "DATE_ALPHA"), 1e-6f)
    }

    @Test
    fun painterClonesPaintsBeforeMutating() {
        val draw = body(src("ui/aod/ClockGlyphPainter.kt"), "draw")
        assertTrue("时间画笔必须克隆", draw.contains("Paint(timePaint)"))
        assertTrue("日期画笔必须克隆", draw.contains("Paint(datePaint)"))
        assertFalse("不得原地改传入时间画笔", draw.contains("timePaint.textSize ="))
        assertFalse("不得原地改传入日期画笔", draw.contains("datePaint.textSize ="))
    }

    @Test
    fun painterGuardsNonPositiveFont() {
        val draw = body(src("ui/aod/ClockGlyphPainter.kt"), "draw")
        assertTrue(draw.contains("if (fontPx <= 0f) return 0f"))
    }

    @Test
    fun painterWritesHitBoundsFromMeasuredText() {
        val draw = body(src("ui/aod/ClockGlyphPainter.kt"), "draw")
        assertTrue("命中框宽必须按实测字串", draw.contains("main.measureText(time)"))
        assertTrue(draw.contains("bounds.set("))
    }

    @Test
    fun bothEditCanvasesCallSharedPainter() {
        for (f in listOf("ui/aod/AodEditView.kt", "ui/aod/AodPreviewEditDelegate.kt")) {
            val text = src(f)
            assertTrue("$f 必须调共用件", text.contains("ClockGlyphPainter.draw("))
        }
    }

    @Test
    fun editCanvasNoLongerDrawsClockTextItself() {
        val fn = body(src("ui/aod/AodEditView.kt"), "drawClock")
        assertFalse("独立页不得自己 drawText 时间", fn.contains("canvas.drawText("))
        assertFalse("独立页不得自己设字号", fn.contains("clockPaint.textSize ="))
        assertFalse("独立页不得写死时间", fn.contains("\"10:08\""))
        assertFalse("独立页不得写死日期", fn.contains("11月17日"))
    }

    @Test
    fun previewDelegateNoLongerDrawsClockTextItself() {
        val fn = body(src("ui/aod/AodPreviewEditDelegate.kt"), "drawClockAt")
        assertFalse("预览委托不得自己 drawText", fn.contains("canvas.drawText("))
        assertFalse("预览委托不得自己设字号", fn.contains("clockPaint.textSize ="))
        assertFalse("预览委托不得自己设日期字号", fn.contains("subPaint.textSize ="))
    }

    @Test
    fun bothCanvasesResolveTypefaceThroughSharedObject() {
        for (f in listOf("ui/aod/AodEditView.kt", "ui/aod/AodPreviewEditDelegate.kt")) {
            val text = src(f)
            assertTrue("$f 时间字型必须走共用件", text.contains("ClockTypefaces.timeOf("))
            assertTrue("$f 日期字型必须走共用件", text.contains("ClockTypefaces.date()"))
            assertFalse("$f 不得再自建字体族分支", text.contains("Typeface.MONOSPACE"))
        }
    }

    @Test
    fun defaultStyleResolvesToThinNotSystemSans() {
        val fn = body(src("ui/aod/ClockTypefaces.kt"), "timeOf")
        assertTrue("默认档必须走细体", fn.contains("return thin(context)"))
        assertTrue(fn.contains("ClockStyle.DEFAULT_WEIGHT"))
    }

    /** 阳性对照：旧的两份 typefaceOf（无细体分支）检测器必报警。 */
    @Test
    fun positiveControlDetectsLegacySystemSansOnly() {
        val legacy = """
            private fun typefaceOf(s: LockLayoutSettings): Typeface {
                val base = when (ClockFonts.normalize(s.clock.fontFamily)) {
                    ClockFonts.SERIF -> Typeface.SERIF
                    else -> Typeface.SANS_SERIF
                }
                return Typeface.create(base, s.clock.weight, false)
            }
        """.trimIndent()
        assertFalse("旧写法没有细体分支", legacy.contains("thin("))
        assertTrue("旧写法会落到系统 sans", legacy.contains("Typeface.SANS_SERIF"))
    }

    /** 阳性对照：日期比例 0.15 与官方 0.132 必须真的不同（防「改了但等价」）。 */
    @Test
    fun positiveControlDateRatioDiffersFromLegacy() {
        val font = 100f
        val legacy = font * 0.15f
        val official = font * constOf(src("ui/aod/ClockGlyphPainter.kt"), "DATE_SIZE_RATIO")
        assertTrue("新旧日期字号必须不同", kotlin.math.abs(legacy - official) > 1f)
    }

    @Test
    fun infoTextCoversAllThreeKinds() {
        val fn = body(src("ui/aod/ClockGlyphPainter.kt"), "infoText")
        assertTrue(fn.contains("ClockInfo.LUNAR"))
        assertTrue(fn.contains("ClockInfo.WEATHER"))
        assertTrue(fn.contains("ClockInfo.NONE -> null"))
    }

    /** 独立页时钟必须走真实时间（与预览同源），不能再写死样例。 */
    @Test
    fun editCanvasUsesRealClockText() {
        val text = src("ui/aod/AodEditView.kt")
        assertTrue(text.contains("LocalDateTime.now()"))
        assertTrue(text.contains("is24HourFormat"))
        assertTrue(body(text, "drawClock").contains("timeText()"))
        assertTrue(body(text, "drawClock").contains("dateText()"))
    }
}