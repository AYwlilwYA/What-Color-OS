package com.coloros16.liquidglass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 两套编辑画布选中态外观必须同源的契约。
 *
 * 外观常量只允许在共享绘制器里出现一次；命中区与绘制位置必须由同一常量派生。
 */
class EditChromeSharedContractTest {

    private fun read(name: String): String =
        File("src/main/kotlin/com/coloros16/liquidglass/ui/aod/$name.kt").readText()

    private fun chrome(): String = read("EditChromePainter")
    private fun editView(): String = read("AodEditView")
    private fun delegate(): String = read("AodPreviewEditDelegate")

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

    /** 两侧选框都必须走共享件，不得自己 drawRoundRect/drawCircle 画选框。 */
    @Test
    fun bothCanvasesUseSharedSelection() {
        for ((name, src) in listOf("AodEditView" to editView(), "Delegate" to delegate())) {
            val b = body(src, "drawSelection")
            assertTrue("$name 选框必须走共享件", b.contains("EditChromePainter.drawSelection"))
            assertFalse("$name 不得自画圆角选框", b.contains("canvas.drawRoundRect"))
        }
    }

    /** 时钟八点手柄与钩两侧都必须走共享件。 */
    @Test
    fun bothCanvasesUseSharedClockHandles() {
        for ((name, src) in listOf("AodEditView" to editView(), "Delegate" to delegate())) {
            val b = body(src, "drawSelection")
            assertTrue("$name 手柄必须走共享件", b.contains("EditChromePainter.drawClockHandles"))
            assertFalse("$name 不得自画手柄圆点", b.contains("canvas.drawCircle(rect.right"))
        }
    }

    /** ★命中区与绘制位置同源：两侧都用 nearHook，不得各写半径。 */
    @Test
    fun hitTestSharesHookCenterWithDrawing() {
        assertTrue("独立页命中走共享件", editView().contains("EditChromePainter.nearHook"))
        assertTrue("预览命中走共享件", delegate().contains("EditChromePainter.nearHook"))
        val c = chrome()
        assertTrue(
            "钩心偏移由钩半径派生不是另写常量",
            c.contains("const val HOOK_CENTER_PAD_DP = HOOK_RADIUS_DP * HOOK_OFFSET_RATIO"),
        )
        val hit = body(c, "nearHook")
        assertTrue("命中用同一偏移", hit.contains("HOOK_CENTER_PAD_DP"))
        val draw = body(c, "drawClockHandles")
        assertTrue("绘制用同一比例", draw.contains("HOOK_OFFSET_RATIO"))
    }

    /** ★阳性对照：旧写法命中取框角、钩画在框外，两者必然错开。 */
    @Test
    fun positiveControlOldHitMissesTheVisibleHook() {
        val density = 2.625f
        val right = 500f
        val bottom = 800f
        val hookX = right + 8f * density * 0.4f
        val oldHitCenterX = right
        assertTrue(
            "旧命中中心与可见钩心错开 3.2dp",
            kotlin.math.abs(hookX - oldHitCenterX) > 3f * density,
        )
        val newHitCenterX = right + 8f * 0.4f * density
        assertEquals("新命中中心与钩心重合", hookX, newHitCenterX, 1e-3f)
    }

    /** 底板色只允许共享件里有一份字面量。 */
    @Test
    fun plateColorHasSingleSource() {
        assertTrue("共享件定义底板色", chrome().contains("const val PLATE_COLOR = 0x24FFFFFF"))
        for ((name, src) in listOf("AodEditView" to editView(), "Delegate" to delegate())) {
            assertTrue("$name 引用共享底板色", src.contains("EditChromePainter.PLATE_COLOR"))
            assertFalse("$name 不得再写 0x1FFFFFFF", src.contains("0x1FFFFFFF"))
            assertFalse("$name 不得再写 0x24FFFFFF", src.contains("0x24FFFFFF"))
        }
    }

    /** 日期色同源。 */
    @Test
    fun dateColorHasSingleSource() {
        assertTrue("共享件定义日期色", chrome().contains("const val DATE_COLOR = 0xFFE7EDF4"))
        for ((name, src) in listOf("AodEditView" to editView(), "Delegate" to delegate())) {
            assertTrue("$name 引用共享日期色", src.contains("EditChromePainter.DATE_COLOR"))
            assertFalse("$name 不得再写日期色字面量", src.contains("0xFFE7EDF4"))
        }
    }

    /** 时钟命中框留白：绘制、离屏测量、缩放上限扣量三处必须同一常量。 */
    @Test
    fun clockPaddingIsOneConstantAcrossThreeUses() {
        assertTrue("共享件定义留白", chrome().contains("const val CLOCK_PAD_DP = 12f"))
        val v = editView()
        val d = delegate()
        assertEquals("独立页五处用留白常量（绘制/离屏测量/扣量/拉伸钳制/降级态收紧）", 5, v.split("EditChromePainter.CLOCK_PAD_DP").size - 1)
        assertEquals("预览五处用留白常量（绘制/离屏测量/扣量/拉伸钳制/降级态收紧）", 5, d.split("EditChromePainter.CLOCK_PAD_DP").size - 1)
        assertFalse("独立页不得再写 28dp 扣量", v.contains("28f * density"))
        assertFalse("预览不得再写 24dp 扣量", d.contains("24f * density"))
        // 按被治对象的三个函数点名，不对全文禁某个数值（同值的无关用途会被误伤）
        assertFalse("独立页绘制留白不得写死", body(v, "drawClock").contains("14f * density"))
        assertFalse("独立页离屏测量留白不得写死", body(v, "drawClockOffscreen").contains("14f * density"))
        assertFalse("预览离屏测量留白不得写死", body(d, "measureClockBounds").contains("12f * density"))
    }

    /** ★扣量必须等于两倍留白：框宽减去左右两条留白才是字串宽。 */
    @Test
    fun fitScaleSubtractsExactlyTwoPaddings() {
        for ((name, src) in listOf("AodEditView" to editView(), "Delegate" to delegate())) {
            assertTrue(
                "$name 扣量=2×留白",
                src.contains("clockBounds.width() - 2f * EditChromePainter.CLOCK_PAD_DP * density"),
            )
        }
        val pad = 12f
        val density = 2.625f
        val boxWidth = 400f
        val drawn = boxWidth - 2f * pad * density
        assertEquals("扣量等于左右两条留白", boxWidth - 2 * pad * density, drawn, 1e-4f)
    }

    /** 选框与参考线画笔由共享件构造，虚线参数不再各写一套。 */
    @Test
    fun dashPaintsComeFromSharedFactory() {
        for ((name, src) in listOf("AodEditView" to editView(), "Delegate" to delegate())) {
            assertTrue("$name 选框画笔走工厂", src.contains("EditChromePainter.selectPaint"))
            assertTrue("$name 参考线画笔走工厂", src.contains("EditChromePainter.guidePaint"))
            assertFalse("$name 不得再自建 DashPathEffect", src.contains("DashPathEffect"))
        }
    }

    /** 大丸选中走圆环、其余走圆角矩形，由共享件按 pill 分派。 */
    @Test
    fun pillSelectionIsRingNotRoundedRect() {
        val b = body(chrome(), "drawSelection")
        assertTrue("大丸画圆环", b.contains("canvas.drawCircle"))
        assertTrue("其余画圆角矩形", b.contains("canvas.drawRoundRect"))
        assertTrue("按 pill 分派", b.contains("if (pill)"))
        assertTrue("独立页传 pill 判据", editView().contains("selection == Selection.PILL"))
        assertTrue("预览传 pill 判据", delegate().contains("selection == Kind.PILL"))
    }

    /** 八点手柄：中心点不画，共八个。 */
    @Test
    fun clockHandlesSkipCenterGivingEight() {
        val b = body(chrome(), "drawClockHandles")
        assertTrue("跳过中心", b.contains("if (fx == rect.centerX() && fy == rect.centerY()) continue"))
        assertTrue("三横三纵", b.contains("floatArrayOf(rect.left, rect.centerX(), rect.right)"))
        assertEquals("3×3 去掉中心 = 8", 8, 3 * 3 - 1)
    }

    /**
     * ★画布不画删除徽标：小组件移除只走长按菜单。
     *
     * 需求原话把小组件交互定为「长按」（spec168 §长按改尺寸 / 拖动），
     * 而徽标圆心在 widget 矩形内侧、点击会被 pick() 判成 widget，
     * 画出来就是一个永远点不动的假按钮。
     */
    @Test
    fun canvasDrawsNoDeadRemoveBadge() {
        assertFalse("共享件不得再有徽标实现", chrome().contains("drawRemoveBadge"))
        assertFalse("预览画布不得画徽标", delegate().contains("drawRemoveBadge"))
        assertFalse("徽标常量一并删除", chrome().contains("BADGE_"))
        // 移除能力必须仍在，且只走长按菜单
        val panel = File("src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodInlineEditPanel.kt").readText()
        assertTrue("预览侧长按菜单能移除", panel.contains("layout.removeWidget(typeId)"))
        val ctrl = File("src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodEditController.kt").readText()
        assertTrue("独立页侧栏能移除", ctrl.contains("settings.removeWidget("))
    }

    /** 阳性对照：旧徽标画在 widget 内侧 ⇒ 点它必然被 pick() 判成 widget，删不掉。 */
    @Test
    fun positiveControlBadgeCenterFallsInsideWidgetRect() {
        val badgeRadiusDp = 9f
        val offsetRatio = 0.4f
        val left = 100f
        val top = 200f
        val right = 400f
        val bottom = 300f
        val cx = left + badgeRadiusDp * offsetRatio
        val cy = top + badgeRadiusDp * offsetRatio
        assertTrue("徽标心 ( $cx , $cy ) 落在 widget 矩形内", cx > left && cx < right && cy > top && cy < bottom)
    }

    /** 零尺寸与零密度必须早退，不得画出 NaN 几何。 */
    @Test
    fun degenerateInputIsGuarded() {
        for (fn in listOf("drawSelection", "drawClockHandles")) {
            val b = body(chrome(), fn)
            assertTrue("$fn 守密度", b.contains("density <= 0f"))
            assertTrue("$fn 守宽", b.contains("rect.width() <= 0f"))
            assertTrue("$fn 守高", b.contains("rect.height() <= 0f"))
        }
    }

    /** 共享件不得反向依赖两套画布，否则又是循环耦合。 */
    @Test
    fun sharedPainterDoesNotDependOnCanvases() {
        val c = chrome()
        assertFalse("不得引用独立页", c.contains("AodEditView"))
        assertFalse("不得引用预览委托", c.contains("AodPreviewEditDelegate"))
        assertFalse("不得引用配置件", c.contains("LockLayoutSettings"))
    }
}
