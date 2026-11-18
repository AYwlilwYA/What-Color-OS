package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.effect.EffectCatalog
import com.coloros16.liquidglass.engine.effect.EffectFamily
import com.coloros16.liquidglass.engine.effect.EffectKind
import com.coloros16.liquidglass.engine.effect.StyleFamilyMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectCatalogTest {

    @Test
    fun idsAreUnique() {
        val ids = EffectCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun everyKindHasEntries() {
        for (kind in EffectKind.values()) {
            if (kind == EffectKind.AOD_STYLE) continue
            assertTrue("kind $kind empty", EffectCatalog.of(kind).isNotEmpty())
        }
    }

    @Test
    fun unknownIdReturnsNull() {
        assertNull(EffectCatalog.find("no.such.effect"))
        assertNull(EffectCatalog.find(null))
    }

    @Test
    fun depth3dIsNotImplemented() {
        val def = EffectCatalog.find(EffectCatalog.LOCK_SP_DEPTH_3D)
        assertNotNull(def)
        assertFalse(def!!.implemented)
        assertTrue(def.requiresPhotoScene)
    }

    @Test
    fun panoramaSpecialsRequireDynamicAndPanorama() {
        for (id in listOf(
            EffectCatalog.LOCK_SP_LIQUID,
            EffectCatalog.LOCK_SP_LENTICULAR,
            EffectCatalog.LOCK_SP_FLASH_CARD,
        )) {
            val def = EffectCatalog.find(id)!!
            assertTrue("$id must require dynamic", def.requiresAodDynamic)
            assertTrue("$id must require panorama", def.requiresPanoramaAod)
        }
    }

    @Test
    fun familyOfKnownStyles() {
        assertEquals(EffectFamily.PANORAMA, StyleFamilyMap.familyOf("aod.fullscreen"))
        assertEquals(EffectFamily.MEIZU, StyleFamilyMap.familyOf("aod.sail"))
        assertEquals(EffectFamily.MEIZU, StyleFamilyMap.familyOf("aod.galaxy"))
        assertEquals(EffectFamily.OPLUS, StyleFamilyMap.familyOf("aod.handpaint"))
    }

    @Test
    fun familyOfUnknownUsesPrefixOrDefault() {
        assertEquals(EffectFamily.SCENE_3D, StyleFamilyMap.familyOf("scene3d.super.1"))
        assertEquals(EffectFamily.SCENE_3D, StyleFamilyMap.familyOf("3d.camera"))
        assertEquals(EffectFamily.OPLUS, StyleFamilyMap.familyOf("oplus.some.new"))
        assertEquals(EffectFamily.STANDARD, StyleFamilyMap.familyOf("whatever"))
        assertEquals(EffectFamily.STANDARD, StyleFamilyMap.familyOf(null))
    }
}
