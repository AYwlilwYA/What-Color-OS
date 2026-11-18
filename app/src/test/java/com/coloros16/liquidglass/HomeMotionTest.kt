package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.effect.EffectCatalog
import com.coloros16.liquidglass.engine.effect.HomeMotion
import com.coloros16.liquidglass.engine.effect.IconEntranceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeMotionTest {

    private fun state() = IconEntranceState()

    private fun entrance(
        effectId: String,
        index: Int = 0,
        count: Int = 8,
        elapsedMs: Long,
        out: IconEntranceState = state(),
    ): IconEntranceState {
        HomeMotion.iconEntrance(effectId, index, count, elapsedMs, 420f, 28f, 60f, out)
        return out
    }

    @Test
    fun startAndEndOfForegroundFly() {
        val start = entrance(EffectCatalog.HOME_ICON_FOREGROUND_FLY, elapsedMs = 0L)
        assertEquals(0f, start.alpha, 1e-4f)
        assertTrue("start should sit below", start.y > 0f)
        assertTrue("start slightly scaled down", start.scale < 1f)

        val end = entrance(EffectCatalog.HOME_ICON_FOREGROUND_FLY, elapsedMs = 10_000L)
        assertEquals(1f, end.alpha, 1e-4f)
        assertEquals(0f, end.y, 1e-4f)
        assertEquals(1f, end.scale, 1e-4f)
    }

    @Test
    fun backgroundFlyComesFromAbove() {
        val start = entrance(EffectCatalog.HOME_ICON_BACKGROUND_FLY, elapsedMs = 0L)
        assertTrue("background should start above", start.y < 0f)
        assertTrue("background starts larger", start.scale > 1f)
    }

    @Test
    fun rotateFlyEndsStraight() {
        val start = entrance(EffectCatalog.HOME_ICON_ROTATE_FLY, elapsedMs = 0L)
        assertTrue(start.rotationDeg < 0f)
        assertTrue(start.x < 0f)
        val end = entrance(EffectCatalog.HOME_ICON_ROTATE_FLY, elapsedMs = 10_000L)
        assertEquals(0f, end.rotationDeg, 1e-4f)
        assertEquals(0f, end.x, 1e-4f)
    }

    @Test
    fun crossfadeOnlyTouchesAlpha() {
        val mid = entrance(EffectCatalog.HOME_ICON_CROSSFADE, elapsedMs = 200L)
        assertEquals(0f, mid.x, 0f)
        assertEquals(0f, mid.y, 0f)
        assertEquals(1f, mid.scale, 0f)
        assertEquals(0f, mid.rotationDeg, 0f)
        assertTrue(mid.alpha in 0f..1f)
    }

    @Test
    fun staggerDelaysLaterIcons() {
        val first = entrance(EffectCatalog.HOME_ICON_CROSSFADE, index = 0, elapsedMs = 100L)
        val later = entrance(EffectCatalog.HOME_ICON_CROSSFADE, index = 6, elapsedMs = 100L)
        assertTrue("later icon should lag", later.alpha < first.alpha)
    }

    @Test
    fun alphaAndOffsetsStayInRangeForAllEffects() {
        val ids = EffectCatalog.of(com.coloros16.liquidglass.engine.effect.EffectKind.HOME_ICON).map { it.id }
        val out = state()
        for (id in ids) {
            for (index in 0 until 12) {
                var t = 0L
                while (t <= 2_000L) {
                    HomeMotion.iconEntrance(id, index, 12, t, 420f, 28f, 100f, out)
                    assertTrue("$id alpha ${out.alpha}", out.alpha in 0f..1f)
                    assertTrue("$id scale ${out.scale}", out.scale in 0.85f..1.15f)
                    assertTrue("$id y ${out.y}", out.y in -1f..1f)
                    assertTrue("$id x ${out.x}", out.x in -1f..0.2f)
                    t += 40L
                }
            }
        }
    }
}
