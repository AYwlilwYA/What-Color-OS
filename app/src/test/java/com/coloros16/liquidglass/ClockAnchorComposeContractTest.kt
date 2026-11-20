package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.ClockAnchor
import com.coloros16.liquidglass.engine.ClockAnchorCompose
import com.coloros16.liquidglass.engine.SceneState
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
 * 样式时钟锚点接 hook 契约：位置逐轴替换、缩放拉伸相乘，无样式时与旧行为逐字段等价。
 */
class ClockAnchorComposeContractTest {

    private fun stage(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/hook/aod/AodStageHook.kt",
    ).readText()

    private fun preview(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodPreviewView.kt",
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

    private fun settings(surface: EditSurface) = LockLayoutSettings.defaults(surface)

    private val sailAod = ClockAnchor(centerX = 0.50f, centerY = 0.435f, scale = 1.0f)
    private val handAod = ClockAnchor(centerX = 0.50f, centerY = 0.72f, scale = 0.86f)

    @Test
    fun defaultSettingsKeepStyleAnchor() {
        val r = ClockAnchorCompose.compose(handAod, settings(EditSurface.AOD))
        assertEquals(0.50f, r.centerX, 1e-6f)
        assertEquals("时钟必须落在花下方而不是屏幕缺省位", 0.72f, r.centerY, 1e-6f)
        assertEquals(0.86f, r.scale, 1e-6f)
    }

    /** 阳性对照：旧 hook 行为直接取配置值 ⇒ 手绘时钟被放到 0.45，与预览差 0.27 屏高。 */
    @Test
    fun positiveControlOldHookIgnoredStyleAnchor() {
        val s = settings(EditSurface.AOD)
        val old = s.clockY
        val new = ClockAnchorCompose.compose(handAod, s).centerY
        assertEquals(0.45f, old, 1e-6f)
        assertEquals(0.72f, new, 1e-6f)
        assertTrue("旧行为偏离必须大到可见", kotlin.math.abs(new - old) > 0.2f)
    }

    @Test
    fun editedAxisReplacesAnchor() {
        val s = settings(EditSurface.AOD)
        s.clockY = 0.30f
        val r = ClockAnchorCompose.compose(handAod, s)
        assertEquals("编辑过的轴取用户值", 0.30f, r.centerY, 1e-6f)
        assertEquals("没编辑的轴仍落锚点", 0.50f, r.centerX, 1e-6f)
    }

    @Test
    fun editedXOnlyKeepsStyleY() {
        val s = settings(EditSurface.AOD)
        s.clockX = 0.22f
        val r = ClockAnchorCompose.compose(sailAod, s)
        assertEquals(0.22f, r.centerX, 1e-6f)
        assertEquals(0.435f, r.centerY, 1e-6f)
    }

    @Test
    fun scaleMultiplies() {
        val s = settings(EditSurface.AOD)
        s.clockScale = 1.5f
        assertEquals(1.29f, ClockAnchorCompose.compose(handAod, s).scale, 1e-5f)
    }

    @Test
    fun stretchMultipliesOnBothAxes() {
        val s = settings(EditSurface.AOD)
        s.clockStretchX = 1.4f
        s.clockStretchY = 0.8f
        val base = handAod.copy(stretchX = 1.1f, stretchY = 1.2f)
        val r = ClockAnchorCompose.compose(base, s)
        assertEquals(1.54f, r.stretchX, 1e-5f)
        assertEquals(0.96f, r.stretchY, 1e-5f)
    }

    @Test
    fun alphaAndRotationComeFromStyleOnly() {
        val s = settings(EditSurface.AOD)
        s.clockX = 0.1f
        s.clockScale = 2f
        val base = sailAod.copy(alpha = 0.4f, rotationDeg = 12f)
        val r = ClockAnchorCompose.compose(base, s)
        assertEquals("透明度只由样式/转场决定", 0.4f, r.alpha, 1e-6f)
        assertEquals(12f, r.rotationDeg, 1e-6f)
    }

    /** 无样式契约时必须与旧 hook 行为逐字段等价（零回归）。 */
    @Test
    fun noStyleMatchesOldHookFieldByField() {
        for (surface in arrayOf(EditSurface.AOD, EditSurface.LOCK)) {
            val s = settings(surface)
            s.clockX = 0.31f
            s.clockY = 0.62f
            s.clockScale = 1.25f
            s.clockStretchX = 1.3f
            s.clockStretchY = 0.7f
            val r = ClockAnchorCompose.compose(null, s)
            assertEquals(s.clockX, r.centerX, 1e-6f)
            assertEquals(s.clockY, r.centerY, 1e-6f)
            assertEquals(s.clockScale, r.scale, 1e-6f)
            assertEquals(s.clockStretchX, r.stretchX, 1e-6f)
            assertEquals(s.clockStretchY, r.stretchY, 1e-6f)
            assertEquals(1f, r.alpha, 1e-6f)
            assertEquals(0f, r.rotationDeg, 1e-6f)
        }
    }

    @Test
    fun noStyleDefaultsMatchOldHookDefaults() {
        for (surface in arrayOf(EditSurface.AOD, EditSurface.LOCK)) {
            val s = settings(surface)
            val r = ClockAnchorCompose.compose(null, s)
            assertEquals(0.5f, r.centerX, 1e-6f)
            assertEquals(s.clockY, r.centerY, 1e-6f)
            assertEquals(1f, r.scale, 1e-6f)
        }
    }

    /** 无样式 + 全默认时基线就是缺省位，不引入额外偏移。 */
    @Test
    fun baseOfIsPlainDefault() {
        val a = ClockAnchorCompose.baseOf(EditSurface.AOD)
        assertEquals(EditAnchorOverlay.defaultX(), a.centerX, 1e-6f)
        assertEquals(LockLayoutSettings.AOD_DEFAULT_CLOCK_Y, a.centerY, 1e-6f)
        val l = ClockAnchorCompose.baseOf(EditSurface.LOCK)
        assertEquals(LockLayoutSettings.LOCK_DEFAULT_CLOCK_Y, l.centerY, 1e-6f)
    }

    /** 现状记录：有世界契约的样式，其息屏锚点与编辑缺省位不同 ⇒ 接线产生真实可见差异。 */
    @Test
    fun sceneStylesReallyDifferFromEditDefault() {
        var differing = 0
        for (style in AodStyleCatalog.styles) {
            val spec = AodSceneCatalog.forStyle(style.id) ?: continue
            val aod = spec.clockAnchors[SceneState.AOD] ?: continue
            val composed = ClockAnchorCompose.compose(aod, settings(EditSurface.AOD))
            val old = settings(EditSurface.AOD)
            if (kotlin.math.abs(composed.centerY - old.clockY) > 0.001f ||
                kotlin.math.abs(composed.scale - old.clockScale) > 0.001f
            ) differing++
        }
        assertTrue("至少要有样式真受影响，否则本轮是空接线", differing >= 2)
    }

    @Test
    fun handPaintAnchorIsBelowFlower() {
        val spec = AodSceneCatalog.forStyle(AodStyleCatalog.ID_HANDPAINT)
        assertNotNull(spec)
        val aod = spec!!.clockAnchors[SceneState.AOD]!!
        assertTrue("手绘时钟在花下方", aod.centerY > 0.6f)
        assertTrue("手绘时钟基线小于原样", aod.scale < 1f)
    }

    @Test
    fun nonSceneStyleHasNoSpec() {
        assertNull(AodSceneCatalog.forStyle(AodStyleCatalog.ID_PORTRAIT))
        assertNull(AodSceneCatalog.forStyle(AodStyleCatalog.ID_TEXT))
    }

    @Test
    fun previewAppliesStyleAnchors() {
        val src = preview()
        assertTrue(src.contains("for ((state, anchor) in spec.clockAnchors)"))
        assertTrue(src.contains("clockMachine.setAnchor(state, anchor"))
    }

    @Test
    fun hookComposesStyleWithEdit() {
        val src = body(stage(), "syncClockAnchors")
        assertTrue("hook 必须取样式契约", src.contains("worldSpec()"))
        assertTrue("必须按态取锚点", src.contains("spec?.clockAnchors?.get(state)"))
        assertTrue("必须走共用合成件", src.contains("ClockAnchorCompose.compose(base, layout)"))
    }

    /** 阳性对照：只接样式不带编辑配置会丢掉用户拖过的位置。 */
    @Test
    fun composeKeepsUserEditOverStyle() {
        val s = settings(EditSurface.AOD)
        s.clockY = 0.9f
        assertEquals(0.9f, ClockAnchorCompose.compose(handAod, s).centerY, 1e-6f)
        assertFalse(
            "直接套用样式锚点会覆盖用户编辑",
            kotlin.math.abs(handAod.centerY - 0.9f) < 0.001f,
        )
    }

    @Test
    fun anchorKeyIncludesStyleBase() {
        val src = stage()
        assertTrue("换样式必须让键失效", src.contains("val baseX: Float"))
        assertTrue(src.contains("val baseScale: Float"))
        assertTrue(src.contains("sameKey(anchorKeys[state], key)"))
    }

    /** NaN 不能进相等判断，否则每帧都判不等、逐帧重设锚点。 */
    @Test
    fun missingBaseUsesSentinelNotNaN() {
        val src = body(stage(), "syncClockAnchors")
        assertTrue(src.contains("?: NO_BASE"))
        assertFalse("禁止用 NaN 当缺失标记", src.contains("Float.NaN"))
        val cmp = body(stage(), "sameKey")
        assertTrue("缺失侧只判有无", cmp.contains("if (aHas != bHas) return false"))
        assertTrue(cmp.contains("if (!aHas) return true"))
    }

    @Test
    fun anchorSyncLogsStyleIdentity() {
        val src = body(stage(), "syncClockAnchors")
        assertTrue("日志要能分辨是否走了样式锚点", src.contains("style="))
        assertTrue(src.contains("stage-anchor-sync"))
    }

    @Test
    fun hotReloadClearsAnchorKeys() {
        assertTrue(body(stage(), "shutdownForHotReload").contains("anchorKeys.clear()"))
    }
}
