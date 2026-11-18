package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.SystemSceneMotion
import com.coloros16.liquidglass.engine.TransitionEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemSceneMotionTest {

    private val layers = SystemSceneMotion.Layers()

    @Test
    fun staticAnchorsMatchSystemState() {
        SystemSceneMotion.sampleStatic(SceneState.AOD, layers)
        assertEquals(1f, layers.wallpaperScale, 0.0001f)
        assertEquals(1f, layers.aodAlpha, 0.0001f)
        assertEquals(0f, layers.lockAlpha, 0.0001f)
        assertEquals(SystemSceneMotion.DIM_AOD, layers.dim, 0.0001f)

        SystemSceneMotion.sampleStatic(SceneState.LOCK, layers)
        assertEquals(SystemSceneMotion.WALLPAPER_SCALE_LOCK, layers.wallpaperScale, 0.0001f)
        assertEquals(1f, layers.lockAlpha, 0.0001f)
        assertEquals(0f, layers.homeAlpha, 0.0001f)

        SystemSceneMotion.sampleStatic(SceneState.HOME, layers)
        assertEquals(1f, layers.wallpaperScale, 0.0001f)
        assertEquals(1f, layers.homeAlpha, 0.0001f)
        assertEquals(0f, layers.lockAlpha, 0.0001f)
    }

    @Test
    fun wallpaperZoomsOnlyOnLockState() {
        SystemSceneMotion.sample(TransitionEdge.AOD_TO_LOCK, 0f, layers)
        assertEquals(1f, layers.wallpaperScale, 0.0001f)
        SystemSceneMotion.sample(TransitionEdge.AOD_TO_LOCK, 1f, layers)
        assertEquals(SystemSceneMotion.WALLPAPER_SCALE_LOCK, layers.wallpaperScale, 0.0001f)

        SystemSceneMotion.sample(TransitionEdge.LOCK_TO_HOME, 0f, layers)
        assertEquals(SystemSceneMotion.WALLPAPER_SCALE_LOCK, layers.wallpaperScale, 0.0001f)
        SystemSceneMotion.sample(TransitionEdge.LOCK_TO_HOME, 0.5f, layers)
        assertEquals(SystemSceneMotion.WALLPAPER_SCALE_LOCK, layers.wallpaperScale, 0.0001f)
        SystemSceneMotion.sample(TransitionEdge.LOCK_TO_HOME, 1f, layers)
        assertEquals(SystemSceneMotion.WALLPAPER_SCALE_LOCK, layers.wallpaperScale, 0.0001f)

        SystemSceneMotion.sample(TransitionEdge.AOD_TO_HOME, 0.5f, layers)
        assertEquals(1f, layers.wallpaperScale, 0.0001f)
    }

    @Test
    fun aodLayerOnlyFadesWithoutScaling() {
        SystemSceneMotion.sample(TransitionEdge.AOD_TO_LOCK, 0f, layers)
        assertEquals(1f, layers.aodAlpha, 0.0001f)
        SystemSceneMotion.sample(TransitionEdge.AOD_TO_LOCK, 1f, layers)
        assertEquals(0f, layers.aodAlpha, 0.0001f)
        assertEquals(1f, layers.aodClockScale, 0.0001f)
    }

    @Test
    fun lockAndHomeFadeStaggeredOnUnlock() {
        SystemSceneMotion.sample(TransitionEdge.LOCK_TO_HOME, 0.1f, layers)
        assertTrue(layers.lockAlpha < 1f)
        assertTrue(layers.homeAlpha in 0.01f..0.3f)

        SystemSceneMotion.sample(TransitionEdge.LOCK_TO_HOME, 0.6f, layers)
        assertTrue(layers.homeAlpha > 0f)
        assertTrue(layers.lockAlpha < 0.05f)
    }

    @Test
    fun timelineAnchorsAndMidpointAreContinuous() {
        SystemSceneMotion.sampleTimeline(0f, layers)
        assertEquals(1f, layers.aodAlpha, 0.0001f)
        assertEquals(1f, layers.wallpaperScale, 0.0001f)

        SystemSceneMotion.sampleTimeline(0.5f, layers)
        assertEquals(SystemSceneMotion.WALLPAPER_SCALE_LOCK, layers.wallpaperScale, 0.0001f)
        assertEquals(1f, layers.lockAlpha, 0.0001f)
        assertEquals(0f, layers.aodAlpha, 0.0001f)
        assertEquals(0f, layers.homeAlpha, 0.0001f)

        SystemSceneMotion.sampleTimeline(1f, layers)
        assertEquals(SystemSceneMotion.WALLPAPER_SCALE_LOCK, layers.wallpaperScale, 0.0001f)
        assertEquals(1f, layers.homeAlpha, 0.0001f)
        assertEquals(0f, layers.lockAlpha, 0.0001f)
    }

    @Test
    fun timelineScrubIsLinearToFinger() {
        SystemSceneMotion.sampleTimeline(0.25f, layers)
        assertEquals(0.5f, layers.aodAlpha, 0.0001f)
        SystemSceneMotion.sampleTimeline(0.75f, layers)
        assertEquals(SystemSceneMotion.WALLPAPER_SCALE_LOCK, layers.wallpaperScale, 0.0001f)
    }

    @Test
    fun lockHomeWallpaperNeverScales() {
        for (edge in listOf(TransitionEdge.LOCK_TO_HOME, TransitionEdge.HOME_TO_LOCK)) {
            for (direct in listOf(false, true)) {
                for (step in 0..20) {
                    SystemSceneMotion.sample(edge, step / 20f, layers, direct)
                    assertEquals(
                        "$edge direct=$direct step=$step",
                        SystemSceneMotion.WALLPAPER_SCALE_LOCK,
                        layers.wallpaperScale,
                        0.0001f,
                    )
                }
            }
        }
        SystemSceneMotion.sampleTimeline(1f, layers)
        assertEquals(SystemSceneMotion.WALLPAPER_SCALE_LOCK, layers.wallpaperScale, 0.0001f)
    }

    @Test
    fun everyEdgeStaysWithinRanges() {
        val edges = TransitionEdge.values()
        for (edge in edges) {
            var last = -1f
            for (step in 0..20) {
                val p = step / 20f
                SystemSceneMotion.sample(edge, p, layers)
                assertTrue(edge.name, layers.aodAlpha in 0f..1f)
                assertTrue(edge.name, layers.lockAlpha in 0f..1f)
                assertTrue(edge.name, layers.homeAlpha in 0f..1f)
                assertTrue(edge.name, layers.wallpaperScale >= 0.999f)
                assertTrue(edge.name, layers.wallpaperScale <= 1.101f)
                assertTrue(edge.name, layers.dim in 0.09f..0.63f)
                last = p
            }
            assertEquals(1f, last, 0.0001f)
        }
    }

    @Test
    fun durationsFollowSystemValues() {
        assertEquals(600L, SystemSceneMotion.durationOf(TransitionEdge.AOD_TO_LOCK))
        assertEquals(800L, SystemSceneMotion.durationOf(TransitionEdge.LOCK_TO_HOME))
        assertEquals(500L, SystemSceneMotion.durationOf(TransitionEdge.LOCK_TO_AOD))
        assertEquals(600L, SystemSceneMotion.durationOf(TransitionEdge.HOME_TO_LOCK))
    }
}
