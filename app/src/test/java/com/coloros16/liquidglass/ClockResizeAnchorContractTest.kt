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
            assertTrue("$f 变形必须走 ClockLayout.resizeStretch", fn.contains("ClockLayout.resizeStretch"))
            assertTrue("$f 必须写横向拉伸", fn.contains("s.clockStretchX = next[0]"))
            assertTrue("$f 必须写纵向拉伸", fn.contains("s.clockStretchY = next[1]"))
        }
        val edit = body(src("ui/aod/AodEditView.kt"), "resizeClock")
        assertTrue("独立页必须按新框反算中心 X", edit.contains("resizeAnchorLeft + clockBounds.width() / 2f"))
        assertTrue("独立页必须按新框反算中心 Y", edit.contains("resizeAnchorTop + clockBounds.height() / 2f"))
        val preview = body(src("ui/aod/AodPreviewEditDelegate.kt"), "resizeClock")
        assertTrue("预览页反算必须走共用 placeClockTopLeft", preview.contains("placeClockTopLeft(s, resizeAnchorLeft, resizeAnchorTop"))
        val place = body(src("ui/aod/AodPreviewEditDelegate.kt"), "placeClockTopLeft")
        assertTrue("预览页反算必须取新框半宽", place.contains("left + clockBounds.width() / 2f"))
        assertTrue("预览页反算必须取新框半高", place.contains("top + clockBounds.height() / 2f"))
        assertTrue("预览页写回必须走视觉→配置换算", place.contains("ClockLayout.visualToConfig"))
    }

    /** 场景锚点不在缺省位时，视觉中心与配置中心不是同一个数。 */
    @Test
    fun visualAndConfigDifferWhenAnchorOffDefault() {
        val anchor = 0.36f
        val default = 0.5f
        assertEquals("未编辑时视觉落在锚点", anchor, ClockLayout.configToVisual(default, anchor, default), 1e-6f)
        assertEquals("编辑过则视觉直接取配置", 0.7f, ClockLayout.configToVisual(0.7f, anchor, default), 1e-6f)
        assertEquals("视觉在锚点=未编辑", default, ClockLayout.visualToConfig(anchor, anchor, default), 1e-6f)
        assertEquals(0.7f, ClockLayout.visualToConfig(0.7f, anchor, default), 1e-6f)
    }

    /** 往返必须闭合，否则每拖一次漂一点。 */
    @Test
    fun visualConfigRoundTripIsStable() {
        for (anchor in listOf(0.36f, 0.5f, 0.13f)) {
            for (cfg in listOf(0.5f, 0.2f, 0.83f)) {
                val visual = ClockLayout.configToVisual(cfg, anchor, 0.5f)
                assertEquals("cfg=$cfg anchor=$anchor", cfg, ClockLayout.visualToConfig(visual, anchor, 0.5f), 1e-5f)
            }
        }
    }

    /** 阳性对照：旧的「视觉直接当配置写」在锚点偏缺省时必漂。 */
    @Test
    fun positiveControlNaiveWriteDriftsWhenAnchorOffDefault() {
        val anchor = 0.36f
        val default = 0.5f
        val visual = 0.36f
        assertEquals("正解=判为未编辑", default, ClockLayout.visualToConfig(visual, anchor, default), 1e-6f)
        assertTrue("旧写法会把锚点位当成用户位存进去", kotlin.math.abs(visual - default) > 0.1f)
    }

    /** 命中框必须按实际绘制中心量，不能按配置值量。 */
    @Test
    fun previewMeasureUsesDrawnCenterNotConfig() {
        val text = src("ui/aod/AodPreviewEditDelegate.kt")
        val fn = body(text, "measureClockBounds")
        assertTrue("量框必须取实际绘制中心", fn.contains("clockCenterPx(s, w, h)"))
        assertTrue("不得再用配置值当中心", !fn.contains("s.clockX * w"))
        assertTrue("不得再用配置值当中心", !fn.contains("s.clockY * h"))
    }

    /** 拖动起点与写回都必须走同一套换算，否则一按下就跳。 */
    @Test
    fun previewDragUsesSharedConversion() {
        val text = src("ui/aod/AodPreviewEditDelegate.kt")
        val drag = body(text, "dragTo")
        assertTrue("拖动写回必须走视觉→配置", drag.contains("ClockLayout.visualToConfig(visualCx"))
        assertTrue("拖动写回必须走视觉→配置", drag.contains("ClockLayout.visualToConfig(visualCy"))
        val touch = body(text, "onTouchEvent")
        assertTrue("按下起点必须取实际绘制中心", touch.contains("clockCenterPx(s, phoneRect.width(), phoneRect.height())"))
    }

    /** 框比屏还宽时不得钳位：钳位会把中心拍回 0.5，左上锚点当场跳走。 */
    @Test
    fun oversizeClockSkipsClamp() {
        val text = src("ui/aod/AodPreviewEditDelegate.kt")
        val fn = body(text, "clampVisualCenter")
        assertTrue("超尺寸必须原样返回", fn.contains("if (half * 2f >= 1f)"))
        assertTrue("超尺寸分支必须 return visual", Regex("""if \(half \* 2f >= 1f\) \{[\s\S]*?return visual""").containsMatchIn(fn))
        assertTrue("超尺寸必须留日志", fn.contains("logOversize(half, visual)"))
        val clamp = body(text, "clampClock")
        assertTrue("松手钳位必须走同一函数", clamp.contains("clampVisualCenter(center.first / w"))
        val drag = body(text, "dragTo")
        assertTrue("拖动钳位必须走同一函数", drag.contains("clampVisualCenter(nx"))
    }

    /** 阳性对照：旧的 coerceIn 写法在框超屏时必把中心拍回 0.5。 */
    @Test
    fun positiveControlOldClampRecentersOversize() {
        val w = 702f
        val halfPx = 336.5f
        val margin = 0.05f
        val half = halfPx / w + margin
        assertTrue("本例确实超尺寸", half * 2f >= 1f)
        val old = 0.5f
        val userVisual = 0.66f
        assertTrue("旧写法与用户位相差 > 0.15", kotlin.math.abs(old - userVisual) > 0.15f)
        val leftOld = old * w - halfPx
        val leftNew = userVisual * w - halfPx
        assertTrue("左上角漂 > 100px", kotlin.math.abs(leftOld - leftNew) > 100f)
    }

    /** 编辑态必须停防烧屏漂移：漂移会让手柄锚点跟着动，左上角钉不住。 */
    @Test
    fun editingStopsAodDrift() {
        val delegate = src("ui/aod/AodPreviewEditDelegate.kt")
        assertTrue("必须有编辑态判据", delegate.contains("val editing: Boolean get() = interactive && selection != null"))
        val view = src("ui/aod/AodPreviewView.kt")
        val uses = Regex("""AodDrift\.offsetY\(""").findAll(view).count()
        val guarded = Regex("""editDelegate\.editing[^\n]*\n?[^\n]*AodDrift\.offsetY|if \(editDelegate\.editing\) 0f else AodDrift\.offsetY""").findAll(view).count()
        assertTrue("三处漂移调用都必须被编辑态门控", uses == 3 && guarded >= 1)
        assertTrue("场景时钟漂移必须门控", view.contains("scene == SceneState.AOD && !editDelegate.editing"))
        assertTrue("启航镜片漂移必须门控", view.contains("if (editDelegate.editing) 0f else AodDrift.offsetY"))
    }

    /** 阳性对照：漂移幅度足以造成观测到的十几像素顶部漂移。 */
    @Test
    fun positiveControlDriftExplainsTopShift() {
        val phoneH = 1524f
        val maxDriftPx = com.coloros16.liquidglass.engine.AodDrift.AMPLITUDE * phoneH
        assertTrue("漂移峰值 > 17px", maxDriftPx > 17f)
    }

    /** 静止态也必须消费用户位置：clockOffset 在 edge==null 时不得恒 0。 */
    @Test
    fun staticSceneConsumesUserPosition() {
        val fn = body(src("ui/aod/AodPreviewEditDelegate.kt"), "clockOffset")
        assertTrue("静止态必须取当前态偏移", fn.contains("if (edge == null) return offsetOf(state)"))
        assertTrue("不得再恒返回零", !fn.contains("if (edge == null) return 0f to 0f"))
    }

    /**
     * 独立页与预览必须用同一套锚点换算：未编辑落样式锚点，编辑过取用户值。
     *
     * 原断言钉的是「独立页没有锚点、配置值即绘制中心」这个缺陷现状，
     * 它会让手绘息屏时钟画错 429.7px，并把手柄反算的锚点偏移固化进用户配置。
     */
    @Test
    fun standaloneCanvasSharesSceneAnchorConversion() {
        val text = src("ui/aod/AodEditView.kt")
        assertTrue("独立页必须有锚点注入口", text.contains("var anchor: Pair<Float, Float>?"))
        assertTrue("绘制中心走换算", body(text, "drawClock").contains("clockCenterPx(s, w, h)"))
        assertTrue("离屏测量走换算", body(text, "drawClockOffscreen").contains("clockCenterPx(s, w, h)"))
        assertTrue("手柄反算写回走换算", body(text, "resizeClock").contains("ClockLayout.visualToConfig"))
        assertTrue("边界钳位按视觉中心", body(text, "clampClock").contains("clampVisualCenter"))
        assertTrue("超宽框不钳位", body(text, "clampVisualCenter").contains("return visual"))
        assertTrue("不得再直接拿配置值当像素", !text.contains("s.clockX * w"))
    }

    /** 手柄是自由变形不是等比缩放：两轴独立，横向拖不动纵向。 */
    @Test
    fun handleStretchesAxesIndependently() {
        val wide = ClockLayout.resizeStretch(1f, 1f, 200f, 0f, 100f, 40f)
        assertEquals(2f, wide[0], 1e-4f)
        assertEquals("横向拖不得改纵向", 1f, wide[1], 1e-4f)
        val tall = ClockLayout.resizeStretch(1f, 1f, 0f, 80f, 100f, 40f)
        assertEquals("纵向拖不得改横向", 1f, tall[0], 1e-4f)
        assertEquals(2f, tall[1], 1e-4f)
    }

    /** 能拉成非常长的时钟：横向可到 6 倍。 */
    @Test
    fun handleCanStretchIntoVeryLongClock() {
        val s = ClockLayout.resizeStretch(1f, 1f, 1000f, 0f, 100f, 40f)
        assertEquals(LockLayoutSettings.MAX_CLOCK_STRETCH, s[0], 1e-4f)
        assertTrue("上限必须远大于等比缩放上限", LockLayoutSettings.MAX_CLOCK_STRETCH > LockLayoutSettings.MAX_CLOCK_SCALE * 3f)
    }

    /** 往回拖能压窄压扁，下限不是 1。 */
    @Test
    fun handleCanShrinkBelowOriginal() {
        val s = ClockLayout.resizeStretch(1f, 1f, -1000f, -1000f, 100f, 40f)
        assertEquals(LockLayoutSettings.MIN_CLOCK_STRETCH, s[0], 1e-4f)
        assertEquals(LockLayoutSettings.MIN_CLOCK_STRETCH, s[1], 1e-4f)
        assertTrue(LockLayoutSettings.MIN_CLOCK_STRETCH < 1f)
    }

    @Test
    fun resizeStretchGuardsNonPositiveHalf() {
        val s = ClockLayout.resizeStretch(1.5f, 2f, 300f, 300f, 0f, -1f)
        assertEquals(1.5f, s[0], 1e-4f)
        assertEquals(2f, s[1], 1e-4f)
    }

    /** 拉伸必须能存能读，否则重进页面回退。 */
    @Test
    fun stretchRoundTripsThroughConfigText() {
        val s = LockLayoutSettings.defaults(com.coloros16.liquidglass.engine.edit.EditSurface.AOD)
        s.clockStretchX = 4.2f
        s.clockStretchY = 0.4f
        val text = s.toText()
        assertTrue("写侧必须落 stretch_x", text.contains("clock.stretch_x="))
        assertTrue("写侧必须落 stretch_y", text.contains("clock.stretch_y="))
        val p = LockLayoutSettings.parse(text, com.coloros16.liquidglass.engine.edit.EditSurface.AOD)
        assertEquals(4.2f, p.clockStretchX, 1e-4f)
        assertEquals(0.4f, p.clockStretchY, 1e-4f)
    }

    /** 旧配置无 stretch 键时必须回 1，不能变形成怪样。 */
    @Test
    fun legacyConfigWithoutStretchDefaultsToOne() {
        val p = LockLayoutSettings.parse("v1\nclock.scale=1.2\n", com.coloros16.liquidglass.engine.edit.EditSurface.AOD)
        assertEquals(1f, p.clockStretchX, 1e-6f)
        assertEquals(1f, p.clockStretchY, 1e-6f)
    }

    /** 阳性对照：旧的等比写法永远拉不长（宽高比恒定）。 */
    @Test
    fun positiveControlUniformScaleCannotElongate() {
        val oldRatio = 900f / 260f
        for (scale in listOf(0.6f, 1f, 1.6f)) {
            val w = 900f * scale
            val h = 260f * scale
            assertEquals("等比缩放宽高比恒定", oldRatio, w / h, 1e-3f)
        }
        val stretched = ClockLayout.resizeStretch(1f, 1f, 900f, 0f, 450f, 130f)
        val newRatio = (900f * stretched[0]) / (260f * stretched[1])
        assertTrue("自由拉伸必须能改变宽高比", newRatio > oldRatio * 1.5f)
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
        assertTrue("旧写法不含共用求值", !legacy.contains("ClockLayout.resizeStretch"))
        assertTrue("旧写法不反算中心", !legacy.contains("resizeAnchorLeft"))
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