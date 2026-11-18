package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SailLensMotion
import com.coloros16.liquidglass.engine.effect.AodMotion
import com.coloros16.liquidglass.engine.effect.AodMotionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AodRotationPathTest {

    private val state = AodMotionState()

    @Test
    fun absoluteRotationIsPositiveAndTimeBased() {
        AodMotion.evaluate(2000L, true, 60f, 12f, 6f, state)
        assertEquals(24f, state.rotationDeg, 0.001f)
    }

    @Test
    fun incrementalSpinIsNegativeAtSameSpeed() {
        val ratio = 12f / SailLensMotion.SPIN_DEG_PER_SEC
        var angle = 0f
        repeat(20) { angle = SailLensMotion.spin(angle, 0.1f, ratio) }
        assertEquals(-24f, angle, 0.001f)
    }

    @Test
    fun twoPathsHaveSameMagnitudeOppositeSign() {
        AodMotion.evaluate(2000L, true, 60f, 12f, 6f, state)
        val ratio = 12f / SailLensMotion.SPIN_DEG_PER_SEC
        var angle = 0f
        repeat(20) { angle = SailLensMotion.spin(angle, 0.1f, ratio) }
        assertEquals(state.rotationDeg, -angle, 0.01f)
    }

    @Test
    fun spinFreezesWhenSpeedNonPositive() {
        val before = 37f
        assertEquals(before, SailLensMotion.spin(before, 1f, 0f), 0.0001f)
        assertEquals(before, SailLensMotion.spin(before, 1f, -2f), 0.0001f)
    }

    @Test
    fun spinWrapsOnlyPastNegativeFullTurn() {
        val a = SailLensMotion.spin(-350f, 1f, 1f)
        assertTrue("超过 -360 才回绕", a > -360f)
        assertEquals(-350f - SailLensMotion.SPIN_DEG_PER_SEC + 360f, a, 0.001f)
    }

    @Test
    fun absoluteRotationWrapsAt360() {
        AodMotion.evaluate(40_000L, true, 60f, 12f, 6f, state)
        assertTrue(state.rotationDeg in 0f..360f)
        assertEquals((12f * 40f) % 360f, state.rotationDeg, 0.001f)
    }

    @Test
    fun dynamicGateFreezesAllThreeChannels() {
        AodMotion.evaluate(5000L, false, 60f, 12f, 6f, state)
        assertEquals(0f, state.rotationDeg, 0.0001f)
        assertEquals(0f, state.colorMix, 0.0001f)
        assertEquals(0f, state.photoTiltDeg, 0.0001f)
        assertTrue(!state.active)
    }

    @Test
    fun spinIsPathIndependentForSameTotalTime() {
        val ratio = 0.3f
        var a = 0f
        repeat(100) { a = SailLensMotion.spin(a, 0.01f, ratio) }
        var b = 0f
        repeat(10) { b = SailLensMotion.spin(b, 0.1f, ratio) }
        assertEquals(a, b, 0.01f)
    }
}