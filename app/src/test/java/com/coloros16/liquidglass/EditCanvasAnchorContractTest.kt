package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.edit.ClockLayout
import com.coloros16.liquidglass.engine.edit.EditAnchorOverlay
import com.coloros16.liquidglass.engine.edit.EditSurface
import com.coloros16.liquidglass.engine.edit.LockLayoutSettings
import com.coloros16.liquidglass.ui.aod.AodSceneCatalog
import com.coloros16.liquidglass.ui.aod.AodStyleCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 独立编辑页时钟必须落在样式锚点上的契约。
 *
 * 配置值仍是缺省值＝这一轴没编辑过，绘制中心取样式锚点；编辑过才取用户值。
 * 绘制、拖动起点、手柄反算、边界钳位四处必须共用这套换算。
 */
class EditCanvasAnchorContractTest {

    private val w = 734.5f
    private val h = 1591.5f

    private fun read(name: String): String =
        File("src/main/kotlin/com/coloros16/liquidglass/ui/aod/$name.kt").readText()

    private fun editView(): String = read("AodEditView")
    private fun delegate(): String = read("AodPreviewEditDelegate")
    private fun controller(): String = read("AodEditController")

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

    private fun defaults(surface: EditSurface) = LockLayoutSettings(surface)

    private fun anchorOf(styleId: String, state: SceneState) =
        AodSceneCatalog.forStyle(styleId)?.clockAnchors?.get(state)

    private fun visualX(config: Float, anchor: Float) =
        ClockLayout.configToVisual(config, anchor, EditAnchorOverlay.defaultX())

    private fun visualY(config: Float, anchor: Float, surface: EditSurface) =
        ClockLayout.configToVisual(config, anchor, EditAnchorOverlay.defaultYOf(surface))

    /** 未编辑时两族锁屏锚点与引擎缺省位不同 ⇒ 接线产生真实可见差异。 */
    @Test
    fun uneditedLockClockLandsOnStyleAnchorNotEditDefault() {
        val s = defaults(EditSurface.LOCK)
        for (id in listOf(AodStyleCatalog.ID_SAIL, AodStyleCatalog.ID_HANDPAINT)) {
            val a = anchorOf(id, SceneState.LOCK)
            assertNotNull("$id 必须有锁屏锚点", a)
            val vx = visualX(s.clockX, a!!.centerX)
            val vy = visualY(s.clockY, a.centerY, EditSurface.LOCK)
            assertEquals("$id 锁屏 x 落锚点", a.centerX, vx, 1e-6f)
            assertEquals("$id 锁屏 y 落锚点", a.centerY, vy, 1e-6f)
            assertTrue("$id 锚点与缺省位必须不同", kotlin.math.abs(a.centerX - s.clockX) > 0.01f)
        }
    }

    /** ★手绘息屏差最大：锚点 0.720 对缺省 0.45，纵向 429.7px。 */
    @Test
    fun handPaintAodGapIsQuantified() {
        val s = defaults(EditSurface.AOD)
        val a = anchorOf(AodStyleCatalog.ID_HANDPAINT, SceneState.AOD)!!
        val previewY = visualY(s.clockY, a.centerY, EditSurface.AOD) * h
        val oldY = s.clockY * h
        assertEquals("手绘息屏锚点 0.720", 0.720f, a.centerY, 1e-6f)
        assertEquals("引擎缺省 0.45", 0.45f, s.clockY, 1e-6f)
        assertEquals("旧写法纵向偏 -429.7px", -429.7f, oldY - previewY, 0.2f)
    }

    /** 两族锁屏的横向与纵向偏差都量化，防「只修一轴」。 */
    @Test
    fun lockGapIsQuantifiedOnBothAxes() {
        val s = defaults(EditSurface.LOCK)
        val a = anchorOf(AodStyleCatalog.ID_SAIL, SceneState.LOCK)!!
        val dx = s.clockX * w - visualX(s.clockX, a.centerX) * w
        val dy = s.clockY * h - visualY(s.clockY, a.centerY, EditSurface.LOCK) * h
        assertEquals("锁屏横向偏 +102.8px", 102.8f, dx, 0.2f)
        assertEquals("锁屏纵向偏 +198.9px", 198.9f, dy, 0.2f)
    }

    /** 启航息屏只差纵向 23.9px：小差异也是差异，不能因为小就不接。 */
    @Test
    fun sailAodGapIsSmallButReal() {
        val s = defaults(EditSurface.AOD)
        val a = anchorOf(AodStyleCatalog.ID_SAIL, SceneState.AOD)!!
        val dx = s.clockX * w - visualX(s.clockX, a.centerX) * w
        val dy = s.clockY * h - visualY(s.clockY, a.centerY, EditSurface.AOD) * h
        assertEquals("启航息屏横向无差", 0f, dx, 1e-3f)
        assertEquals("启航息屏纵向偏 +23.9px", 23.9f, dy, 0.2f)
    }

    /** 编辑过之后两侧一致：换算不得覆盖用户拖出的位置。 */
    @Test
    fun editedAxisKeepsUserValueOnBothCanvases() {
        val a = anchorOf(AodStyleCatalog.ID_HANDPAINT, SceneState.AOD)!!
        for ((cx, cy) in listOf(0.30f to 0.60f, 0.72f to 0.22f)) {
            assertEquals("编辑过的 x 直取", cx, visualX(cx, a.centerX), 1e-6f)
            assertEquals("编辑过的 y 直取", cy, visualY(cy, a.centerY, EditSurface.AOD), 1e-6f)
        }
    }

    /** 单轴编辑：另一轴仍落锚点，不得被一起带走。 */
    @Test
    fun editingOneAxisLeavesTheOtherOnAnchor() {
        val s = defaults(EditSurface.LOCK)
        val a = anchorOf(AodStyleCatalog.ID_SAIL, SceneState.LOCK)!!
        s.clockX = 0.8f
        assertEquals("x 取用户值", 0.8f, visualX(s.clockX, a.centerX), 1e-6f)
        assertEquals("y 仍落锚点", a.centerY, visualY(s.clockY, a.centerY, EditSurface.LOCK), 1e-6f)
    }

    /** 无世界契约的样式没有锚点，回落编辑缺省位（不是回落到别的样式）。 */
    @Test
    fun nonSceneStyleFallsBackToEditDefault() {
        assertNull(AodSceneCatalog.forStyle(AodStyleCatalog.ID_TEXT))
        assertNull(AodSceneCatalog.forStyle(AodStyleCatalog.ID_PORTRAIT))
        val s = defaults(EditSurface.LOCK)
        val ax = EditAnchorOverlay.defaultX()
        val ay = EditAnchorOverlay.defaultYOf(EditSurface.LOCK)
        assertEquals("无契约 x 回缺省", s.clockX, visualX(s.clockX, ax), 1e-6f)
        assertEquals("无契约 y 回缺省", s.clockY, visualY(s.clockY, ay, EditSurface.LOCK), 1e-6f)
    }

    /** ★接线：独立页五处坐标读写都必须走换算，不得再直接拿配置值当像素。 */
    @Test
    fun editCanvasRoutesAllCoordinateAccessThroughConversion() {
        val v = editView()
        assertTrue("必须有锚点注入口", v.contains("var anchor: Pair<Float, Float>?"))
        assertTrue("必须有中心换算件", v.contains("private fun clockCenterPx"))
        assertFalse("绘制不得直接用配置值", v.contains("s.clockX * w"))
        assertFalse("离屏测量不得直接用配置值", body(v, "drawClockOffscreen").contains("s.clockX * w"))
        assertFalse("拖动起点不得直接取配置值", v.contains("startX = s.clockX"))
        assertTrue("拖动写回走 visualToConfig", body(v, "dragTo").contains("ClockLayout.visualToConfig"))
        assertTrue("手柄反算走 visualToConfig", body(v, "resizeClock").contains("ClockLayout.visualToConfig"))
        assertTrue("边界钳位走 visualToConfig", body(v, "clampClock").contains("ClockLayout.visualToConfig"))
    }

    /** 中心换算件本身必须逐轴走 configToVisual，缺省值取引擎件不写魔数。 */
    @Test
    fun centerHelperUsesEngineDefaultsPerAxis() {
        val b = body(editView(), "clockCenterPx")
        assertTrue("x 走换算", b.contains("ClockLayout.configToVisual(s.clockX"))
        assertTrue("y 走换算", b.contains("ClockLayout.configToVisual(s.clockY"))
        assertTrue("x 缺省取引擎", b.contains("EditAnchorOverlay.defaultX()"))
        assertTrue("y 缺省按面取", b.contains("EditAnchorOverlay.defaultYOf(surface)"))
        assertFalse("不得写死 0.5", b.contains("0.5f"))
        assertFalse("不得写死 0.28", b.contains("0.28f"))
        assertFalse("不得写死 0.45", b.contains("0.45f"))
    }

    /** 控制器必须注入锚点，且与预览取同一份样式契约。 */
    @Test
    fun controllerInjectsAnchorFromSameCatalogAsPreview() {
        val c = controller()
        assertTrue("控制器有样式来源入参", c.contains("styleIdOf: () -> String"))
        assertTrue("bind 注入锚点", c.contains("view.anchor = anchorOfSurface()"))
        assertTrue("refresh 也注入（换样式后要跟）", c.contains("canvas?.anchor = anchorOfSurface()"))
        val b = body(c, "anchorOfSurface")
        // 钉「目录 + 来源」两件事，不钉拼接写法（中间插诊断变量是合理改写）
        assertTrue("有样式来源", b.contains("styleIdOf()"))
        assertTrue("走同一份目录", b.contains("AodSceneCatalog.forStyle("))
        assertTrue("按面取态", b.contains("if (surface == EditSurface.AOD) SceneState.AOD else SceneState.LOCK"))
        assertTrue("取不到契约回 null", b.contains("return null"))
    }

    /**
     * 设置页必须真的把样式 id 传进去，否则注入链断在最后一环。
     *
     * ★来源必须与预览/hook 同口径：`effect_config` 的 `aod_style=` 是另一套存储，
     * 抽屉「应用」只写 alive 键 ⇒ 按前者取会让编辑页停在旧款锚点上（AOD-128）。
     */
    @Test
    fun settingsPagePassesStyleId() {
        val src = File("src/main/kotlin/com/coloros16/liquidglass/ui/SettingsActivity.kt").readText()
        val edit = src.substringAfter("private fun bindAodEditPage(").substringBefore("private fun readLayoutSettings")
        assertTrue("设置页传样式来源", edit.contains("styleIdOf = {"))
        assertTrue("来源走共享解析件（与预览/hook 同口径）", edit.contains("AodStyleSelection.resolve("))
    }

    /** ★阳性对照：旧写法把视觉中心当配置值写回，会把锚点偏移固化进用户配置。 */
    @Test
    fun positiveControlOldResizeBakesAnchorIntoConfig() {
        val a = anchorOf(AodStyleCatalog.ID_HANDPAINT, SceneState.AOD)!!
        val s = defaults(EditSurface.AOD)
        // 未编辑时视觉中心＝锚点；旧写法直接把它写回配置
        val visualY = visualY(s.clockY, a.centerY, EditSurface.AOD)
        val oldConfig = visualY
        val newConfig = ClockLayout.visualToConfig(
            visualY, a.centerY, EditAnchorOverlay.defaultYOf(EditSurface.AOD),
        )
        assertEquals("旧写法把 0.720 写进配置", 0.720f, oldConfig, 1e-6f)
        assertEquals("新写法保持缺省 0.45", 0.45f, newConfig, 1e-6f)
        assertTrue(
            "旧写法一旦写回，换样式后位置会被带过去",
            kotlin.math.abs(oldConfig - newConfig) > 0.2f,
        )
    }

    /** 超宽框不钳位：钳位会把中心拍回 0.5，左上锚点当场跳走。 */
    @Test
    fun oversizeBoxSkipsClampInsteadOfSnappingToCenter() {
        val v = editView()
        val b = body(v, "clampVisualCenter")
        assertTrue("装不下就原样返回", b.contains("if (half * 2f >= 1f)"))
        assertTrue("原样返回不是拍回中心", b.contains("return visual"))
        assertFalse("不得拍回 0.5", b.contains("0.5f"))
        assertTrue("跳过要留诊断", b.contains("logOversize"))
        // 手算：半宽 400px / 屏宽 734.5 + 边距 0.05 = 0.5946，两倍 >1 ⇒ 不钳
        val half = 400f / w + 0.05f
        assertTrue("该框确实装不下", half * 2f >= 1f)
    }

    /**
     * 两侧画布共用同一套换算语义，独立页不得自创第二套。
     *
     * 引擎件在预览侧用局部别名 `overlay` 引用，故按成员名判而不是按全限定名判。
     */
    @Test
    fun bothCanvasesShareTheSameConversionSemantics() {
        for ((name, src) in listOf("AodEditView" to editView(), "Delegate" to delegate())) {
            assertTrue("$name 用 configToVisual", src.contains("ClockLayout.configToVisual"))
            assertTrue("$name 用 visualToConfig", src.contains("ClockLayout.visualToConfig"))
            assertTrue("$name 缺省按面取", src.contains(".defaultYOf("))
            assertTrue("$name 缺省 x 取引擎", src.contains(".defaultX()"))
            assertTrue("$name 引用引擎件", src.contains("EditAnchorOverlay"))
            assertFalse("$name 不得写死锁屏缺省 0.28", src.contains("0.28f"))
            assertFalse("$name 不得写死息屏缺省 0.45", src.contains("0.45f"))
        }
    }
}
