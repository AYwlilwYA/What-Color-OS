package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.effect.EffectCatalog
import com.coloros16.liquidglass.engine.effect.EffectTextureCatalog
import com.coloros16.liquidglass.engine.effect.EffectTextureMath
import com.coloros16.liquidglass.engine.effect.EffectTextureSpec
import com.coloros16.liquidglass.engine.effect.TextureWrap
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class EffectReliefOfflineTest {

    private fun spec(id: String) = EffectTextureCatalog.find(id)!!

    private fun sourceSize(id: String): Pair<Int, Int> = when (id) {
        EffectCatalog.LOCK_WP_STRIPES_FINE -> 14 to 1
        EffectCatalog.LOCK_WP_STRIPES_COARSE -> 120 to 1
        EffectCatalog.LOCK_WP_LIGHT_STRIPES -> 40 to 1
        EffectCatalog.LOCK_WP_GRID -> 482 to 482
        EffectCatalog.LOCK_WP_WATER -> 111 to 3201
        EffectCatalog.LOCK_WP_RIPPLE -> 840 to 600
        else -> 32 to 32
    }

    private val ids = listOf(
        EffectCatalog.LOCK_WP_STRIPES_FINE,
        EffectCatalog.LOCK_WP_STRIPES_COARSE,
        EffectCatalog.LOCK_WP_LIGHT_STRIPES,
        EffectCatalog.LOCK_WP_GRID,
        EffectCatalog.LOCK_WP_WATER,
        EffectCatalog.LOCK_WP_RIPPLE,
    )

    private fun normalMap(sw: Int, sh: Int): IntArray {
        val a = IntArray(sw * sh)
        for (y in 0 until sh) {
            for (x in 0 until sw) {
                val r = x * 255 / maxOf(1, sw - 1)
                val g = y * 255 / maxOf(1, sh - 1)
                a[y * sw + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or ((x * 13 + y * 7) % 256)
            }
        }
        return a
    }

    /** 独立参照：自算采样坐标与光向投影，只复用 sampleGrid 取 texel。 */
    private fun referenceRelief(
        pixels: IntArray, sw: Int, sh: Int, spec: EffectTextureSpec,
        w: Int, h: Int, factor: Int,
    ): IntArray {
        val gw = w * factor
        val gh = h * factor
        val texel = EffectTextureMath.sampleGrid(pixels, sw, sh, spec, gw, gh)
        val rad = spec.lightAngleDeg * Math.PI / 180.0
        val lx = cos(rad).toFloat()
        val ly = sin(rad).toFloat()
        val gain = 2.2f + spec.thickness * 0.8f
        val out = IntArray(gw * gh)
        for (i in out.indices) {
            val px = texel[i]
            val nx = (((px shr 16) and 0xFF) - 128) / 127f
            val ny = (((px shr 8) and 0xFF) - 128) / 127f
            val hh = (px and 0xFF) / 255f
            val facing = nx * lx + ny * ly
            val lum = (128f + facing * gain * 127f).toInt().coerceIn(0, 255)
            val alpha = (150f + hh * 90f).toInt().coerceIn(0, 255)
            out[i] = (alpha shl 24) or (lum shl 16) or (lum shl 8) or lum
        }
        return out
    }

    @Test
    fun reliefMatchesIndependentReferenceForAllSpecs() {
        val w = 12
        val h = 9
        for (id in ids) {
            val (sw, sh) = sourceSize(id)
            val px = normalMap(sw, sh)
            val sp = spec(id)
            val got = EffectTextureMath.reliefGrid(px, sw, sh, sp, w, h)
            val want = referenceRelief(px, sw, sh, sp, w, h, EffectTextureMath.RELIEF_OVERSAMPLE)
            assertArrayEquals("relief $id", want, got)
        }
    }

    @Test
    fun outputSizeIsTargetTimesOversample() {
        val sp = spec(EffectCatalog.LOCK_WP_GRID)
        val (sw, sh) = sourceSize(EffectCatalog.LOCK_WP_GRID)
        val got = EffectTextureMath.reliefGrid(normalMap(sw, sh), sw, sh, sp, 7, 5)
        assertEquals(7 * 4 * 5 * 4, got.size)
    }

    @Test
    fun customOversampleIsHonored() {
        val sp = spec(EffectCatalog.LOCK_WP_RIPPLE)
        val (sw, sh) = sourceSize(EffectCatalog.LOCK_WP_RIPPLE)
        val px = normalMap(sw, sh)
        val got = EffectTextureMath.reliefGrid(px, sw, sh, sp, 6, 4, 2)
        assertEquals(6 * 2 * 4 * 2, got.size)
        assertArrayEquals(referenceRelief(px, sw, sh, sp, 6, 4, 2), got)
    }

    @Test
    fun oversampleBelowOneIsTreatedAsOne() {
        val sp = spec(EffectCatalog.LOCK_WP_GRID)
        val (sw, sh) = sourceSize(EffectCatalog.LOCK_WP_GRID)
        val px = normalMap(sw, sh)
        val zero = EffectTextureMath.reliefGrid(px, sw, sh, sp, 5, 3, 0)
        val one = EffectTextureMath.reliefGrid(px, sw, sh, sp, 5, 3, 1)
        assertEquals(15, zero.size)
        assertArrayEquals(one, zero)
    }

    @Test
    fun outputIsGrayscaleWithIndependentAlpha() {
        val sp = spec(EffectCatalog.LOCK_WP_WATER)
        val (sw, sh) = sourceSize(EffectCatalog.LOCK_WP_WATER)
        val got = EffectTextureMath.reliefGrid(normalMap(sw, sh), sw, sh, sp, 8, 8)
        for (px in got) {
            val r = px shr 16 and 0xFF
            val g = px shr 8 and 0xFF
            val b = px and 0xFF
            assertEquals(r, g)
            assertEquals(g, b)
            val a = px ushr 24
            assertTrue("alpha 在 150..240", a in 150..240)
        }
    }

    @Test
    fun flatNormalAtMidpointGivesLum128() {
        val sp = spec(EffectCatalog.LOCK_WP_GRID).copy(
            region = intArrayOf(0, 0, 0, 0), layoutScale = 1f,
            wrapX = TextureWrap.STRETCH, wrapY = TextureWrap.STRETCH,
        )
        val px = IntArray(4) { (0xFF shl 24) or (128 shl 16) or (128 shl 8) or 0 }
        val got = EffectTextureMath.reliefGrid(px, 2, 2, sp, 3, 3, 1)
        for (p in got) {
            assertEquals(128, p and 0xFF)
            assertEquals(150, p ushr 24)
        }
    }

    @Test
    fun lightAngleRotatesWhichNormalIsBrightest() {
        val base = spec(EffectCatalog.LOCK_WP_GRID).copy(
            region = intArrayOf(0, 0, 0, 0), layoutScale = 1f,
            wrapX = TextureWrap.CROP_OR_STRETCH, wrapY = TextureWrap.CROP_OR_STRETCH,
            thickness = 0f,
        )
        val px = intArrayOf(
            (0xFF shl 24) or (255 shl 16) or (128 shl 8) or 0,
            (0xFF shl 24) or (128 shl 16) or (255 shl 8) or 0,
        )
        val atZero = EffectTextureMath.reliefGrid(px, 2, 1, base.copy(lightAngleDeg = 0f), 2, 1, 1)
        val atNinety = EffectTextureMath.reliefGrid(px, 2, 1, base.copy(lightAngleDeg = 90f), 2, 1, 1)
        assertTrue("0° 时 +X 法线更亮", (atZero[0] and 0xFF) > (atZero[1] and 0xFF))
        assertTrue("90° 时 +Y 法线更亮", (atNinety[1] and 0xFF) > (atNinety[0] and 0xFF))
    }

    @Test
    fun stretchWrapNeverReachesLastTexelColumn() {
        val base = spec(EffectCatalog.LOCK_WP_GRID).copy(
            region = intArrayOf(0, 0, 0, 0), layoutScale = 1f,
            wrapX = TextureWrap.STRETCH, wrapY = TextureWrap.STRETCH,
            thickness = 0f, lightAngleDeg = 0f,
        )
        val px = intArrayOf(
            (0xFF shl 24) or (255 shl 16) or (128 shl 8) or 0,
            (0xFF shl 24) or (0 shl 16) or (128 shl 8) or 0,
        )
        val got = EffectTextureMath.reliefGrid(px, 2, 1, base, 2, 1, 1)
        assertEquals("STRETCH 下 x=w-1 仍落在第 0 列", got[0], got[1])
    }

    @Test
    fun thicknessRaisesGainSoContrastGrows() {
        val base = spec(EffectCatalog.LOCK_WP_GRID).copy(
            region = intArrayOf(0, 0, 0, 0), layoutScale = 1f,
            wrapX = TextureWrap.CROP_OR_STRETCH, wrapY = TextureWrap.CROP_OR_STRETCH,
            lightAngleDeg = 0f,
        )
        val px = intArrayOf((0xFF shl 24) or (140 shl 16) or (128 shl 8) or 0)
        val thin = EffectTextureMath.reliefGrid(px, 1, 1, base.copy(thickness = 0.2f), 1, 1, 1)
        val thick = EffectTextureMath.reliefGrid(px, 1, 1, base.copy(thickness = 2f), 1, 1, 1)
        assertTrue((thick[0] and 0xFF) > (thin[0] and 0xFF))
        assertTrue("低 gain 不该撞顶", (thin[0] and 0xFF) < 255)
    }

    @Test
    fun strongNormalSaturatesEvenAtLowThickness() {
        val base = spec(EffectCatalog.LOCK_WP_GRID).copy(
            region = intArrayOf(0, 0, 0, 0), layoutScale = 1f,
            wrapX = TextureWrap.CROP_OR_STRETCH, wrapY = TextureWrap.CROP_OR_STRETCH,
            lightAngleDeg = 0f, thickness = 0.2f,
        )
        val px = intArrayOf((0xFF shl 24) or (200 shl 16) or (128 shl 8) or 0)
        val got = EffectTextureMath.reliefGrid(px, 1, 1, base, 1, 1, 1)
        assertEquals("gain 2.36 × nx 0.567 已超 127，直接顶到 255", 255, got[0] and 0xFF)
    }

    @Test
    fun luminanceClampsAtBothEnds() {
        val base = spec(EffectCatalog.LOCK_WP_GRID).copy(
            region = intArrayOf(0, 0, 0, 0), layoutScale = 1f,
            wrapX = TextureWrap.CROP_OR_STRETCH, wrapY = TextureWrap.CROP_OR_STRETCH,
            lightAngleDeg = 0f, thickness = 20f,
        )
        val px = intArrayOf(
            (0xFF shl 24) or (255 shl 16) or (128 shl 8) or 0,
            (0xFF shl 24) or (0 shl 16) or (128 shl 8) or 255,
        )
        val got = EffectTextureMath.reliefGrid(px, 2, 1, base, 2, 1, 1)
        assertEquals(255, got[0] and 0xFF)
        assertEquals(0, got[1] and 0xFF)
        assertEquals(240, got[1] ushr 24)
    }

    @Test
    fun nonPositiveTargetReturnsEmpty() {
        val sp = spec(EffectCatalog.LOCK_WP_GRID)
        assertEquals(0, EffectTextureMath.reliefGrid(IntArray(4), 2, 2, sp, 0, 5).size)
        assertEquals(0, EffectTextureMath.reliefGrid(IntArray(4), 2, 2, sp, 5, -1).size)
    }

    @Test
    fun nonPositiveSourceGoesThroughShadingNotEarlyReturn() {
        val sp = spec(EffectCatalog.LOCK_WP_GRID).copy(
            region = intArrayOf(0, 0, 0, 0), layoutScale = 1f,
            wrapX = TextureWrap.CROP_OR_STRETCH, wrapY = TextureWrap.CROP_OR_STRETCH,
            lightAngleDeg = 0f,
        )
        val got = EffectTextureMath.reliefGrid(IntArray(0), 0, 0, sp, 2, 2, 1)
        assertEquals(4, got.size)
        for (p in got) {
            assertEquals("零 texel 法线 -1.008 被当有效值着色 ⇒ 亮度钳到 0", 0, p and 0xFF)
            assertEquals(150, p ushr 24)
        }
    }

    @Test
    fun reliefHasNoBlurredParameterAndIgnoresBlurThickness() {
        val sp = spec(EffectCatalog.LOCK_WP_WATER)
        val (sw, sh) = sourceSize(EffectCatalog.LOCK_WP_WATER)
        val px = normalMap(sw, sh)
        val a = EffectTextureMath.reliefGrid(px, sw, sh, sp, 6, 6)
        val b = EffectTextureMath.reliefGrid(px, sw, sh, sp.copy(blurThickness = sp.thickness + 3f), 6, 6)
        assertArrayEquals("relief 无 blurred 形参，只吃 thickness", a, b)
    }
}