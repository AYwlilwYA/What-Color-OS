package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.edit.ClockLayout
import com.coloros16.liquidglass.engine.edit.LockLayoutSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 时钟缩放手柄契约：左上角钉死、右下角拖，两套编辑画布共用同一份求值。
 *
 * ui/aod 类带 android 依赖不能在纯 JVM 加载，源码侧断言一律读文本。
 */
class ClockResizeAnchorContractTest {

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

    @Test
    fun resizeScaleUsesSharedTravelConstant() {
        assertEquals(0.35f, ClockLayout.RESIZE_TRAVEL, 1e-6f)
        val base = 1000f
        assertEquals(1.2f, ClockLayout.resizeScale(1f, 35f, 35f, base), 1e-4f)
        assertEquals(1f, ClockLayout.resizeScale(1f, 0f, 0f, base), 1e-4f)
        assertEquals(0.9f, ClockLayout.resizeScale(1f, -20f, -15f, base), 1e-4f)
    }

    @Test
    fun resizeScaleClampsToUserRange() {
        assertEquals(
            LockLayoutSettings.MAX_CLOCK_SCALE,
            ClockLayout.resizeScale(1f, 9999f, 9999f, 1000f),
            1e-4f,
        )
        assertEquals(
            LockLayoutSettings.MIN_CLOCK_SCALE,
            ClockLayout.resizeScale(1f, -9999f, -9999f, 1000f),
            1e-4f,
        )
    }

    @Test
    fun resizeScaleGuardsNonPositiveEdge() {
        assertEquals(1.2f, ClockLayout.resizeScale(1.2f, 500f, 500f, 0f), 1e-4f)
        assertEquals(1.2f, ClockLayout.resizeScale(1.2f, 500f, 500f, -3f), 1e-4f)
    }

    /** 左上角钉死：中心位移必须等于半宽/半高的增量。 */
    @Test
    fun anchorShiftKeepsTopLeftFixed() {
        val shift = ClockLayout.anchorShift(100f, 40f, 150f, 60f, 1000f, 2000f)
        assertEquals(0.05f, shift[0], 1e-6f)
        assertEquals(0.01f, shift[1], 1e-6f)
        val oldLeft = 500f - 100f
        val oldTop = 800f - 40f
        val newCx = 500f + shift[0] * 1000f
        val newCy = 800f + shift[1] * 2000f
        assertEquals(oldLeft, newCx - 150f, 1e-3f)
        assertEquals(oldTop, newCy - 60f, 1e-3f)
    }

    @Test
    fun anchorShiftIsZeroWhenSizeUnchanged() {
        val shift = ClockLayout.anchorShift(100f, 40f, 100f, 40f, 1000f, 2000f)
        assertEquals(0f, shift[0], 1e-6f)
        assertEquals(0f, shift[1], 1e-6f)
    }

    @Test
    fun anchorShiftGuardsNonPositiveCanvas() {
        val shift = ClockLayout.anchorShift(100f, 40f, 150f, 60f, 0f, 2000f)
        assertEquals(0f, shift[0], 1e-6f)
        assertEquals(0f, shift[1], 1e-6f)
    }

    @Test
    fun bothCanvasesCaptureAnchorOnHandleDown() {
        for (f in listOf("ui/aod/AodEditView.kt", "ui/aod/AodPreviewEditDelegate.kt")) {
            val text = src(f)
            assertTrue("$f 必须声明左上锚点字段", text.contains("resizeAnchorLeft"))
            assertTrue("$f 必须声明左上锚点字段", text.contains("resizeAnchorTop"))
            assertTrue("$f 按下时必须记框左上", text.contains("resizeAnchorLeft = clockBounds.left"))
            assertTrue("$f 按下时必须记框左上", text.contains("resizeAnchorTop = clockBounds.top"))
        }
    }

    @Test
    fun bothCanvasesDelegateResizeToSharedEngine() {
        for (f in listOf("ui/aod/AodEditView.kt", "ui/aod/AodPreviewEditDelegate.kt")) {
            val text = src(f)
            val fn = body(text, "resizeClock")
            assertTrue("$f 缩放必须走 ClockLayout.resizeScale", fn.contains("ClockLayout.resizeScale"))
            assertTrue("$f 必须按新框反算中心 X", fn.contains("resizeAnchorLeft + clockBounds.width() / 2f"))
            assertTrue("$f 必须按新框反算中心 Y", fn.contains("resizeAnchorTop + clockBounds.height() / 2f"))
        }
    }

    /** 阳性对照：旧的中心缩放写法（只改 scale 不动中心）检测器必报警。 */
    @Test
    fun positiveControlDetectsCenterOnlyResize() {
        val legacy = """
            private fun resizeClock(s: LockLayoutSettings, dx: Float, dy: Float) {
                val delta = (dx + dy) / (min(width, height) * 0.35f)
                s.clockScale = (startScale + delta).coerceAtMost(fitMaxScale())
            }
        """.trimIndent()
        assertTrue("旧写法不含共用求值", !legacy.contains("ClockLayout.resizeScale"))
        assertTrue("旧写法不反算中心", !legacy.contains("resizeAnchorLeft"))
    }

    /** 阳性对照：只改 scale 不移动中心时左上角必漂。 */
    @Test
    fun positiveControlCenterOnlyResizeMovesTopLeft() {
        val oldHalfW = 100f
        val newHalfW = 150f
        val cx = 500f
        val oldLeft = cx - oldHalfW
        val newLeftCenterOnly = cx - newHalfW
        assertTrue("中心缩放会让左边缘漂 50px", kotlin.math.abs(oldLeft - newLeftCenterOnly) > 40f)
        val shift = ClockLayout.anchorShift(oldHalfW, 40f, newHalfW, 60f, 1000f, 2000f)
        assertEquals(oldLeft, cx + shift[0] * 1000f - newHalfW, 1e-3f)
    }

    @Test
    fun moveAndUpShareTheSameResizeCall() {
        for (f in listOf("ui/aod/AodEditView.kt", "ui/aod/AodPreviewEditDelegate.kt")) {
            val text = src(f)
            val touch = body(text, "onTouchEvent")
            val calls = Regex("""resizeClock\(""").findAll(touch).count()
            assertEquals("$f MOVE 与 UP 必须各调一次同一个 resizeClock", 2, calls)
            assertTrue("$f 不得再留旧的 0.35f 就地算式", !touch.contains("* 0.35f"))
        }
    }
}