package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.edit.ClockInfo
import com.coloros16.liquidglass.engine.edit.EditAxis
import com.coloros16.liquidglass.engine.edit.EditSurface
import com.coloros16.liquidglass.engine.edit.LockLayoutSettings
import com.coloros16.liquidglass.engine.edit.LockWidget
import com.coloros16.liquidglass.engine.edit.PillPlacement
import com.coloros16.liquidglass.engine.edit.PillType
import com.coloros16.liquidglass.engine.edit.WidgetCatalog
import com.coloros16.liquidglass.engine.edit.WidgetSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LockLayoutSettingsTest {

    @Test
    fun defaultsHaveBothPillsOnLockAndNoneOnAod() {
        val lock = LockLayoutSettings.defaults(EditSurface.LOCK)
        assertEquals(2, lock.pills().size)
        assertNotNull(lock.pill(PillType.FLASHLIGHT.id))
        assertNotNull(lock.pill(PillType.CAMERA.id))

        val aod = LockLayoutSettings.defaults(EditSurface.AOD)
        assertTrue(aod.pills().isEmpty())
        assertEquals(EditAxis.FREE, EditSurface.AOD.axis)
    }

    @Test
    fun roundTripKeepsClockStyleAndPlacements() {
        val settings = LockLayoutSettings.defaults(EditSurface.LOCK)
        settings.clock = settings.clock.copy(
            styleId = "clock.bold",
            fontFamily = "serif",
            weight = 600,
            letterSpacing = 0.04f,
            colorArgb = 0x80FFFFFF.toInt(),
            glassTint = false,
            info = ClockInfo.LUNAR,
        )
        settings.clockX = 0.42f
        settings.clockY = 0.31f
        settings.clockScale = 1.2f
        settings.snapEnabled = false
        assertTrue(settings.setWidget(LockWidget(WidgetCatalog.WEATHER, x = 0.7f, y = 0.2f, size = WidgetSize.SPAN_2X2)))
        assertTrue(settings.setPill(PillPlacement(PillType.CAMERA.id, enabled = false, x = 0.75f, y = 0.9f, scale = 1.3f)))

        val parsed = LockLayoutSettings.parse(settings.toText(), EditSurface.LOCK)
        assertEquals("clock.bold", parsed.clock.styleId)
        assertEquals("serif", parsed.clock.fontFamily)
        assertEquals(600, parsed.clock.weight)
        assertEquals(ClockInfo.LUNAR, parsed.clock.info)
        assertEquals(0x80FFFFFF.toInt(), parsed.clock.colorArgb)
        assertFalse(parsed.clock.glassTint)
        assertEquals(0.42f, parsed.clockX, 1e-4f)
        assertEquals(0.31f, parsed.clockY, 1e-4f)
        assertEquals(1.2f, parsed.clockScale, 1e-4f)
        assertFalse(parsed.snapEnabled)

        val weather = parsed.widget(WidgetCatalog.WEATHER)
        assertNotNull(weather)
        assertEquals(WidgetSize.SPAN_2X2, weather!!.size)
        assertEquals(0.7f, weather.x, 1e-4f)

        val camera = parsed.pill(PillType.CAMERA.id)
        assertNotNull(camera)
        assertFalse(camera!!.enabled)
        assertEquals(1.3f, camera.scale, 1e-4f)
    }

    @Test
    fun aodMovesClockBothAxes() {
        val settings = LockLayoutSettings.defaults(EditSurface.AOD)
        val baseY = settings.clockY
        val result = settings.moveClock(0.6f, 0.8f)
        assertEquals(0.6f, settings.clockX, 1e-4f)
        assertEquals(0.8f, settings.clockY, 1e-4f)
        assertEquals(0.8f, result.y, 1e-4f)
        assertTrue("息屏纵向必须真的动了", kotlin.math.abs(settings.clockY - baseY) > 0.01f)
    }

    /** 息屏纵向位置必须能存能读：解析不得把它拍回默认值。 */
    @Test
    fun aodRoundTripKeepsVerticalPosition() {
        val settings = LockLayoutSettings.defaults(EditSurface.AOD)
        settings.moveClock(0.5f, 0.7f)
        val parsed = LockLayoutSettings.parse(settings.toText(), EditSurface.AOD)
        assertEquals("息屏 clock.y 必须原值读回", 0.7f, parsed.clockY, 1e-4f)
        assertTrue(
            "读回值必须不等于默认，否则本测无鉴别力",
            kotlin.math.abs(parsed.clockY - LockLayoutSettings.AOD_DEFAULT_CLOCK_Y) > 0.01f,
        )
    }

    /** 阳性对照：旧解析口径无条件写默认值，必定丢掉用户纵向位置。 */
    @Test
    fun positiveControlOldParseDropsAodVertical() {
        val oldWay = LockLayoutSettings.AOD_DEFAULT_CLOCK_Y
        assertEquals(LockLayoutSettings.AOD_DEFAULT_CLOCK_Y, oldWay, 1e-6f)
        assertTrue("旧口径与用户值 0.7 必须不同", kotlin.math.abs(oldWay - 0.7f) > 0.01f)
    }

    /** 越界纵向值仍要钳到 0..1，不能因为放开而失去钳位。 */
    @Test
    fun aodVerticalStillClampedToUnitRange() {
        val settings = LockLayoutSettings.defaults(EditSurface.AOD)
        settings.clockY = 3f
        assertEquals(1f, LockLayoutSettings.parse(settings.toText(), EditSurface.AOD).clockY, 1e-4f)
        settings.clockY = -2f
        assertEquals(0f, LockLayoutSettings.parse(settings.toText(), EditSurface.AOD).clockY, 1e-4f)
    }

    @Test
    fun lockMovesClockBothAxes() {
        val settings = LockLayoutSettings.defaults(EditSurface.LOCK)
        settings.moveClock(0.63f, 0.72f)
        assertEquals(0.63f, settings.clockX, 1e-4f)
        assertEquals(0.72f, settings.clockY, 1e-4f)
    }

    @Test
    fun unknownWidgetRejectedAndSizeFallsBack() {
        val settings = LockLayoutSettings.defaults(EditSurface.LOCK)
        assertFalse(settings.setWidget(LockWidget("no.such.widget")))
        assertTrue(settings.setWidget(LockWidget(WidgetCatalog.WEATHER, size = WidgetSize.SPAN_1X4)))
        assertEquals(WidgetSize.SPAN_1X4, settings.widget(WidgetCatalog.WEATHER)!!.size)
        assertTrue(settings.removeWidget(WidgetCatalog.WEATHER))
        assertFalse(settings.removeWidget(WidgetCatalog.WEATHER))
    }

    @Test
    fun unknownKeysAndBadValuesFallBack() {
        val text = "v1\nclock.x=abc\nw.unknown.x=0.3\nclock.info=solar\nclock.weight=9999\n"
        val parsed = LockLayoutSettings.parse(text, EditSurface.LOCK)
        assertEquals(0.5f, parsed.clockX, 1e-4f)
        assertEquals(ClockInfo.NONE, parsed.clock.info)
        assertEquals(900, parsed.clock.weight)
        assertTrue(parsed.widgets().isEmpty())
    }

    @Test
    fun colorFormatsAccepted() {
        val short = LockLayoutSettings.parse("clock.color=#112233", EditSurface.LOCK)
        assertEquals(0xFF112233.toInt(), short.clock.colorArgb)
        val full = LockLayoutSettings.parse("clock.color=#80112233", EditSurface.LOCK)
        assertEquals(0x80112233.toInt(), full.clock.colorArgb)
    }

    @Test
    fun pillContentSurvivesRoundTrip() {
        val settings = LockLayoutSettings.defaults(EditSurface.LOCK)
        assertTrue(settings.setPillContent(PillType.FLASHLIGHT.id, PillType.DIAL.id))
        assertTrue(settings.setPillContent(PillType.CAMERA.id, PillType.PAY.id))
        val text = settings.toText()
        assertTrue("快捷入口内容必须落盘", text.contains(PillType.FLASHLIGHT.id + ".content=" + PillType.DIAL.id))
        val parsed = LockLayoutSettings.parse(text, EditSurface.LOCK)
        assertEquals(PillType.DIAL.id, parsed.pillContent(PillType.FLASHLIGHT.id))
        assertEquals(PillType.PAY.id, parsed.pillContent(PillType.CAMERA.id))
    }

    @Test
    fun pillContentDefaultsToSlotIdWhenNeverSet() {
        val settings = LockLayoutSettings.defaults(EditSurface.LOCK)
        assertEquals(PillType.FLASHLIGHT.id, settings.pillContent(PillType.FLASHLIGHT.id))
        assertFalse("没改过就不该写出 content 行", settings.toText().contains(".content="))
        val parsed = LockLayoutSettings.parse(settings.toText(), EditSurface.LOCK)
        assertEquals(PillType.CAMERA.id, parsed.pillContent(PillType.CAMERA.id))
    }

    @Test
    fun pillContentNoneIsPersistedNotTreatedAsUnset() {
        val settings = LockLayoutSettings.defaults(EditSurface.LOCK)
        assertTrue(settings.setPillContent(PillType.CAMERA.id, PillType.NONE.id))
        val parsed = LockLayoutSettings.parse(settings.toText(), EditSurface.LOCK)
        assertEquals("选「无」也是有效内容，不能回退成槽位 id", PillType.NONE.id, parsed.pillContent(PillType.CAMERA.id))
    }

    @Test
    fun unknownPillContentRejectedAndKeepsPrevious() {
        val settings = LockLayoutSettings.defaults(EditSurface.LOCK)
        assertTrue(settings.setPillContent(PillType.CAMERA.id, PillType.SCAN.id))
        assertFalse(settings.setPillContent(PillType.CAMERA.id, "pill.teleport"))
        assertEquals(PillType.SCAN.id, settings.pillContent(PillType.CAMERA.id))
        val parsed = LockLayoutSettings.parse(
            settings.toText() + PillType.CAMERA.id + ".content=pill.teleport\n",
            EditSurface.LOCK,
        )
        assertEquals(PillType.SCAN.id, parsed.pillContent(PillType.CAMERA.id))
    }

    @Test
    fun pillContentAndPlacementBothSurviveTogether() {
        val settings = LockLayoutSettings.defaults(EditSurface.LOCK)
        assertTrue(settings.setPillContent(PillType.FLASHLIGHT.id, PillType.RECORDER.id))
        assertTrue(settings.setPill(PillPlacement(PillType.FLASHLIGHT.id, enabled = false, x = 0.33f, y = 0.9f, scale = 1.4f)))
        val parsed = LockLayoutSettings.parse(settings.toText(), EditSurface.LOCK)
        assertEquals(PillType.RECORDER.id, parsed.pillContent(PillType.FLASHLIGHT.id))
        val pill = parsed.pill(PillType.FLASHLIGHT.id)
        assertNotNull(pill)
        assertFalse(pill!!.enabled)
        assertEquals(0.33f, pill.x, 1e-4f)
        assertEquals(1.4f, pill.scale, 1e-4f)
    }

    @Test
    fun repeatedRoundTripIsStable() {
        val settings = LockLayoutSettings.defaults(EditSurface.LOCK)
        settings.setPillContent(PillType.FLASHLIGHT.id, PillType.CALCULATOR.id)
        settings.setPillContent(PillType.CAMERA.id, PillType.NONE.id)
        var text = settings.toText()
        repeat(5) {
            val next = LockLayoutSettings.parse(text, EditSurface.LOCK).toText()
            assertEquals("多轮落盘-读回不得漂移", text, next)
            text = next
        }
    }

    /** 阳性对照：检测手段本身能发现 content 缺失，避免断言写成永真。 */
    @Test
    fun detectorWouldCatchMissingContentLine() {
        val settings = LockLayoutSettings.defaults(EditSurface.LOCK)
        settings.setPillContent(PillType.FLASHLIGHT.id, PillType.DIAL.id)
        val stripped = settings.toText().lineSequence()
            .filterNot { it.contains(".content=") }
            .joinToString("\n")
        val parsed = LockLayoutSettings.parse(stripped, EditSurface.LOCK)
        assertEquals(
            "去掉 content 行后必须回退成槽位 id（证明该断言真的在测落盘）",
            PillType.FLASHLIGHT.id,
            parsed.pillContent(PillType.FLASHLIGHT.id),
        )
    }
}
