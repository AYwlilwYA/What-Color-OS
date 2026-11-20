package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SystemSceneMotion
import com.coloros16.liquidglass.engine.TransitionEdge
import com.coloros16.liquidglass.engine.effect.EffectCatalog
import com.coloros16.liquidglass.engine.effect.EffectParams
import com.coloros16.liquidglass.engine.effect.EffectSettings
import com.coloros16.liquidglass.engine.effect.HomeMotion
import com.coloros16.liquidglass.engine.effect.IconEntranceState
import com.coloros16.liquidglass.ui.aod.AodSceneCatalog
import com.coloros16.liquidglass.ui.aod.AodStyleCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 过渡时长滑杆对图标相位的端到端影响（alpha 级）。
 *
 * 分母同源本身由 `IconEntranceDurationSourceContractTest` 钉死，本套件只补它没覆盖的角度：
 * 四档滑杆必须给出四个不同的图标 alpha，以及图标在长边里只占多大一段。
 */
class IconPhaseSliderEffectContractTest {

    private fun settings(id: String, ms: Float): EffectSettings =
        EffectSettings().also {
            it.homeTransition = id
            it.setParam(EffectParams.HOME_TRANSITION_DURATION, ms)
        }

    private fun alphaAt(elapsed: Long): Float {
        val out = IconEntranceState()
        HomeMotion.iconEntrance(
            EffectCatalog.HOME_ICON_FOREGROUND_FLY, 0, 24, elapsed, 420f, 28f, 100f, out,
        )
        return out.alpha
    }

    /** ★四档滑杆必须给出四个不同 alpha：这是「滑杆真影响图标」的端到端判据。 */
    @Test
    fun optionDurationChangesIconAlpha() {
        val edge = TransitionEdge.LOCK_TO_HOME
        val t = 0.25f
        val seen = ArrayList<Float>()
        for (ms in floatArrayOf(200f, 500f, 900f, 1200f)) {
            val s = settings(EffectCatalog.HOME_TRANSITION_CROSSFADE, ms)
            val edgeMs = AodSceneCatalog.durationOf(AodStyleCatalog.ID_STARMOON, edge, s)
            assertEquals("选项时长必须生效", ms.toLong(), edgeMs)
            seen.add(alphaAt((t * edgeMs).toLong()))
        }
        assertEquals("四档必须四个不同值", 4, seen.toSet().size)
        assertEquals("200ms 档 alpha 0.3465", 0.3465f, seen[0], 2e-3f)
        assertEquals("500ms 档 alpha 0.6420", 0.6420f, seen[1], 2e-3f)
        assertEquals("900ms 档 alpha 0.8639", 0.8639f, seen[2], 2e-3f)
        assertEquals("1200ms 档 alpha 0.9526", 0.9526f, seen[3], 2e-3f)
        assertTrue("边越短同一 t 下演得越少", seen[0] < seen[3])
    }

    /** ★阳性对照：旧分母下四档滑杆给出同一个 alpha ⇒ 滑杆对图标完全无效。 */
    @Test
    fun positiveControlOldDenominatorIgnoresSlider() {
        val t = 0.25f
        val fixed = SystemSceneMotion.durationOf(TransitionEdge.LOCK_TO_HOME)
        val fixedAlpha = alphaAt((t * fixed).toLong())
        assertEquals("旧分母恒 0.8213", 0.8213f, fixedAlpha, 2e-3f)
        for (ms in floatArrayOf(200f, 500f, 900f, 1200f)) {
            val s = settings(EffectCatalog.HOME_TRANSITION_CROSSFADE, ms)
            val real = AodSceneCatalog.durationOf(AodStyleCatalog.ID_STARMOON, TransitionEdge.LOCK_TO_HOME, s)
            assertEquals("选项确实改了边长", ms.toLong(), real)
            assertEquals("但旧分母算出的 alpha 与滑杆无关", fixedAlpha, alphaAt((t * fixed).toLong()), 1e-6f)
        }
    }

    /** ★长边里图标只占前一小段：420ms 行程 / 3840ms 边 = 10.94%，之后全程静止。 */
    @Test
    fun iconOccupiesOnlyHeadOfLongEdge() {
        val real = AodSceneCatalog.durationOf(
            AodStyleCatalog.ID_SAIL, TransitionEdge.LOCK_TO_HOME,
            settings(EffectCatalog.HOME_TRANSITION_NONE, 500f),
        )
        assertEquals("启航解锁边 3840ms", 3840L, real)
        assertEquals("图标只占边的 10.94%", 0.1094f, 420f / real, 1e-3f)
        assertTrue("t=0.25 真值分母已满", alphaAt((0.25f * real).toLong()) > 0.999f)
        assertTrue("旧分母此刻还在 0.82", alphaAt((0.25f * 800L).toLong()) < 0.85f)
    }

    /** 息屏直进桌面那条边不受选项控制，但仍取款式真值（alpha 级验证）。 */
    @Test
    fun aodToHomeIgnoresOptionButUsesStyleTruth() {
        val edge = TransitionEdge.AOD_TO_HOME
        val s = settings(EffectCatalog.HOME_TRANSITION_CROSSFADE, 1200f)
        val sail = AodSceneCatalog.durationOf(AodStyleCatalog.ID_SAIL, edge, s)
        assertEquals("该边不受选项控制，取启航真值 1000ms", 1000L, sail)
        val t = 0.25f
        assertEquals("真值分母 alpha 0.8995", 0.8995f, alphaAt((t * sail).toLong()), 2e-3f)
        assertEquals("旧分母 alpha 0.7709", 0.7709f, alphaAt((t * 700L).toLong()), 2e-3f)
    }
}
