package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.effect.EffectCatalog
import com.coloros16.liquidglass.engine.effect.EffectTextureCatalog
import com.coloros16.liquidglass.engine.effect.TextureWrap
import com.coloros16.liquidglass.engine.effect.TiltShiftSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EffectTextureCatalogTest {

    private val assetDir: File = run {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf(File(dir, "src/main/assets"), File(dir, "app/src/main/assets"))
                .firstOrNull { it.isDirectory }?.let { return@run it }
            dir = dir.parentFile
        }
        File("src/main/assets")
    }

    @Test
    fun everySpecMapsToKnownEffectId() {
        for (spec in EffectTextureCatalog.all) {
            assertNotNull("unknown id ${spec.effectId}", EffectCatalog.find(spec.effectId))
        }
    }

    @Test
    fun textureFilesExistUnderDeclaredPath() {
        assertTrue("assets dir missing: ${assetDir.absolutePath}", assetDir.isDirectory)
        for (spec in EffectTextureCatalog.all) {
            val f = File(assetDir, spec.texturePath)
            assertTrue("missing texture ${spec.texturePath}", f.isFile)
            assertTrue("empty texture ${spec.texturePath}", f.length() > 0)
        }
    }

    @Test
    fun unknownIdReturnsNull() {
        assertNull(EffectTextureCatalog.find(null))
        assertNull(EffectTextureCatalog.find("no.such.effect"))
        assertFalse(EffectTextureCatalog.hasTexture(EffectCatalog.LOCK_WP_NONE))
        assertTrue(EffectTextureCatalog.hasTexture(EffectCatalog.LOCK_WP_WATER))
    }

    @Test
    fun normalMapValuesMatchSystemConfig() {
        val fine = EffectTextureCatalog.find(EffectCatalog.LOCK_WP_STRIPES_FINE)!!
        assertEquals(1f, fine.layoutScale, 0.0001f)
        assertEquals("#05131F4E", fine.glassColor)
        assertEquals(3.6f, fine.thickness, 0.0001f)
        assertEquals(1.18f, fine.refractivity, 0.0001f)
        assertEquals(8.2f, fine.specular, 0.0001f)
        assertEquals(-22.293f, fine.lightAngleDeg, 0.0001f)
        assertEquals(0.1f, fine.lightStrength, 0.0001f)
        assertEquals("#FFDEDEDE", fine.lightColor)

        val coarse = EffectTextureCatalog.find(EffectCatalog.LOCK_WP_STRIPES_COARSE)!!
        assertEquals(2.93f, coarse.layoutScale, 0.0001f)
        assertEquals("#0A101323", coarse.glassColor)
        assertEquals(1.06f, coarse.thickness, 0.0001f)
        assertEquals(1.6f, coarse.specular, 0.0001f)

        val light = EffectTextureCatalog.find(EffectCatalog.LOCK_WP_LIGHT_STRIPES)!!
        assertEquals(0.99f, light.layoutScale, 0.0001f)
        assertEquals(43f, light.specular, 0.0001f)

        val grid = EffectTextureCatalog.find(EffectCatalog.LOCK_WP_GRID)!!
        assertEquals(8.04f, grid.layoutScale, 0.0001f)
        assertTrue(grid.hasRegion)
        assertArrayEquals(intArrayOf(0, 0, 482, 482), grid.region)
        assertEquals(0f, grid.lightStrength, 0.0001f)

        val water = EffectTextureCatalog.find(EffectCatalog.LOCK_WP_WATER)!!
        assertEquals(TextureWrap.REPEAT, water.wrapX)
        assertEquals(TextureWrap.CROP_OR_STRETCH, water.wrapY)
        assertEquals(32.619f, water.lightAngleDeg, 0.0001f)

        val ripple = EffectTextureCatalog.find(EffectCatalog.LOCK_WP_RIPPLE)!!
        assertEquals(0.75f, ripple.layoutScale, 0.0001f)
        assertArrayEquals(intArrayOf(285, 0, 555, 600), ripple.region)
        assertEquals(TextureWrap.STRETCH, ripple.wrapX)
        assertEquals(TextureWrap.STRETCH, ripple.wrapY)
        assertEquals(-30.929f, ripple.lightAngleDeg, 0.0001f)
    }

    @Test
    fun blurVariantsMatchSystemConfig() {
        val fine = EffectTextureCatalog.find(EffectCatalog.LOCK_WP_STRIPES_FINE)!!
        assertEquals(5f, EffectTextureCatalog.thickness(fine, true), 0.0001f)
        assertEquals(1.36f, EffectTextureCatalog.refractivity(fine, true), 0.0001f)
        assertEquals(8.2f, EffectTextureCatalog.specular(fine, true), 0.0001f)
        assertEquals(3.6f, EffectTextureCatalog.thickness(fine, false), 0.0001f)

        val grid = EffectTextureCatalog.find(EffectCatalog.LOCK_WP_GRID)!!
        assertEquals(grid.thickness, EffectTextureCatalog.thickness(grid, true), 0.0001f)
    }

    @Test
    fun needsBlurFollowsStepAndRadius() {
        val coarse = EffectTextureCatalog.find(EffectCatalog.LOCK_WP_STRIPES_COARSE)!!
        assertFalse(EffectTextureCatalog.needsBlur(coarse, false))
        assertTrue(EffectTextureCatalog.needsBlur(coarse, true))

        val fine = EffectTextureCatalog.find(EffectCatalog.LOCK_WP_STRIPES_FINE)!!
        assertTrue(EffectTextureCatalog.needsBlur(fine, false))
        assertTrue(EffectTextureCatalog.needsBlur(fine, true))

        val grid = EffectTextureCatalog.find(EffectCatalog.LOCK_WP_GRID)!!
        assertFalse(EffectTextureCatalog.needsBlur(grid, false))
        assertTrue(EffectTextureCatalog.needsBlur(grid, true))
    }

    @Test
    fun regionFlagOnlyWhenNonZero() {
        val fine = EffectTextureCatalog.find(EffectCatalog.LOCK_WP_STRIPES_FINE)!!
        assertFalse(fine.hasRegion)
        val ripple = EffectTextureCatalog.find(EffectCatalog.LOCK_WP_RIPPLE)!!
        assertTrue(ripple.hasRegion)
    }

    @Test
    fun specEqualityUsesRegionContent() {
        val a = EffectTextureCatalog.find(EffectCatalog.LOCK_WP_GRID)!!
        val b = a.copy(region = intArrayOf(0, 0, 482, 482))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        val c = a.copy(region = intArrayOf(0, 0, 480, 482))
        assertFalse(a == c)
    }

    @Test
    fun tiltShiftIsBandBlurNotBokeh() {
        assertEquals(1, TiltShiftSpec.LEVEL)
        assertEquals(300, TiltShiftSpec.RADIUS)
        assertEquals(0.45f, TiltShiftSpec.HEIGHT_PERCENT, 0.0001f)
        assertEquals(1f, TiltShiftSpec.CENTER_OFFSET, 0.0001f)
        assertEquals(1.36f, TiltShiftSpec.SMOOTH_PERCENT, 0.0001f)
        assertEquals(840f * 0.45f, TiltShiftSpec.bandHeightPx(840f), 0.001f)
        assertEquals(840f, TiltShiftSpec.bandCenterPx(840f), 0.001f)
        assertEquals(840f * 0.45f * 1.36f, TiltShiftSpec.featherPx(840f), 0.001f)
    }
}
