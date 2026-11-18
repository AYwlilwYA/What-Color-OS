package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.effect.AodMotion
import com.coloros16.liquidglass.engine.effect.AodMotionState
import com.coloros16.liquidglass.engine.effect.EffectCatalog
import com.coloros16.liquidglass.engine.effect.LockWallpaperMotion
import com.coloros16.liquidglass.engine.effect.WallpaperEffectState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class EffectMotionTest {

    private fun aod() = AodMotionState()
    private fun wp() = WallpaperEffectState()

    @Test
    fun aodFrozenWhenDynamicDisabled() {
        val out = aod()
        AodMotion.evaluate(5_000L, allowDynamic = false, colorMixPercent = 80f, rotateSpeedDegPerSec = 20f, photoTiltDeg = 10f, out = out)
        assertFalse(out.active)
        assertEquals(0f, out.rotationDeg, 0f)
        assertEquals(0f, out.colorMix, 0f)
        assertEquals(0f, out.photoTiltDeg, 0f)
    }

    @Test
    fun aodRotationAdvancesAndWraps() {
        val out = aod()
        AodMotion.evaluate(1_000L, true, 60f, 20f, 6f, out)
        assertEquals(20f, out.rotationDeg, 1e-3f)
        AodMotion.evaluate(360_000L, true, 60f, 20f, 6f, out)
        assertTrue("rotation must wrap into 0..360", out.rotationDeg in 0f..360f)
    }

    @Test
    fun aodColorMixAndTiltStayBounded() {
        val out = aod()
        var t = 0L
        while (t <= 40_000L) {
            AodMotion.evaluate(t, true, 100f, 30f, 15f, out)
            assertTrue("colorMix ${out.colorMix} at $t", out.colorMix in 0f..1f)
            assertTrue("tilt ${out.photoTiltDeg} at $t", abs(out.photoTiltDeg) <= 15f + 1e-4f)
            t += 250L
        }
    }

    @Test
    fun lockNoneStaysInactive() {
        val out = wp()
        LockWallpaperMotion.evaluate(EffectCatalog.LOCK_WP_NONE, 5_000L, 1f, 60f, out)
        assertFalse(out.active)
        assertEquals(1f, out.scale, 0f)
    }

    @Test
    fun unknownEffectFallsBack() {
        val out = wp()
        LockWallpaperMotion.evaluate("lock.wp.mystery", 5_000L, 1f, 60f, out)
        assertTrue(out.fallback)
        assertFalse(out.active)
        assertEquals(1f, out.scale, 0f)
    }

    @Test
    fun everyRegisteredWallpaperEffectAnimatesAndStaysBounded() {
        val ids = EffectCatalog.of(com.coloros16.liquidglass.engine.effect.EffectKind.LOCK_WALLPAPER)
            .map { it.id }
            .filter { it != EffectCatalog.LOCK_WP_NONE }
        assertTrue(ids.isNotEmpty())
        val out = wp()
        for (id in ids) {
            for (percent in listOf(0f, 50f, 100f)) {
                var t = 0L
                while (t <= 20_000L) {
                    LockWallpaperMotion.evaluate(id, t, 1.5f, percent, out)
                    assertTrue("$id active", out.active)
                    assertFalse("$id fallback", out.fallback)
                    assertTrue("$id scale ${out.scale}", out.scale in 0.9f..1.1f)
                    assertTrue("$id offsetX ${out.offsetX}", abs(out.offsetX) <= 0.05f)
                    assertTrue("$id offsetY ${out.offsetY}", abs(out.offsetY) <= 0.05f)
                    assertTrue("$id blur ${out.blurFraction}", out.blurFraction in 0f..0.08f)
                    assertTrue("$id density ${out.density}", out.density in 0f..1f)
                    assertTrue("$id phase ${out.phase}", out.phase in 0f..1f)
                    t += 500L
                }
            }
        }
    }

    @Test
    fun intensityZeroKeepsEffectsGeometryStill() {
        val out = wp()
        LockWallpaperMotion.evaluate(EffectCatalog.LOCK_WP_WAVE, 3_000L, 1f, 0f, out)
        assertEquals(0f, out.offsetX, 1e-6f)
        assertEquals(0f, out.offsetY, 1e-6f)
        assertEquals(1f, out.scale, 1e-6f)
    }

    @Test
    fun blurEffectScalesWithIntensity() {
        val low = wp()
        LockWallpaperMotion.evaluate(EffectCatalog.LOCK_WP_BLUR, 1_000L, 1f, 0f, low)
        val high = wp()
        LockWallpaperMotion.evaluate(EffectCatalog.LOCK_WP_BLUR, 1_000L, 1f, 100f, high)
        assertTrue(high.blurFraction > low.blurFraction)
    }
}
