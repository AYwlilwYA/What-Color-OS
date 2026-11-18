package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SceneTimeline
import com.coloros16.liquidglass.engine.SystemSceneMotion
import com.coloros16.liquidglass.engine.TransitionEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineClockEntryTest {

    private val layers = SystemSceneMotion.Layers()

    @Test
    fun timelineOnlyProducesNonEnteringEdges() {
        for (i in 0..100) {
            val q = i / 100f
            val edge = SceneTimeline.edgeAt(q)
            assertTrue(edge.name, edge != TransitionEdge.LOCK_TO_AOD)
            assertTrue(edge.name, edge != TransitionEdge.HOME_TO_AOD)
        }
    }

    @Test
    fun clockEntryFactorIsUnityAcrossWholeTimeline() {
        for (i in 0..100) {
            SystemSceneMotion.sampleTimeline(i / 100f, layers)
            assertEquals(1f, layers.aodClockScale, 0.0001f)
        }
    }

    @Test
    fun aodAnchorHasFullClockScale() {
        SystemSceneMotion.sampleTimeline(SceneTimeline.AOD, layers)
        assertEquals(1f, layers.aodClockScale, 0.0001f)
    }

    @Test
    fun lockAnchorHasFullClockScale() {
        SystemSceneMotion.sampleTimeline(SceneTimeline.LOCK, layers)
        assertEquals(1f, layers.aodClockScale, 0.0001f)
    }

    @Test
    fun homeAnchorHasFullClockScale() {
        SystemSceneMotion.sampleTimeline(SceneTimeline.HOME, layers)
        assertEquals(1f, layers.aodClockScale, 0.0001f)
    }

    @Test
    fun clampsOutOfRangeTimeline() {
        SystemSceneMotion.sampleTimeline(-1f, layers)
        assertEquals(1f, layers.aodClockScale, 0.0001f)
        SystemSceneMotion.sampleTimeline(2f, layers)
        assertEquals(1f, layers.aodClockScale, 0.0001f)
    }

    @Test
    fun enteringEdgesDoCarryEntryFactorWhenSampledDirectly() {
        SystemSceneMotion.sample(TransitionEdge.LOCK_TO_AOD, 0f, layers, direct = true)
        assertEquals(0.85f, layers.aodClockScale, 0.0001f)
        SystemSceneMotion.sample(TransitionEdge.LOCK_TO_AOD, 1f, layers, direct = true)
        assertEquals(1f, layers.aodClockScale, 0.0001f)
    }

    @Test
    fun scrubOfEnteringEdgeIsLinearInEntryProgress() {
        SystemSceneMotion.sample(TransitionEdge.LOCK_TO_AOD, 0.5f, layers, direct = true)
        val expect = com.coloros16.liquidglass.engine.AodClockEntry.scaleAt(0.5f)
        assertEquals(expect, layers.aodClockScale, 0.0001f)
    }

    @Test
    fun timelineWallpaperStillFollowsFinger() {
        SystemSceneMotion.sampleTimeline(SceneTimeline.AOD, layers)
        assertEquals(1.0f, layers.wallpaperScale, 0.0001f)
        SystemSceneMotion.sampleTimeline(SceneTimeline.LOCK, layers)
        assertEquals(1.1f, layers.wallpaperScale, 0.0001f)
    }
}