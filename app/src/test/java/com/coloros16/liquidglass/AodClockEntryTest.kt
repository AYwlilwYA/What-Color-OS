package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.AodClockEntry
import com.coloros16.liquidglass.engine.SystemSceneMotion
import com.coloros16.liquidglass.engine.TransitionEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AodClockEntryTest {

    private val lockToAod = SystemSceneMotion.LOCK_TO_AOD_MS
    private val homeToAod = SystemSceneMotion.HOME_TO_AOD_MS

    @Test
    fun onlyEdgesEndingAtAodAreEntering() {
        assertTrue(AodClockEntry.entering(TransitionEdge.LOCK_TO_AOD))
        assertTrue(AodClockEntry.entering(TransitionEdge.HOME_TO_AOD))
        assertFalse(AodClockEntry.entering(TransitionEdge.AOD_TO_LOCK))
        assertFalse(AodClockEntry.entering(TransitionEdge.AOD_TO_HOME))
        assertFalse(AodClockEntry.entering(TransitionEdge.LOCK_TO_HOME))
        assertFalse(AodClockEntry.entering(TransitionEdge.HOME_TO_LOCK))
    }

    @Test
    fun progressSlowsDownWhenEdgeShorterThanClock() {
        assertEquals(0f, AodClockEntry.progress(0f, lockToAod), 0.0001f)
        assertEquals(0.25f, AodClockEntry.progress(0.5f, lockToAod), 0.0001f)
        assertEquals(0.5f, AodClockEntry.progress(1f, lockToAod), 0.0001f)
    }

    @Test
    fun progressClampsWhenEdgeLongerThanClock() {
        val longEdge = 2000L
        assertEquals(1f, AodClockEntry.progress(1f, longEdge), 0.0001f)
        assertEquals(1f, AodClockEntry.progress(0.75f, longEdge), 0.0001f)
        assertEquals(0.5f, AodClockEntry.progress(0.25f, longEdge), 0.0001f)
    }

    @Test
    fun progressHandlesNonPositiveEdgeDuration() {
        assertEquals(1f, AodClockEntry.progress(0.3f, 0L), 0.0001f)
        assertEquals(1f, AodClockEntry.progress(0.3f, -5L), 0.0001f)
    }

    @Test
    fun scaleRunsFromSystemStartToOne() {
        assertEquals(0.85f, AodClockEntry.scaleAt(0f), 0.0001f)
        assertEquals(1f, AodClockEntry.scaleAt(1f), 0.0001f)
    }

    @Test
    fun scaleIsMonotonicAndBounded() {
        var previous = 0f
        for (i in 0..40) {
            val s = AodClockEntry.scaleAt(i / 40f)
            assertTrue(s >= previous - 0.0001f)
            assertTrue(s in 0.85f..1f)
            previous = s
        }
    }

    @Test
    fun scaleClampsOutOfRangeProgress() {
        assertEquals(0.85f, AodClockEntry.scaleAt(-1f), 0.0001f)
        assertEquals(1f, AodClockEntry.scaleAt(3f), 0.0001f)
    }

    @Test
    fun tailIsClockDurationMinusEdgeDuration() {
        assertEquals(500L, AodClockEntry.tailMs(lockToAod))
        assertEquals(500L, AodClockEntry.tailMs(homeToAod))
        assertEquals(0L, AodClockEntry.tailMs(1000L))
        assertEquals(0L, AodClockEntry.tailMs(1500L))
    }

    @Test
    fun tailProgressStartsWhereEdgeEnded() {
        val atEdgeEnd = AodClockEntry.progress(1f, lockToAod)
        assertEquals(atEdgeEnd, AodClockEntry.tailProgress(0L, lockToAod), 0.0001f)
    }

    @Test
    fun tailProgressReachesOneExactlyAtTailEnd() {
        val tail = AodClockEntry.tailMs(lockToAod)
        assertEquals(1f, AodClockEntry.tailProgress(tail, lockToAod), 0.0001f)
        assertTrue(AodClockEntry.tailProgress(tail - 1L, lockToAod) < 1f)
    }

    @Test
    fun tailProgressClampsBeyondEnd() {
        assertEquals(1f, AodClockEntry.tailProgress(9999L, lockToAod), 0.0001f)
        assertEquals(
            AodClockEntry.progress(1f, lockToAod),
            AodClockEntry.tailProgress(-100L, lockToAod),
            0.0001f,
        )
    }

    @Test
    fun tailDoneOnlyAfterFullTail() {
        val tail = AodClockEntry.tailMs(lockToAod)
        assertFalse(AodClockEntry.tailDone(0L, lockToAod))
        assertFalse(AodClockEntry.tailDone(tail - 1L, lockToAod))
        assertTrue(AodClockEntry.tailDone(tail, lockToAod))
        assertTrue(AodClockEntry.tailDone(tail + 1L, lockToAod))
    }

    @Test
    fun noTailNeededWhenEdgeCoversClockDuration() {
        assertTrue(AodClockEntry.tailDone(0L, 1200L))
        assertEquals(1f, AodClockEntry.tailProgress(0L, 1200L), 0.0001f)
    }

    @Test
    fun edgeEndPlusTailEqualsFullClockScale() {
        val atEdgeEnd = AodClockEntry.scaleAt(AodClockEntry.progress(1f, lockToAod))
        assertTrue("边末不应已完成", atEdgeEnd < 1f)
        val atTailEnd = AodClockEntry.scaleAt(
            AodClockEntry.tailProgress(AodClockEntry.tailMs(lockToAod), lockToAod),
        )
        assertEquals(1f, atTailEnd, 0.0001f)
    }

    @Test
    fun tailScaleIsContinuousAcrossEdgeBoundary() {
        val before = AodClockEntry.scaleAt(AodClockEntry.progress(1f, lockToAod))
        val after = AodClockEntry.scaleAt(AodClockEntry.tailProgress(0L, lockToAod))
        assertEquals(before, after, 0.00001f)
    }

    @Test
    fun engineAndEntryAgreeOnScaleDuringEdge() {
        val layers = SystemSceneMotion.Layers()
        for (i in 0..10) {
            val t = i / 10f
            SystemSceneMotion.sample(TransitionEdge.LOCK_TO_AOD, t, layers)
            val expect = AodClockEntry.scaleAt(AodClockEntry.progress(t, lockToAod))
            assertEquals(expect, layers.aodClockScale, 0.0001f)
        }
    }

    @Test
    fun engineAndEntryAgreeOnHomeToAod() {
        val layers = SystemSceneMotion.Layers()
        for (i in 0..10) {
            val t = i / 10f
            SystemSceneMotion.sample(TransitionEdge.HOME_TO_AOD, t, layers)
            val expect = AodClockEntry.scaleAt(AodClockEntry.progress(t, homeToAod))
            assertEquals(expect, layers.aodClockScale, 0.0001f)
        }
    }
}