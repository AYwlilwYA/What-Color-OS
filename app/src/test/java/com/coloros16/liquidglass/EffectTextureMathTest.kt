package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.effect.EffectCatalog
import com.coloros16.liquidglass.engine.effect.EffectTextureCatalog
import com.coloros16.liquidglass.engine.effect.EffectTextureMath
import com.coloros16.liquidglass.engine.effect.TextureWrap
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectTextureMathTest {

    private fun spec(id: String) = EffectTextureCatalog.find(id)!!

    private fun texel(nx: Int, ny: Int, height: Int): Int =
        (0xFF shl 24) or ((nx + 128).coerceIn(0, 255) shl 16) or
            ((ny + 128).coerceIn(0, 255) shl 8) or height.coerceIn(0, 255)

    @Test
    fun repeatWrapsBothDirections() {
        assertEquals(0, EffectTextureMath.sampleCoord(0f, 14, TextureWrap.REPEAT))
        assertEquals(13, EffectTextureMath.sampleCoord(13f, 14, TextureWrap.REPEAT))
        assertEquals(0, EffectTextureMath.sampleCoord(14f, 14, TextureWrap.REPEAT))
        assertEquals(1, EffectTextureMath.sampleCoord(15f, 14, TextureWrap.REPEAT))
        assertEquals(13, EffectTextureMath.sampleCoord(-1f, 14, TextureWrap.REPEAT))
    }

    @Test
    fun cropClampsAndStretchScales() {
        assertEquals(13, EffectTextureMath.sampleCoord(999f, 14, TextureWrap.CROP_OR_STRETCH))
        assertEquals(0, EffectTextureMath.sampleCoord(-5f, 14, TextureWrap.CROP_OR_STRETCH))
        assertEquals(13, EffectTextureMath.sampleCoord(14f, 14, TextureWrap.STRETCH))
        assertEquals(0, EffectTextureMath.sampleCoord(0f, 14, TextureWrap.STRETCH))
        assertEquals(0, EffectTextureMath.sampleCoord(5f, 1, TextureWrap.REPEAT))
    }

    @Test
    fun normalDecodingMatchesEncoding() {
        val t = texel(64, -64, 200)
        assertEquals(64f / 127f, EffectTextureMath.normalX(t), 0.0001f)
        assertEquals(-64f / 127f, EffectTextureMath.normalY(t), 0.0001f)
        assertEquals(200f / 255f, EffectTextureMath.height(t), 0.0001f)
        val nz = EffectTextureMath.normalZ(EffectTextureMath.normalX(t), EffectTextureMath.normalY(t))
        assertTrue(nz > 0f && nz <= 1f)
    }

    @Test
    fun normalZClampsOutOfRange() {
        assertEquals(0f, EffectTextureMath.normalZ(1f, 1f), 0.0001f)
        assertEquals(1f, EffectTextureMath.normalZ(0f, 0f), 0.0001f)
    }

    @Test
    fun colorParsingHandlesAlphaAndShortForm() {
        assertEquals(0x05131F4E, EffectTextureMath.parseColor("#05131F4E"))
        assertEquals(0xFF131F4E.toInt(), EffectTextureMath.parseColor("#131F4E"))
        assertEquals(0, EffectTextureMath.parseColor("nope"))
    }

    @Test
    fun flatTexelKeepsGlassTintOnly() {
        val s = spec(EffectCatalog.LOCK_WP_GRID)
        val flat = texel(0, 0, 255)
        val out = EffectTextureMath.shadePixel(flat, s, false)
        val glass = EffectTextureMath.parseColor(s.glassColor)
        assertEquals(glass shr 16 and 0xFF, out shr 16 and 0xFF)
        assertEquals(glass shr 8 and 0xFF, out shr 8 and 0xFF)
        assertEquals(glass and 0xFF, out and 0xFF)
        assertEquals(glass ushr 24 and 0xFF, out ushr 24 and 0xFF)
    }

    @Test
    fun slopedTexelChangesBrightness() {
        val s = spec(EffectCatalog.LOCK_WP_STRIPES_FINE)
        val up = EffectTextureMath.shadePixel(texel(80, 0, 255), s, false)
        val down = EffectTextureMath.shadePixel(texel(-80, 0, 255), s, false)
        assertTrue("slopes must differ", up != down)
    }

    @Test
    fun blurVariantUsesBlurParameters() {
        val s = spec(EffectCatalog.LOCK_WP_STRIPES_FINE)
        val t = texel(60, 20, 220)
        val plain = EffectTextureMath.shadePixel(t, s, false)
        val blurred = EffectTextureMath.shadePixel(t, s, true)
        assertTrue(plain != blurred)
    }

    @Test
    fun refractStrengthScalesWithShortSide() {
        val s = spec(EffectCatalog.LOCK_WP_STRIPES_FINE)
        assertEquals(3.6f * 0.18f * 1080f * 0.02f, EffectTextureMath.refractStrength(s, false, 1080f), 0.001f)
        assertEquals(5f * 0.36f * 1080f * 0.02f, EffectTextureMath.refractStrength(s, true, 1080f), 0.001f)
        assertEquals(0f, EffectTextureMath.refractStrength(s, false, 0f), 0.001f)
        val grid = spec(EffectCatalog.LOCK_WP_GRID)
        assertEquals(0.42f * 0.06f * 540f * 0.02f, EffectTextureMath.refractStrength(grid, false, 540f), 0.001f)
        assertTrue(
            EffectTextureMath.refractStrength(s, false, 1080f) >
                EffectTextureMath.refractStrength(s, false, 540f),
        )
    }

    @Test
    fun regionFallsBackToWholeImage() {
        val fine = spec(EffectCatalog.LOCK_WP_STRIPES_FINE)
        assertArrayEquals(intArrayOf(0, 0, 14, 1), EffectTextureMath.region(fine, 14, 1))
        val grid = spec(EffectCatalog.LOCK_WP_GRID)
        assertArrayEquals(intArrayOf(0, 0, 482, 482), EffectTextureMath.region(grid, 482, 482))
        val ripple = spec(EffectCatalog.LOCK_WP_RIPPLE)
        assertArrayEquals(intArrayOf(285, 0, 270, 600), EffectTextureMath.region(ripple, 840, 600))
    }

    @Test
    fun tiltShiftClearBandSitsAtBottom() {
        val band = EffectTextureMath.tiltShiftBand(840f)
        assertEquals(0.775f, band[0], 0.0001f)
        assertEquals(1f, band[1], 0.0001f)
    }

    @Test
    fun tiltShiftWeightRampsOutsideBand() {
        assertEquals(0f, EffectTextureMath.tiltShiftWeight(0.9f, 840f), 0.0001f)
        assertEquals(0f, EffectTextureMath.tiltShiftWeight(1f, 840f), 0.0001f)
        assertTrue(EffectTextureMath.tiltShiftWeight(0.5f, 840f) > 0f)
        assertEquals(1f, EffectTextureMath.tiltShiftWeight(0f, 840f), 0.0001f)
        assertEquals(0f, EffectTextureMath.tiltShiftWeight(0.5f, 0f), 0.0001f)
    }

    @Test
    fun tiltShiftWeightIsMonotonicAwayFromBand() {
        var last = -1f
        var y = 0.75f
        while (y >= 0f) {
            val w = EffectTextureMath.tiltShiftWeight(y, 840f)
            assertTrue("weight must not decrease going up", w >= last - 0.0001f)
            last = w
            y -= 0.05f
        }
    }
}
