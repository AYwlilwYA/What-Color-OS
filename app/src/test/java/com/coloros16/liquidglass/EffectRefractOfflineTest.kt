package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.effect.EffectCatalog
import com.coloros16.liquidglass.engine.effect.EffectTextureCatalog
import com.coloros16.liquidglass.engine.effect.EffectTextureMath
import com.coloros16.liquidglass.engine.effect.EffectTextureSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectRefractOfflineTest {

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

    private fun normalMap(sw: Int, sh: Int): IntArray {
        val a = IntArray(sw * sh)
        for (y in 0 until sh) {
            for (x in 0 until sw) {
                val r = (x * 255 / maxOf(1, sw - 1))
                val g = (y * 255 / maxOf(1, sh - 1))
                a[y * sw + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or 0x80
            }
        }
        return a
    }

    private fun wallpaper(ww: Int, wh: Int): IntArray {
        val a = IntArray(ww * wh)
        for (y in 0 until wh) {
            for (x in 0 until ww) {
                a[y * ww + x] = (0xFF shl 24) or ((x * 7 % 256) shl 16) or
                    ((y * 11 % 256) shl 8) or ((x + y) % 256)
            }
        }
        return a
    }

    /** 独立参照：自算强度与偏移，不复用被测函数中间量。 */
    private fun referenceRefract(
        wp: IntArray, ww: Int, wh: Int, grid: IntArray,
        spec: EffectTextureSpec, w: Int, h: Int, blurred: Boolean,
    ): IntArray {
        val thickness = if (blurred) spec.blurThickness else spec.thickness
        val refractivity = if (blurred) spec.blurRefractivity else spec.refractivity
        val strength = thickness * (refractivity - 1f) * minOf(w, h).toFloat() * 0.02f
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val px = grid[i]
                val nx = (((px shr 16) and 0xFF) - 128) / 127f
                val ny = (((px shr 8) and 0xFF) - 128) / 127f
                val ox = (x.toFloat() / w * ww + nx * strength).toInt().coerceIn(0, ww - 1)
                val baseY = (y.toFloat() / h * wh).toInt().coerceIn(0, wh - 1)
                val oy = (baseY + ny * strength).toInt().coerceIn(0, wh - 1)
                out[i] = wp[oy * ww + ox]
            }
        }
        return out
    }

    @Test
    fun refractMatchesIndependentReferenceForAllSpecs() {
        val ww = 64
        val wh = 96
        val wp = wallpaper(ww, wh)
        for (s in EffectTextureCatalog.all) {
            val (sw, sh) = sourceSize(s.effectId)
            val nm = normalMap(sw, sh)
            for (blurred in booleanArrayOf(false, true)) {
                val grid = EffectTextureMath.sampleGrid(nm, sw, sh, s, 20, 30)
                val want = referenceRefract(wp, ww, wh, grid, s, 20, 30, blurred)
                val got = EffectTextureMath.refractGrid(
                    wp.copyOf(), ww, wh, grid, s, 20, 30, blurred,
                )
                assertArrayEquals("${s.effectId}/$blurred", want, got)
            }
        }
    }

    @Test
    fun refractWritesInPlaceAndReturnsSameArray() {
        val s = spec(EffectCatalog.LOCK_WP_WATER)
        val (sw, sh) = sourceSize(s.effectId)
        val grid = EffectTextureMath.sampleGrid(normalMap(sw, sh), sw, sh, s, 8, 8)
        val got = EffectTextureMath.refractGrid(wallpaper(16, 16), 16, 16, grid, s, 8, 8, false)
        assertTrue("应就地改写并返回同一数组", got === grid)
    }

    @Test
    fun flatNormalKeepsWallpaperUnwarped() {
        val s = spec(EffectCatalog.LOCK_WP_GRID)
        val ww = 16
        val wh = 16
        val wp = wallpaper(ww, wh)
        val flatTexel = (0xFF shl 24) or (128 shl 16) or (128 shl 8) or 0x80
        val grid = IntArray(ww * wh) { flatTexel }
        val got = EffectTextureMath.refractGrid(wp, ww, wh, grid, s, ww, wh, false)
        assertArrayEquals("法线全 128 ⇒ 零偏移 ⇒ 与壁纸逐像素相同", wp, got)
    }

    @Test
    fun uniformSourceStaysUniformAfterSampling() {
        val flatTexel = (0xFF shl 24) or (128 shl 16) or (128 shl 8) or 0x80
        for (s in EffectTextureCatalog.all) {
            val (sw, sh) = sourceSize(s.effectId)
            val grid = EffectTextureMath.sampleGrid(
                IntArray(sw * sh) { flatTexel }, sw, sh, s, 12, 12,
            )
            assertTrue("${s.effectId} 均匀源采样后仍应均匀", grid.all { it == flatTexel })
        }
    }

    @Test
    fun zeroTargetReturnsGridUntouched() {
        val s = spec(EffectCatalog.LOCK_WP_STRIPES_FINE)
        val grid = intArrayOf(1, 2, 3)
        val got = EffectTextureMath.refractGrid(wallpaper(4, 4), 4, 4, grid, s, 0, 5, false)
        assertArrayEquals(intArrayOf(1, 2, 3), got)
    }

    @Test
    fun emptyWallpaperReturnsGridUntouched() {
        val s = spec(EffectCatalog.LOCK_WP_WATER)
        val grid = intArrayOf(7, 8, 9, 10)
        val got = EffectTextureMath.refractGrid(IntArray(0), 0, 0, grid, s, 2, 2, false)
        assertArrayEquals(intArrayOf(7, 8, 9, 10), got)
    }

    @Test
    fun undersizedGridIsNotOverrun() {
        val s = spec(EffectCatalog.LOCK_WP_RIPPLE)
        val grid = intArrayOf(5, 6)
        val got = EffectTextureMath.refractGrid(wallpaper(8, 8), 8, 8, grid, s, 4, 4, false)
        assertEquals(2, got.size)
        assertArrayEquals(intArrayOf(5, 6), got)
    }

    @Test
    fun blurredBranchChangesOffsetStrength() {
        val s = spec(EffectCatalog.LOCK_WP_STRIPES_FINE)
        val plainStrength = EffectTextureMath.refractStrength(s, false, 400f)
        val blurStrength = EffectTextureMath.refractStrength(s, true, 400f)
        assertTrue("细条纹模糊档强度应更大", blurStrength > plainStrength)
        val (sw, sh) = sourceSize(s.effectId)
        val nm = normalMap(sw, sh)
        val wp = wallpaper(400, 600)
        val plain = EffectTextureMath.refractGrid(
            wp.copyOf(), 400, 600,
            EffectTextureMath.sampleGrid(nm, sw, sh, s, 200, 300), s, 200, 300, false,
        )
        val blur = EffectTextureMath.refractGrid(
            wp.copyOf(), 400, 600,
            EffectTextureMath.sampleGrid(nm, sw, sh, s, 200, 300), s, 200, 300, true,
        )
        assertTrue("足够尺寸下模糊档应改变折射结果", !plain.contentEquals(blur))
    }

    @Test
    fun rhombusBlurTripletEqualsPlainSoOffsetIdentical() {
        val s = spec(EffectCatalog.LOCK_WP_GRID)
        assertEquals(s.thickness, s.blurThickness, 1e-6f)
        assertEquals(s.refractivity, s.blurRefractivity, 1e-6f)
        assertEquals(
            EffectTextureMath.refractStrength(s, false, 400f),
            EffectTextureMath.refractStrength(s, true, 400f),
            1e-6f,
        )
    }

    @Test
    fun tinyTargetOffsetIsSubPixelButTruncationStillShiftsNegativeNormals() {
        val s = spec(EffectCatalog.LOCK_WP_WATER)
        val strength = EffectTextureMath.refractStrength(s, false, 16f)
        assertTrue("16px 短边下强度不足 1px", strength < 1f)
        val (sw, sh) = sourceSize(s.effectId)
        val wp = wallpaper(16, 16)
        val got = EffectTextureMath.refractGrid(
            wp.copyOf(), 16, 16,
            EffectTextureMath.sampleGrid(normalMap(sw, sh), sw, sh, s, 16, 16), s, 16, 16, false,
        )
        val moved = got.indices.count { got[it] != wp[it] }
        assertTrue("负法线经 toInt 向零截断仍会左/上移一格", moved > 0)
        assertTrue("亚像素强度下多数像素不变", moved < got.size)
    }

    @Test
    fun flatNormalAtTinyTargetKeepsWallpaperExactly() {
        val s = spec(EffectCatalog.LOCK_WP_WATER)
        val wp = wallpaper(16, 16)
        val flat = (0xFF shl 24) or (128 shl 16) or (128 shl 8) or 0x80
        val grid = IntArray(16 * 16) { flat }
        val got = EffectTextureMath.refractGrid(wp.copyOf(), 16, 16, grid, s, 16, 16, false)
        assertArrayEquals("零偏移时输出与原壁纸逐像素相同", wp, got)
    }

    @Test
    fun outputPixelsAllComeFromWallpaper() {
        val s = spec(EffectCatalog.LOCK_WP_LIGHT_STRIPES)
        val (sw, sh) = sourceSize(s.effectId)
        val wp = wallpaper(32, 40)
        val set = wp.toHashSet()
        val got = EffectTextureMath.refractGrid(
            wp.copyOf(), 32, 40,
            EffectTextureMath.sampleGrid(normalMap(sw, sh), sw, sh, s, 12, 18), s, 12, 18, false,
        )
        assertTrue("折射只做重采样，不应产生壁纸里没有的颜色", got.all { it in set })
    }

    @Test
    fun extremeNormalsStayInsideWallpaperBounds() {
        val s = spec(EffectCatalog.LOCK_WP_WATER)
        val ww = 8
        val wh = 8
        val wp = wallpaper(ww, wh)
        val extreme = intArrayOf(
            (0xFF shl 24) or (0 shl 16) or (0 shl 8) or 0,
            (0xFF shl 24) or (255 shl 16) or (255 shl 8) or 255,
        )
        val grid = EffectTextureMath.sampleGrid(extreme, 2, 1, s, 16, 16)
        val got = EffectTextureMath.refractGrid(wp, ww, wh, grid, s, 16, 16, true)
        val set = wp.toHashSet()
        assertTrue(got.all { it in set })
    }
}