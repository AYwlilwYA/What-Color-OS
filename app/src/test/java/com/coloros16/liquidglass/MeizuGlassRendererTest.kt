package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.effect.EffectCatalog
import com.coloros16.liquidglass.ui.aod.MeizuGlassRenderer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeizuGlassRendererTest {

    @Test
    fun kindMapsMeizuEffectsOnly() {
        assertEquals(MeizuGlassRenderer.RAINBOW, MeizuGlassRenderer.kindOf(EffectCatalog.LOCK_WP_RAINBOW))
        assertEquals(MeizuGlassRenderer.WAVE, MeizuGlassRenderer.kindOf(EffectCatalog.LOCK_WP_WAVE))
        assertEquals(MeizuGlassRenderer.FOG, MeizuGlassRenderer.kindOf(EffectCatalog.LOCK_WP_FOG))
        assertNull(MeizuGlassRenderer.kindOf(EffectCatalog.LOCK_WP_NONE))
        assertNull(MeizuGlassRenderer.kindOf(EffectCatalog.LOCK_WP_WATER))
        assertNull(MeizuGlassRenderer.kindOf(null))
    }

    @Test
    fun paramsMatchOfficialGlassState() {
        assertArrayEquals(
            floatArrayOf(0.04f, 0.076f, 0f, 0.03f, 0f),
            MeizuGlassRenderer.params(MeizuGlassRenderer.RAINBOW),
            0.0001f,
        )
        assertArrayEquals(
            floatArrayOf(0.05f, 0.072f, 0f, 0.03f, 0.007f),
            MeizuGlassRenderer.params(MeizuGlassRenderer.WAVE),
            0.0001f,
        )
        assertArrayEquals(
            floatArrayOf(0.04f, 0.076f, 0f, 0.03f, 0f),
            MeizuGlassRenderer.params(MeizuGlassRenderer.FOG),
            0.0001f,
        )
    }
}
