package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.AodDrift
import com.coloros16.liquidglass.engine.AodDriftSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AodDriftScheduleTest {

    @Test
    fun cycleIsSixSteps() {
        assertEquals(6, AodDriftSchedule.STEP_COUNT)
        assertEquals(AodDrift.STEP_MS * 6, AodDriftSchedule.CYCLE_MS)
    }

    @Test
    fun glidePhaseSchedulesGlideEnd() {
        val now = 1000L
        assertTrue(AodDriftSchedule.gliding(now))
        assertEquals(AodDrift.GLIDE_MS - now, AodDriftSchedule.delayToNext(now))
        assertEquals(AodDrift.GLIDE_MS, AodDriftSchedule.nextChangeAt(now))
    }

    @Test
    fun holdPhaseSchedulesNextStep() {
        val now = AodDrift.GLIDE_MS + 1000L
        assertFalse(AodDriftSchedule.gliding(now))
        assertEquals(AodDrift.STEP_MS - now, AodDriftSchedule.delayToNext(now))
    }

    @Test
    fun delayIsAlwaysPositive() {
        var t = 0L
        for (i in 0 until 200) {
            val d = AodDriftSchedule.delayToNext(t)
            assertTrue("t=$t delay=$d", d > 0L)
            assertTrue("t=$t delay=$d", d <= AodDrift.STEP_MS)
            t = AodDriftSchedule.nextChangeAt(t)
        }
    }

    @Test
    fun boundaryAtGlideEndIsHold() {
        assertFalse(AodDriftSchedule.gliding(AodDrift.GLIDE_MS))
        assertEquals(0L, AodDriftSchedule.glideRemainMs(AodDrift.GLIDE_MS))
        assertEquals(AodDrift.STEP_MS - AodDrift.GLIDE_MS, AodDriftSchedule.delayToNext(AodDrift.GLIDE_MS))
    }

    @Test
    fun boundaryAtStepEndIsNextGlide() {
        val now = AodDrift.STEP_MS
        assertTrue(AodDriftSchedule.gliding(now))
        assertEquals(AodDrift.GLIDE_MS, AodDriftSchedule.delayToNext(now))
    }

    @Test
    fun glideRemainShrinks() {
        assertEquals(AodDrift.GLIDE_MS, AodDriftSchedule.glideRemainMs(0L))
        assertEquals(AodDrift.GLIDE_MS - 1000L, AodDriftSchedule.glideRemainMs(1000L))
        assertEquals(1L, AodDriftSchedule.glideRemainMs(AodDrift.GLIDE_MS - 1L))
    }

    @Test
    fun scheduleCoversWholeCycleWithoutGap() {
        var t = 0L
        var guard = 0
        while (t < AodDriftSchedule.CYCLE_MS && guard < 100) {
            t = AodDriftSchedule.nextChangeAt(t)
            guard++
        }
        assertTrue(t >= AodDriftSchedule.CYCLE_MS)
        assertEquals(12, guard)
    }

    @Test
    fun valueStaysInsideAmplitude() {
        var t = 0L
        for (i in 0 until 500) {
            val y = AodDrift.offsetY(t)
            assertTrue("t=$t y=$y", y >= -AodDrift.AMPLITUDE - 0.0001f)
            assertTrue("t=$t y=$y", y <= AodDrift.AMPLITUDE + 0.0001f)
            t += 997L
        }
    }

    @Test
    fun valueIsConstantDuringHold() {
        val a = AodDrift.offsetY(AodDrift.GLIDE_MS + 100L)
        val b = AodDrift.offsetY(AodDrift.STEP_MS - 100L)
        assertEquals(a, b, 0.000001f)
    }

    @Test
    fun valueChangesDuringGlide() {
        val base = AodDrift.STEP_MS
        val a = AodDrift.offsetY(base)
        val b = AodDrift.offsetY(base + AodDrift.GLIDE_MS / 2)
        assertTrue(kotlin.math.abs(a - b) > 0.00001f)
    }

    @Test
    fun firstStepHasNoMovementBecauseAdjacentStepsAreEqual() {
        for (t in longArrayOf(0L, 1000L, 2000L, AodDrift.GLIDE_MS, AodDrift.STEP_MS - 1L)) {
            assertEquals(0f, AodDrift.offsetY(t), 0.000001f)
        }
    }

    @Test
    fun negativeTimeIsHandled() {
        val d = AodDriftSchedule.delayToNext(-5000L)
        assertTrue(d > 0L)
        val y = AodDrift.offsetY(-5000L)
        assertTrue(y >= -AodDrift.AMPLITUDE - 0.0001f)
        assertTrue(y <= AodDrift.AMPLITUDE + 0.0001f)
    }
}