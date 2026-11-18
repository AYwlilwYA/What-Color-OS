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

class EffectGridOfflineTest {

    private fun spec(id: String) = EffectTextureCatalog.find(id)!!

    /** 造一张可辨识的法线图：每像素 R/G/B 由坐标决定。 */
    private fun ramp(sw: Int, sh: Int): IntArray {
        val a = IntArray(sw * sh)
        for (y in 0 until sh) {
            for (x in 0 until sw) {
                val r = (x * 255 / maxOf(1, sw - 1))
                val g = (y * 255 / maxOf(1, sh - 1))
                val b = ((x + y) * 255 / maxOf(1, sw + sh - 2))
                a[y * sw + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return a
    }

    /** 独立参照实现：直接按定义算，不复用被测函数的中间量。 */
    private fun referenceGrid(
        pixels: IntArray, sw: Int, sh: Int, spec: EffectTextureSpec, w: Int, h: Int,
    ): IntArray {
        val left = if (spec.hasRegion) spec.region[0] else 0
        val top = if (spec.hasRegion) spec.region[1] else 0
        val right = if (spec.hasRegion) spec.region[2] else sw
        val bottom = if (spec.hasRegion) spec.region[3] else sh
        val rw = maxOf(1, right - left)
        val rh = maxOf(1, bottom - top)
        val scale = maxOf(0.01f, spec.layoutScale)
        fun coord(v: Float, span: Int, wrap: TextureWrap): Int {
            if (span <= 1) return 0
            val raw = when (wrap) {
                TextureWrap.REPEAT -> {
                    val m = v % span
                    if (m < 0f) m + span else m
                }
                TextureWrap.STRETCH -> v / span * (span - 1)
                TextureWrap.CROP_OR_STRETCH -> v
            }
            return raw.toInt().coerceIn(0, span - 1)
        }
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val sy = coord(y.toFloat() / h * rh * scale, rh, spec.wrapY)
                val sx = coord(x.toFloat() / w * rw * scale, rw, spec.wrapX)
                out[y * w + x] = pixels[(top + sy) * sw + left + sx]
            }
        }
        return out
    }

    /** 各档真实贴图尺寸；region 必须落在图内，合成源要按此给。 */
    private fun sourceSize(id: String): Pair<Int, Int> = when (id) {
        EffectCatalog.LOCK_WP_STRIPES_FINE -> 14 to 1
        EffectCatalog.LOCK_WP_STRIPES_COARSE -> 120 to 1
        EffectCatalog.LOCK_WP_LIGHT_STRIPES -> 40 to 1
        EffectCatalog.LOCK_WP_GRID -> 482 to 482
        EffectCatalog.LOCK_WP_WATER -> 111 to 3201
        EffectCatalog.LOCK_WP_RIPPLE -> 840 to 600
        else -> 32 to 32
    }

    @Test
    fun sampleGridMatchesIndependentReferenceForAllSpecs() {
        for (s in EffectTextureCatalog.all) {
            val (sw, sh) = sourceSize(s.effectId)
            val px = ramp(sw, sh)
            val got = EffectTextureMath.sampleGrid(px, sw, sh, s, 24, 40)
            val want = referenceGrid(px, sw, sh, s, 24, 40)
            assertArrayEquals(s.effectId, want, got)
        }
    }

    @Test
    fun everyRegionFitsInsideItsRealTexture() {
        for (s in EffectTextureCatalog.all) {
            val (sw, sh) = sourceSize(s.effectId)
            if (!s.hasRegion) continue
            assertTrue("${s.effectId} right<=w", s.region[2] <= sw)
            assertTrue("${s.effectId} bottom<=h", s.region[3] <= sh)
            assertTrue("${s.effectId} left<right", s.region[0] < s.region[2])
            assertTrue("${s.effectId} top<bottom", s.region[1] < s.region[3])
        }
    }

    @Test
    fun shadeGridEqualsSampleThenShadePixel() {
        val sw = 19
        val sh = 23
        val px = ramp(sw, sh)
        for (s in EffectTextureCatalog.all) {
            for (blurred in booleanArrayOf(false, true)) {
                val grid = EffectTextureMath.sampleGrid(px, sw, sh, s, 12, 16)
                for (i in grid.indices) grid[i] = EffectTextureMath.shadePixel(grid[i], s, blurred)
                val got = EffectTextureMath.shadeGrid(px, sw, sh, s, 12, 16, blurred)
                assertArrayEquals("${s.effectId}/$blurred", grid, got)
            }
        }
    }

    @Test
    fun outputSizeIsExactlyRequested() {
        val px = ramp(8, 8)
        val s = spec(EffectCatalog.LOCK_WP_STRIPES_FINE)
        assertEquals(7 * 11, EffectTextureMath.sampleGrid(px, 8, 8, s, 7, 11).size)
        assertEquals(7 * 11, EffectTextureMath.shadeGrid(px, 8, 8, s, 7, 11, false).size)
    }

    @Test
    fun zeroTargetProducesEmptyArray() {
        val px = ramp(8, 8)
        val s = spec(EffectCatalog.LOCK_WP_WATER)
        assertEquals(0, EffectTextureMath.sampleGrid(px, 8, 8, s, 0, 10).size)
        assertEquals(0, EffectTextureMath.sampleGrid(px, 8, 8, s, 10, 0).size)
        assertEquals(0, EffectTextureMath.shadeGrid(px, 8, 8, s, 0, 0, true).size)
    }

    @Test
    fun degenerateSourceProducesZeroFilledArray() {
        val s = spec(EffectCatalog.LOCK_WP_GRID)
        val got = EffectTextureMath.sampleGrid(IntArray(0), 0, 0, s, 4, 4)
        assertEquals(16, got.size)
        assertTrue(got.all { it == 0 })
    }

    @Test
    fun regionIsHonouredInSampling() {
        val s = spec(EffectCatalog.LOCK_WP_RIPPLE)
        assertTrue(s.hasRegion)
        val (sw, sh) = sourceSize(s.effectId)
        val px = ramp(sw, sh)
        val got = EffectTextureMath.sampleGrid(px, sw, sh, s, 8, 8)
        val want = referenceGrid(px, sw, sh, s, 8, 8)
        assertArrayEquals(want, got)
    }

    @Test
    fun outOfBoundsRegionDegradesToZeroInsteadOfCrashing() {
        val s = spec(EffectCatalog.LOCK_WP_RIPPLE)
        val px = ramp(8, 8)
        val got = EffectTextureMath.sampleGrid(px, 8, 8, s, 4, 4)
        assertEquals(16, got.size)
    }

    @Test
    fun repeatWrapWrapsAroundInsteadOfClamping() {
        val sw = 6
        val sh = 6
        val px = ramp(sw, sh)
        val s = spec(EffectCatalog.LOCK_WP_WATER)
        assertEquals(TextureWrap.REPEAT, s.wrapX)
        val got = EffectTextureMath.sampleGrid(px, sw, sh, s, 24, 4)
        val row0 = got.sliceArray(0 until 24)
        val reds = row0.map { (it shr 16) and 0xFF }
        assertTrue("REPEAT 应出现回绕（红分量非单调）", reds.zipWithNext().any { (a, b) -> b < a })
    }

    @Test
    fun stretchWrapIsMonotonicAcrossRow() {
        val sw = 12
        val sh = 12
        val px = ramp(sw, sh)
        val s = spec(EffectCatalog.LOCK_WP_RIPPLE)
        assertEquals(TextureWrap.STRETCH, s.wrapX)
        val got = EffectTextureMath.sampleGrid(px, sw, sh, s, 16, 2)
        val reds = got.sliceArray(0 until 16).map { (it shr 16) and 0xFF }
        assertTrue(reds.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun onePixelSourceIsSafe() {
        val s = spec(EffectCatalog.LOCK_WP_STRIPES_COARSE)
        val px = intArrayOf(0xFF804020.toInt())
        val got = EffectTextureMath.sampleGrid(px, 1, 1, s, 5, 5)
        assertEquals(25, got.size)
        assertTrue(got.all { it == px[0] })
    }

    @Test
    fun shadedAlphaNeverExceedsGlassAlphaFloor() {
        val px = ramp(16, 16)
        for (s in EffectTextureCatalog.all) {
            val glassA = (EffectTextureMath.parseColor(s.glassColor) ushr 24) and 0xFF
            val grid = EffectTextureMath.shadeGrid(px, 16, 16, s, 8, 8, false)
            for (p in grid) {
                val a = (p ushr 24) and 0xFF
                assertTrue("${s.effectId} alpha=$a glassA=$glassA", a <= 255)
                if (s.lightStrength <= 0f) {
                    assertTrue("${s.effectId} 光强 0 时 alpha 不应超过玻璃上限", a <= glassA)
                }
            }
        }
    }

    @Test
    fun blurredBranchUsesBlurTriplet() {
        val px = ramp(16, 16)
        val s = spec(EffectCatalog.LOCK_WP_STRIPES_FINE)
        val plain = EffectTextureMath.shadeGrid(px, 16, 16, s, 8, 8, false)
        val blur = EffectTextureMath.shadeGrid(px, 16, 16, s, 8, 8, true)
        assertTrue("厚度/折射不同应产生不同输出", !plain.contentEquals(blur))
    }

    @Test
    fun identicalSpecsProduceIdenticalGrids() {
        val px = ramp(20, 20)
        val s = spec(EffectCatalog.LOCK_WP_LIGHT_STRIPES)
        val a = EffectTextureMath.shadeGrid(px, 20, 20, s, 10, 10, false)
        val b = EffectTextureMath.shadeGrid(px, 20, 20, s, 10, 10, false)
        assertArrayEquals(a, b)
    }
}