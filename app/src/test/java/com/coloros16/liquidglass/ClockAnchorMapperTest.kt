package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.ClockAnchor
import com.coloros16.liquidglass.engine.ClockAnchorMapper
import com.coloros16.liquidglass.engine.WidgetRectPx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClockAnchorMapperTest {

    private val clockSingle = "com.coloros.widget.smallweather.OppoWeatherSingle"
    private val clockVertical = "com.coloros.widget.smallweather.OppoWeatherVertical"

    @Test
    fun exactClockComponentsMatch() {
        assertEquals(ClockAnchorMapper.Match.EXACT, ClockAnchorMapper.match("com.coloros.alarmclock", clockSingle))
        assertEquals(ClockAnchorMapper.Match.EXACT, ClockAnchorMapper.match("com.coloros.alarmclock", clockVertical))
    }

    @Test
    fun samePackageFallsBackToPackageMatch() {
        assertEquals(
            ClockAnchorMapper.Match.PACKAGE,
            ClockAnchorMapper.match("com.coloros.alarmclock", "com.coloros.widget.launcher.LauncherWidgetOne"),
        )
        assertEquals(ClockAnchorMapper.Match.PACKAGE, ClockAnchorMapper.match("com.coloros.alarmclock", null))
    }

    @Test
    fun otherPackagesAreNotClock() {
        assertEquals(ClockAnchorMapper.Match.NONE, ClockAnchorMapper.match("com.coloros.weather", clockSingle))
        assertEquals(ClockAnchorMapper.Match.NONE, ClockAnchorMapper.match(null, clockSingle))
        assertEquals(ClockAnchorMapper.Match.NONE, ClockAnchorMapper.match("com.example.clock", "com.example.ClockWidget"))
    }

    @Test
    fun widgetRectMapsToNormalizedCenterAndScale() {
        val anchor = ClockAnchorMapper.fromWidgetRect(
            WidgetRectPx(left = 100, top = 200, right = 500, bottom = 400),
            screenW = 1000,
            screenH = 2000,
            lockClockHeightPx = 100f,
        )
        requireNotNull(anchor)
        assertEquals(0.3f, anchor.centerX, 0.0001f)
        assertEquals(0.15f, anchor.centerY, 0.0001f)
        assertEquals(2f, anchor.scale, 0.0001f)
        assertEquals(1f, anchor.alpha, 0.0001f)
    }

    @Test
    fun unknownLockClockHeightKeepsUnitScale() {
        val anchor = ClockAnchorMapper.fromWidgetRect(
            WidgetRectPx(0, 0, 300, 150),
            screenW = 1080,
            screenH = 2400,
            lockClockHeightPx = 0f,
        )
        requireNotNull(anchor)
        assertEquals(1f, anchor.scale, 0.0001f)
    }

    @Test
    fun scaleIsClampedToSafeRange() {
        val tiny = ClockAnchorMapper.fromWidgetRect(
            WidgetRectPx(0, 0, 100, 10),
            screenW = 1080,
            screenH = 2400,
            lockClockHeightPx = 400f,
        )
        assertEquals(ClockAnchorMapper.SCALE_MIN, requireNotNull(tiny).scale, 0.0001f)
        val huge = ClockAnchorMapper.fromWidgetRect(
            WidgetRectPx(0, 0, 100, 1000),
            screenW = 1080,
            screenH = 2400,
            lockClockHeightPx = 50f,
        )
        assertEquals(ClockAnchorMapper.SCALE_MAX, requireNotNull(huge).scale, 0.0001f)
    }

    @Test
    fun homeAnchorPriorityPrefersWidgetThenStatusBarThenHidden() {
        val widget = ClockAnchor(centerX = 0.4f, centerY = 0.3f, scale = 2f)
        val status = ClockAnchor(centerX = 0.9f, centerY = 0.03f, scale = 0.2f)

        assertEquals(widget, ClockAnchorMapper.pickHomeAnchor(widget, status))
        assertEquals(status, ClockAnchorMapper.pickHomeAnchor(null, status))

        val hidden = ClockAnchorMapper.pickHomeAnchor(null, null)
        assertEquals(0f, hidden.alpha, 0.0001f)
    }

    @Test
    fun targetRectSupportsStatusBarClock() {
        val anchor = ClockAnchorMapper.fromTargetRect(
            WidgetRectPx(920, 40, 1010, 90),
            screenW = 1080,
            screenH = 2400,
            lockClockHeightPx = 300f,
        )
        requireNotNull(anchor)
        assertEquals(965f / 1080f, anchor.centerX, 0.0001f)
        assertEquals(65f / 2400f, anchor.centerY, 0.0001f)
        assertEquals(50f / 300f, anchor.scale, 0.0001f)
    }

    @Test
    fun invalidInputsReturnNull() {
        assertNull(ClockAnchorMapper.fromWidgetRect(WidgetRectPx(0, 0, 100, 100), 0, 2400, 100f))
        assertNull(ClockAnchorMapper.fromWidgetRect(WidgetRectPx(0, 0, 100, 100), 1080, 0, 100f))
        assertNull(ClockAnchorMapper.fromWidgetRect(WidgetRectPx(10, 10, 10, 100), 1080, 2400, 100f))
        assertNull(ClockAnchorMapper.fromWidgetRect(WidgetRectPx(10, 10, 100, 10), 1080, 2400, 100f))
        assertNull(ClockAnchorMapper.fromWidgetRect(WidgetRectPx(1080, 0, 1200, 100), 1080, 2400, 100f))
        assertNull(ClockAnchorMapper.fromWidgetRect(WidgetRectPx(0, 2400, 100, 2500), 1080, 2400, 100f))
        // 相邻页只露一部分：中心在屏外
        assertNull(ClockAnchorMapper.fromWidgetRect(WidgetRectPx(-900, 0, 100, 200), 1080, 2400, 100f))
        assertNull(ClockAnchorMapper.fromWidgetRect(WidgetRectPx(1000, 0, 2000, 200), 1080, 2400, 100f))
    }
}
