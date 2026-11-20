package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.effect.EffectCatalog
import com.coloros16.liquidglass.engine.effect.HomeMotion
import com.coloros16.liquidglass.engine.effect.IconEntranceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals("前景起点在屏幕前方，必须放大", 1f + HomeMotion.FOREGROUND_DEPTH, start.scale, 1e-4f)

        val end = entrance(EffectCatalog.HOME_ICON_FOREGROUND_FLY, elapsedMs = 10_000L)
        assertEquals(1f, end.alpha, 1e-4f)
        assertEquals(0f, end.y, 1e-4f)
        assertEquals(1f, end.scale, 1e-4f)
    }

    @Test
    fun backgroundFlyComesFromAbove() {
        val start = entrance(EffectCatalog.HOME_ICON_BACKGROUND_FLY, elapsedMs = 0L)
        assertTrue("background should start above", start.y < 0f)
        assertEquals("后景起点在屏幕后方，必须缩小", 1f - HomeMotion.BACKGROUND_DEPTH, start.scale, 1e-4f)
    }

    /** 前景向后飞入：scale 必须单调递减到 1，中途恒大于 1。 */
    @Test
    fun foregroundShrinksMonotonicallyIntoScreen() {
        var prev = Float.MAX_VALUE
        for (ms in 0L..420L step 20L) {
            val s = entrance(EffectCatalog.HOME_ICON_FOREGROUND_FLY, elapsedMs = ms).scale
            assertTrue("前景 scale 必须单调不增 ms=$ms", s <= prev + 1e-5f)
            assertTrue("落位前恒在前方 ms=$ms", s >= 1f - 1e-5f)
            prev = s
        }
        assertEquals(1f, prev, 1e-4f)
    }

    /** 后景向前浮出：scale 必须单调递增到 1，中途恒小于 1。 */
    @Test
    fun backgroundGrowsMonotonicallyOutOfScreen() {
        var prev = -Float.MAX_VALUE
        for (ms in 0L..420L step 20L) {
            val s = entrance(EffectCatalog.HOME_ICON_BACKGROUND_FLY, elapsedMs = ms).scale
            assertTrue("后景 scale 必须单调不减 ms=$ms", s >= prev - 1e-5f)
            assertTrue("落位前恒在后方 ms=$ms", s <= 1f + 1e-5f)
            prev = s
        }
        assertEquals(1f, prev, 1e-4f)
    }

    /** 阳性对照：旧值前景 0.92 起点比原样还小，与「前景」语义相反。 */
    @Test
    fun positiveControlOldForegroundDepthWasInverted() {
        val oldForeStart = 0.92f
        val oldBackStart = 1.08f
        assertTrue("旧前景起点更小=更远，与前景相反", oldForeStart < 1f)
        assertTrue("旧后景起点更大=更近，与后景相反", oldBackStart > 1f)
        val newFore = entrance(EffectCatalog.HOME_ICON_FOREGROUND_FLY, elapsedMs = 0L).scale
        val newBack = entrance(EffectCatalog.HOME_ICON_BACKGROUND_FLY, elapsedMs = 0L).scale
        assertTrue("修后前景必须比后景大", newFore > newBack)
        assertTrue("修后两者近远关系与旧值相反", (newFore - 1f) * (oldForeStart - 1f) < 0f)
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

    /** 只有旋转飞入错峰，其余三种整屏同相位。 */
    @Test
    fun onlyRotateFlyStaggers() {
        assertTrue(HomeMotion.staggered(EffectCatalog.HOME_ICON_ROTATE_FLY))
        for (id in listOf(
            EffectCatalog.HOME_ICON_FOREGROUND_FLY,
            EffectCatalog.HOME_ICON_BACKGROUND_FLY,
            EffectCatalog.HOME_ICON_CROSSFADE,
        )) {
            assertFalse("$id 不得错峰", HomeMotion.staggered(id))
            for (ms in listOf(0L, 60L, 100L, 210L, 400L)) {
                val first = entrance(id, index = 0, elapsedMs = ms)
                val later = entrance(id, index = 6, elapsedMs = ms)
                assertEquals("$id alpha 必须同相位 ms=$ms", first.alpha, later.alpha, 1e-6f)
                assertEquals("$id scale 必须同相位 ms=$ms", first.scale, later.scale, 1e-6f)
                assertEquals("$id y 必须同相位 ms=$ms", first.y, later.y, 1e-6f)
            }
        }
    }

    /** 旋转飞入仍逐个弹出：靠后的必须落后。 */
    @Test
    fun rotateFlyStillStaggers() {
        val first = entrance(EffectCatalog.HOME_ICON_ROTATE_FLY, index = 0, elapsedMs = 100L)
        val later = entrance(EffectCatalog.HOME_ICON_ROTATE_FLY, index = 6, elapsedMs = 100L)
        assertTrue("旋转飞入靠后图标必须落后", later.alpha < first.alpha)
    }

    /** 阳性对照：旧实现给全部四种都加 index×stagger 延迟。 */
    @Test
    fun positiveControlOldImplementationStaggeredEverything() {
        val stagger = 28f
        val duration = 420f
        val elapsed = 100f
        val oldFirst = (elapsed - 0 * stagger) / duration
        val oldLater = ((elapsed - 6 * stagger) / duration).coerceIn(0f, 1f)
        assertTrue("旧实现末位延迟 168ms 已吃掉大半行程", oldLater < oldFirst)
        assertEquals(0f, oldLater, 1e-6f)
        val newLater = entrance(EffectCatalog.HOME_ICON_CROSSFADE, index = 6, elapsedMs = 100L).alpha
        assertTrue("修后同相位，末位不再为 0", newLater > 0f)
    }

    /** 旋转飞入弹出：起点小且倾斜，中途过冲越过原样，落位归 1。 */
    @Test
    fun rotateFlyPopsWithOvershoot() {
        val start = entrance(EffectCatalog.HOME_ICON_ROTATE_FLY, elapsedMs = 0L)
        assertEquals(HomeMotion.ROTATE_FLY_START_SCALE, start.scale, 1e-4f)
        assertEquals(-HomeMotion.ROTATE_FLY_DEG, start.rotationDeg, 1e-3f)
        var peak = 0f
        for (ms in 0L..420L step 10L) {
            peak = maxOf(peak, entrance(EffectCatalog.HOME_ICON_ROTATE_FLY, elapsedMs = ms).scale)
        }
        assertTrue("必须冲过原样 peak=$peak", peak > 1f)
        val end = entrance(EffectCatalog.HOME_ICON_ROTATE_FLY, elapsedMs = 10_000L)
        assertEquals(1f, end.scale, 1e-4f)
        assertEquals(0f, end.rotationDeg, 1e-3f)
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
                    val lo = if (HomeMotion.staggered(id)) {
                        HomeMotion.ROTATE_FLY_START_SCALE
                    } else {
                        1f - HomeMotion.BACKGROUND_DEPTH
                    }
                    assertTrue("$id scale ${out.scale}", out.scale in lo..(1f + HomeMotion.FOREGROUND_DEPTH))
                    assertTrue("$id y ${out.y}", out.y in -1f..1f)
                    assertTrue("$id x ${out.x}", out.x in -1f..0.2f)
                    t += 40L
                }
            }
        }
    }
}
