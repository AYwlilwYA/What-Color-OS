package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.EffectOverlayPolicy
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.SystemSceneMotion
import com.coloros16.liquidglass.engine.TransitionEdge
import com.coloros16.liquidglass.engine.effect.EffectCatalog
import com.coloros16.liquidglass.engine.from
import com.coloros16.liquidglass.engine.to
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 效果层跨三态可见度契约：hook 与预览共用同一口径，钉住「桌面不跟着锁屏淡出」。 */
class EffectVisibilityContractTest {

    private val edges = listOf(
        TransitionEdge.AOD_TO_LOCK,
        TransitionEdge.LOCK_TO_AOD,
        TransitionEdge.LOCK_TO_HOME,
        TransitionEdge.HOME_TO_LOCK,
        TransitionEdge.AOD_TO_HOME,
        TransitionEdge.HOME_TO_AOD,
    )

    private val samples = floatArrayOf(0f, 0.125f, 0.25f, 0.375f, 0.5f, 0.625f, 0.75f, 0.875f, 1f)

    @Test
    fun staticVisibilityCoversLockAndHome() {
        assertEquals(0f, EffectOverlayPolicy.visibilityOf(SceneState.AOD), 0.0001f)
        assertEquals(1f, EffectOverlayPolicy.visibilityOf(SceneState.LOCK), 0.0001f)
        assertEquals(1f, EffectOverlayPolicy.visibilityOf(SceneState.HOME), 0.0001f)
    }

    @Test
    fun nullEdgeFallsBackToStaticValue() {
        for (state in SceneState.entries) {
            for (t in samples) {
                assertEquals(
                    EffectOverlayPolicy.visibilityOf(state),
                    EffectOverlayPolicy.visibilityAt(state, null, t),
                    0.0001f,
                )
            }
        }
    }

    @Test
    fun lockHomeEdgesStayFullyVisible() {
        for (edge in listOf(TransitionEdge.LOCK_TO_HOME, TransitionEdge.HOME_TO_LOCK)) {
            for (t in samples) {
                assertEquals(
                    "edge=$edge t=$t 桌面与锁屏都露壁纸，不该淡出",
                    1f,
                    EffectOverlayPolicy.visibilityAt(SceneState.LOCK, edge, t),
                    0.0001f,
                )
            }
        }
    }

    @Test
    fun aodEdgesRampMonotonically() {
        for (edge in edges) {
            val rising = EffectOverlayPolicy.visibilityOf(edge.to) >
                EffectOverlayPolicy.visibilityOf(edge.from)
            val flat = EffectOverlayPolicy.visibilityOf(edge.to) ==
                EffectOverlayPolicy.visibilityOf(edge.from)
            var prev = -1f
            for (t in samples) {
                val v = EffectOverlayPolicy.visibilityAt(SceneState.LOCK, edge, t)
                assertTrue("edge=$edge t=$t 越界 v=$v", v in 0f..1f)
                if (prev >= 0f) {
                    if (rising) assertTrue("edge=$edge 应递增", v >= prev - 0.0001f)
                    else if (!flat) assertTrue("edge=$edge 应递减", v <= prev + 0.0001f)
                }
                prev = v
            }
        }
    }

    @Test
    fun edgeEndpointsMatchStaticStates() {
        for (edge in edges) {
            assertEquals(
                "edge=$edge 起点",
                EffectOverlayPolicy.visibilityOf(edge.from),
                EffectOverlayPolicy.visibilityAt(SceneState.LOCK, edge, 0f),
                0.0001f,
            )
            assertEquals(
                "edge=$edge 终点",
                EffectOverlayPolicy.visibilityOf(edge.to),
                EffectOverlayPolicy.visibilityAt(SceneState.LOCK, edge, 1f),
                0.0001f,
            )
        }
    }

    @Test
    fun progressIsClampedOutsideUnitRange() {
        val edge = TransitionEdge.AOD_TO_LOCK
        assertEquals(0f, EffectOverlayPolicy.visibilityAt(SceneState.LOCK, edge, -2f), 0.0001f)
        assertEquals(1f, EffectOverlayPolicy.visibilityAt(SceneState.LOCK, edge, 3f), 0.0001f)
    }

    /** 阳性对照：旧口径（跟锁屏层透明度）在 LOCK→HOME 会淡出，新口径不会。 */
    @Test
    fun newVisibilityDiffersFromLockAlphaOnUnlock() {
        val layers = SystemSceneMotion.Layers()
        var differs = 0
        for (edge in listOf(TransitionEdge.LOCK_TO_HOME, TransitionEdge.HOME_TO_LOCK)) {
            for (t in samples) {
                SystemSceneMotion.sample(edge, t, layers)
                val old = layers.lockAlpha
                val new = EffectOverlayPolicy.visibilityAt(SceneState.LOCK, edge, t)
                if (kotlin.math.abs(old - new) > 0.01f) differs++
            }
        }
        assertTrue("两条口径必须在解锁边真的不同，否则是假修复", differs > 0)
    }

    /** 阳性对照：HOME 静止态旧口径恒 0（效果完全不画），新口径为 1。 */
    @Test
    fun homeStaticWasInvisibleUnderOldRule() {
        val layers = SystemSceneMotion.Layers()
        SystemSceneMotion.sampleStatic(SceneState.HOME, layers)
        assertEquals(0f, layers.lockAlpha, 0.0001f)
        assertEquals(1f, EffectOverlayPolicy.visibilityOf(SceneState.HOME), 0.0001f)
    }

    @Test
    fun aodStaticStaysHiddenUnderBothRules() {
        val layers = SystemSceneMotion.Layers()
        SystemSceneMotion.sampleStatic(SceneState.AOD, layers)
        assertEquals(0f, layers.lockAlpha, 0.0001f)
        assertEquals(0f, EffectOverlayPolicy.visibilityOf(SceneState.AOD), 0.0001f)
    }

    @Test
    fun buildGateIgnoresSceneAndAcceptsAnyRealEffect() {
        assertTrue(EffectOverlayPolicy.needsBuild(EffectCatalog.LOCK_WP_WATER, null))
        assertTrue(EffectOverlayPolicy.needsBuild(EffectCatalog.LOCK_WP_STRIPES_FINE, null))
        assertTrue(EffectOverlayPolicy.needsBuild(null, EffectCatalog.LOCK_SP_BOKEH))
    }

    @Test
    fun buildGateRejectsNoneAndNull() {
        assertFalse(EffectOverlayPolicy.needsBuild(null, null))
        assertFalse(EffectOverlayPolicy.needsBuild(EffectCatalog.LOCK_WP_NONE, null))
        assertFalse(EffectOverlayPolicy.needsBuild(null, EffectCatalog.LOCK_SP_NONE))
        assertFalse(
            EffectOverlayPolicy.needsBuild(EffectCatalog.LOCK_WP_NONE, EffectCatalog.LOCK_SP_NONE),
        )
    }

    /** 息屏态可见度为 0 但仍要构建：选中即预处理，不等切到锁屏。 */
    @Test
    fun aodStillBuildsEvenThoughInvisible() {
        val id = EffectCatalog.LOCK_WP_GRID
        assertTrue(EffectOverlayPolicy.needsBuild(id, null))
        assertEquals(0f, EffectOverlayPolicy.visibilityOf(SceneState.AOD), 0.0001f)
    }
}