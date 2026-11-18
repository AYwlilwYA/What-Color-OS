package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.effect.EffectCatalog
import com.coloros16.liquidglass.engine.effect.EffectTextureCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectBlurConfigTruthTest {

    private fun spec(id: String) = EffectTextureCatalog.find(id)!!

    @Test
    fun tightStripesBlurPairMatchesSystemConfig() {
        val s = spec(EffectCatalog.LOCK_WP_STRIPES_FINE)
        assertEquals(1, s.blurStep)
        assertEquals(6, s.blurRadius)
        assertEquals(4, s.blurredStep)
        assertEquals(40, s.blurredRadius)
    }

    @Test
    fun altStripesBlurPairMatchesSystemConfig() {
        val s = spec(EffectCatalog.LOCK_WP_STRIPES_COARSE)
        assertEquals(-1, s.blurStep)
        assertEquals(-1, s.blurRadius)
        assertEquals(5, s.blurredStep)
        assertEquals(40, s.blurredRadius)
    }

    @Test
    fun sparseStripesBlurPairMatchesSystemConfig() {
        val s = spec(EffectCatalog.LOCK_WP_LIGHT_STRIPES)
        assertEquals(-1, s.blurStep)
        assertEquals(-1, s.blurRadius)
        assertEquals(3, s.blurredStep)
        assertEquals(60, s.blurredRadius)
    }

    @Test
    fun rhombusGridBlurPairMatchesSystemConfig() {
        val s = spec(EffectCatalog.LOCK_WP_GRID)
        assertEquals(-1, s.blurStep)
        assertEquals(-1, s.blurRadius)
        assertEquals(1, s.blurredStep)
        assertEquals(40, s.blurredRadius)
    }

    @Test
    fun waterRipplesBlurPairMatchesSystemConfig() {
        val s = spec(EffectCatalog.LOCK_WP_WATER)
        assertEquals(1, s.blurStep)
        assertEquals(5, s.blurRadius)
        assertEquals(3, s.blurredStep)
        assertEquals(60, s.blurredRadius)
    }

    @Test
    fun spiralGrainBlurPairMatchesSystemConfig() {
        val s = spec(EffectCatalog.LOCK_WP_RIPPLE)
        assertEquals(1, s.blurStep)
        assertEquals(10, s.blurRadius)
        assertEquals(3, s.blurredStep)
        assertEquals(80, s.blurredRadius)
    }

    @Test
    fun everySpecNeedsBlurOnlyWhenBothFieldsPositive() {
        for (s in EffectTextureCatalog.all) {
            val plain = EffectTextureCatalog.needsBlur(s, false)
            assertEquals(s.effectId, s.blurStep > 0 && s.blurRadius > 0, plain)
            val blurred = EffectTextureCatalog.needsBlur(s, true)
            assertEquals(s.effectId, s.blurredStep > 0 && s.blurredRadius > 0, blurred)
        }
    }

    @Test
    fun allSpecsHavePositiveBlurredPair() {
        for (s in EffectTextureCatalog.all) {
            assertTrue(s.effectId, EffectTextureCatalog.needsBlur(s, true))
        }
    }

    @Test
    fun exactlyThreeSpecsNeedBlurWithoutBackground() {
        val plain = EffectTextureCatalog.all.filter { EffectTextureCatalog.needsBlur(it, false) }
        assertEquals(3, plain.size)
        assertTrue(plain.any { it.effectId == EffectCatalog.LOCK_WP_STRIPES_FINE })
        assertTrue(plain.any { it.effectId == EffectCatalog.LOCK_WP_WATER })
        assertTrue(plain.any { it.effectId == EffectCatalog.LOCK_WP_RIPPLE })
    }

    @Test
    fun negativePairNeverLeaksIntoBlurredBranch() {
        for (s in EffectTextureCatalog.all) {
            if (s.blurStep < 0) {
                assertFalse(s.effectId, EffectTextureCatalog.needsBlur(s, false))
                assertTrue(s.effectId, EffectTextureCatalog.needsBlur(s, true))
            }
        }
    }
}