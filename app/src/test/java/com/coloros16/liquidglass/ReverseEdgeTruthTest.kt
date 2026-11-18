package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SystemSceneMotion
import com.coloros16.liquidglass.engine.TransitionEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReverseEdgeTruthTest {

    private val layers = SystemSceneMotion.Layers()

    @Test
    fun lockExitMatchesMiddleHighAnimLevel() {
        assertEquals(200f, SystemSceneMotion.LOCK_EXIT_MS, 0.0001f)
        assertEquals(300f, SystemSceneMotion.LOCK_EXIT_LOW_MS, 0.0001f)
    }

    @Test
    fun lockAlphaFullyGoneAtExitWindowOnLockToAod() {
        val ratio = SystemSceneMotion.LOCK_EXIT_MS / SystemSceneMotion.LOCK_TO_AOD_MS
        SystemSceneMotion.sample(TransitionEdge.LOCK_TO_AOD, ratio, layers)
        assertEquals(0f, layers.lockAlpha, 0.0001f)
    }

    @Test
    fun lockAlphaStillVisibleBeforeExitWindow() {
        val ratio = SystemSceneMotion.LOCK_EXIT_MS / SystemSceneMotion.LOCK_TO_AOD_MS
        SystemSceneMotion.sample(TransitionEdge.LOCK_TO_AOD, ratio * 0.5f, layers)
        assertTrue(layers.lockAlpha > 0f)
        assertTrue(layers.lockAlpha < 1f)
    }

    @Test
    fun homeAlphaExitsWithinWindowOnHomeToLock() {
        val ratio = SystemSceneMotion.LOCK_EXIT_MS / SystemSceneMotion.HOME_TO_LOCK_MS
        SystemSceneMotion.sample(TransitionEdge.HOME_TO_LOCK, ratio, layers)
        assertEquals(0f, layers.homeAlpha, 0.0001f)
    }

    @Test
    fun lockAlphaExitsWithinWindowOnLockToHome() {
        val ratio = SystemSceneMotion.LOCK_EXIT_MS / SystemSceneMotion.LOCK_TO_HOME_MS
        SystemSceneMotion.sample(TransitionEdge.LOCK_TO_HOME, ratio, layers)
        assertEquals(0f, layers.lockAlpha, 0.0001f)
    }

    @Test
    fun aodClockStartsAtSystemScale() {
        SystemSceneMotion.sample(TransitionEdge.LOCK_TO_AOD, 0f, layers)
        assertEquals(0.85f, layers.aodClockScale, 0.0001f)
        SystemSceneMotion.sample(TransitionEdge.HOME_TO_AOD, 0f, layers)
        assertEquals(0.85f, layers.aodClockScale, 0.0001f)
    }

    @Test
    fun aodClockStillGrowingAtEdgeEndBecauseItOutlivesTheEdge() {
        SystemSceneMotion.sample(TransitionEdge.LOCK_TO_AOD, 1f, layers)
        assertTrue("时钟 1000ms 超出灭屏边 500ms，边末不应已完成", layers.aodClockScale < 1f)
        assertTrue(layers.aodClockScale > 0.95f)
        SystemSceneMotion.sample(TransitionEdge.HOME_TO_AOD, 1f, layers)
        assertTrue(layers.aodClockScale < 1f)
        assertTrue(layers.aodClockScale > 0.95f)
    }

    @Test
    fun aodClockReachesOneOnlyAfterFullClockDuration() {
        val need = SystemSceneMotion.AOD_CLOCK_IN_MS / SystemSceneMotion.LOCK_TO_AOD_MS
        SystemSceneMotion.sample(TransitionEdge.LOCK_TO_AOD, 1f, layers)
        val atEdgeEnd = layers.aodClockScale
        val eased = SystemSceneMotion.AOD_CLOCK.map(1f / need)
        assertEquals(0.85f + 0.15f * eased, atEdgeEnd, 0.0001f)
    }

    @Test
    fun aodClockIsClampedWhenEdgeShorterThanClockDuration() {
        val half = SystemSceneMotion.AOD_CLOCK_IN_MS / SystemSceneMotion.LOCK_TO_AOD_MS
        assertTrue("时钟 1000ms 应长于灭屏边 500ms", half > 1f)
        SystemSceneMotion.sample(TransitionEdge.LOCK_TO_AOD, 0.5f, layers)
        assertTrue(layers.aodClockScale <= 1f)
        assertTrue(layers.aodClockScale >= 0.85f)
    }

    @Test
    fun aodClockIsMonotonicAcrossEdge() {
        var previous = 0f
        for (i in 0..20) {
            SystemSceneMotion.sample(TransitionEdge.LOCK_TO_AOD, i / 20f, layers)
            assertTrue(layers.aodClockScale >= previous - 0.0001f)
            previous = layers.aodClockScale
        }
        assertTrue(previous > 0.95f)
        assertTrue(previous < 1f)
    }

    @Test
    fun scrubBypassesClockDurationScaling() {
        SystemSceneMotion.sample(TransitionEdge.LOCK_TO_AOD, 0.5f, layers, direct = true)
        val eased = SystemSceneMotion.AOD_CLOCK.map(0.5f)
        assertEquals(0.85f + 0.15f * eased, layers.aodClockScale, 0.0001f)
        SystemSceneMotion.sample(TransitionEdge.LOCK_TO_AOD, 1f, layers, direct = true)
        assertEquals(1f, layers.aodClockScale, 0.0001f)
    }

    @Test
    fun aodClockUntouchedOnEdgesWithoutAodArrival() {
        for (edge in listOf(
            TransitionEdge.AOD_TO_LOCK,
            TransitionEdge.LOCK_TO_HOME,
            TransitionEdge.HOME_TO_LOCK,
            TransitionEdge.AOD_TO_HOME,
        )) {
            SystemSceneMotion.sample(edge, 0.5f, layers)
            assertEquals(edge.name, 1f, layers.aodClockScale, 0.0001f)
        }
    }

    @Test
    fun reverseEdgeDurationsMatchSystemTruth() {
        assertEquals(500L, SystemSceneMotion.durationOf(TransitionEdge.LOCK_TO_AOD))
        assertEquals(600L, SystemSceneMotion.durationOf(TransitionEdge.HOME_TO_LOCK))
        assertEquals(500L, SystemSceneMotion.durationOf(TransitionEdge.HOME_TO_AOD))
        assertEquals(600L, SystemSceneMotion.durationOf(TransitionEdge.AOD_TO_LOCK))
    }

    @Test
    fun lockToAodWallpaperShrinksBackToOne() {
        SystemSceneMotion.sample(TransitionEdge.LOCK_TO_AOD, 0f, layers)
        assertEquals(1.1f, layers.wallpaperScale, 0.0001f)
        SystemSceneMotion.sample(TransitionEdge.LOCK_TO_AOD, 1f, layers)
        assertEquals(1.0f, layers.wallpaperScale, 0.0001f)
    }

    @Test
    fun homeToAodWallpaperStaysAtOne() {
        for (i in 0..10) {
            SystemSceneMotion.sample(TransitionEdge.HOME_TO_AOD, i / 10f, layers)
            assertEquals(1.0f, layers.wallpaperScale, 0.0001f)
        }
    }
}