package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.edit.ClockFonts
import com.coloros16.liquidglass.engine.edit.ClockLayout
import com.coloros16.liquidglass.engine.edit.ClockStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockLayoutTest {

    @Test
    fun fontUsesShortEdgeAndScale() {
        assertEquals(280.8f, ClockLayout.fontPx(1080f, 2400f, 1f), 1e-2f)
        assertEquals(224.64f, ClockLayout.fontPx(2400f, 1080f, 0.8f), 1e-2f)
        assertEquals(0f, ClockLayout.fontPx(0f, 1080f, 1f), 0f)
    }

    /** 动画真值不受用户可拖范围钳位：pose.scale 0.46 与入场 0.85 相乘后仍按比例出字号。 */
    @Test
    fun fontScaleIsNotClampedByUserRange() {
        assertEquals(
            1000f * ClockLayout.BASE_FRACTION * 0.46f,
            ClockLayout.fontPx(1000f, 1000f, 0.46f),
            1e-3f,
        )
        assertEquals(
            1000f * ClockLayout.BASE_FRACTION * 0.527f,
            ClockLayout.fontPx(1000f, 1000f, 0.527f),
            1e-3f,
        )
        assertEquals(0f, ClockLayout.fontPx(1000f, 1000f, 0f), 0f)
        assertEquals(0f, ClockLayout.fontPx(1000f, 1000f, -1f), 0f)
    }

    /** 用户可拖范围只在写回配置时钳。 */
    @Test
    fun userScaleClampedSeparately() {
        assertEquals(1.6f, ClockLayout.clampUserScale(99f), 1e-4f)
        assertEquals(0.6f, ClockLayout.clampUserScale(0.01f), 1e-4f)
        assertEquals(1.1f, ClockLayout.clampUserScale(1.1f), 1e-4f)
    }

    /** 阳性对照：旧口径会把 0.46 顶到 0.6，与新口径必须不同。 */
    @Test
    fun oldClampWouldDifferFromNewOnHomePose() {
        val oldWay = 1000f * ClockLayout.BASE_FRACTION * 0.6f
        val newWay = ClockLayout.fontPx(1000f, 1000f, 0.46f)
        assertTrue("新旧口径必须真的不同", kotlin.math.abs(oldWay - newWay) > 1f)
    }

    @Test
    fun fitScaleShrinksToAvailableWidth() {
        assertEquals(0.8f, ClockLayout.fitScale(textWidthPx = 1000f, currentScale = 1f, availableWidthPx = 800f), 1e-4f)
        assertEquals(0.7f, ClockLayout.fitScale(textWidthPx = 1400f, currentScale = 1.4f, availableWidthPx = 700f), 1e-4f)
    }

    @Test
    fun fitScaleRespectsBounds() {
        assertEquals(0.6f, ClockLayout.fitScale(2000f, 1f, 500f), 1e-4f)
        assertEquals(1.6f, ClockLayout.fitScale(100f, 1f, 5000f), 1e-4f)
        assertEquals(1.2f, ClockLayout.fitScale(0f, 1.2f, 500f), 1e-4f)
    }

    @Test
    fun fontTokensNormalize() {
        assertEquals(ClockFonts.SERIF, ClockFonts.normalize("serif"))
        assertEquals(ClockFonts.MONO, ClockFonts.normalize(" Monospace "))
        assertEquals(ClockFonts.SANS, ClockFonts.normalize("cursive-ish"))
        assertEquals(ClockFonts.SANS, ClockFonts.normalize(null))
        val style = ClockStyle(fontFamily = "unknown").sanitized()
        assertEquals(ClockFonts.SANS, style.fontFamily)
        assertEquals(ClockFonts.CASUAL, ClockStyle(fontFamily = "casual").sanitized().fontFamily)
    }
}
