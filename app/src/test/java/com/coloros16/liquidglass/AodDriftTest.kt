package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.AodDrift
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AodDriftTest {

    @Test
    fun offsetStaysWithinAmplitude() {
        val cycle = AodDrift.STEP_MS * 6
        var t = 0L
        while (t <= cycle) {
            val v = AodDrift.offsetY(t)
            assertTrue("offset $v out of range at $t", kotlin.math.abs(v) <= AodDrift.AMPLITUDE + 1e-6f)
            t += 500L
        }
    }

    @Test
    fun glideMovesMonotonicallyBetweenSteps() {
        val stepStart = AodDrift.STEP_MS
        val from = AodDrift.offsetY(stepStart - 1L)
        var last = from
        var moved = false
        var phase = 0L
        while (phase <= AodDrift.GLIDE_MS) {
            val v = AodDrift.offsetY(stepStart + phase)
            assertTrue("offset should not go backwards inside a glide", v <= last + 1e-6f)
            if (kotlin.math.abs(v - from) > 1e-4f) moved = true
            last = v
            phase += 100L
        }
        assertTrue("glide produced no movement", moved)
    }

    @Test
    fun cycleWrapsWithoutJump() {
        val cycle = AodDrift.STEP_MS * 6
        val endValue = AodDrift.offsetY(cycle - 1L)
        val startValue = AodDrift.offsetY(0L)
        assertEquals(startValue, endValue, 1e-3f)
    }
}
