package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.edit.ClockFonts
import com.coloros16.liquidglass.engine.edit.ClockLayout
import com.coloros16.liquidglass.engine.edit.ClockStyle
import org.junit.Assert.assertEquals
import org.junit.Test

class ClockLayoutTest {

    @Test
    fun fontUsesShortEdgeAndScale() {
        assertEquals(280.8f, ClockLayout.fontPx(1080f, 2400f, 1f), 1e-2f)
        assertEquals(224.64f, ClockLayout.fontPx(2400f, 1080f, 0.8f), 1e-2f)
        assertEquals(0f, ClockLayout.fontPx(0f, 1080f, 1f), 0f)
    }

    @Test
    fun fontScaleClamped() {
        val max = ClockLayout.fontPx(1000f, 1000f, 99f)
        assertEquals(1000f * ClockLayout.BASE_FRACTION * 1.6f, max, 1e-3f)
        val min = ClockLayout.fontPx(1000f, 1000f, 0.01f)
        assertEquals(1000f * ClockLayout.BASE_FRACTION * 0.6f, min, 1e-3f)
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
