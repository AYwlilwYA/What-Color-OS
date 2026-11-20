package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SailLensMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LensSpinAbsoluteContractTest {

    @Test
    fun absoluteAngleIsNegativeLikeIncrementalSpin() {
        assertTrue(SailLensMotion.spinAt(1f) < 0f)
        assertTrue(SailLensMotion.spinAt(2.5f) < 0f)
    }

    @Test
    fun absoluteMatchesIncrementalIntegration() {
        var acc = 0f
        val step = 0.05f
        repeat(40) { acc = SailLensMotion.spin(acc, step) }
        assertEquals(acc, SailLensMotion.spinAt(40 * step), 0.01f)
    }

    @Test
    fun absoluteWrapsIntoMinusThreeSixtyToZero() {
        for (s in listOf(0f, 1f, 9f, 10f, 25f, 100f, 1000f)) {
            val a = SailLensMotion.spinAt(s)
            assertTrue("s=$s a=$a", a <= 0f && a > -360f)
        }
    }

    @Test
    fun fullTurnReturnsToZero() {
        assertEquals(0f, SailLensMotion.spinAt(9f), 0.001f)
        assertEquals(0f, SailLensMotion.spinAt(18f), 0.001f)
    }

    @Test
    fun speedScalesAngle() {
        assertEquals(
            SailLensMotion.spinAt(1f, 1f) * 2f,
            SailLensMotion.spinAt(1f, 2f),
            0.01f,
        )
    }

    @Test
    fun zeroSpeedOrZeroTimeFreezes() {
        assertEquals(0f, SailLensMotion.spinAt(5f, 0f))
        assertEquals(0f, SailLensMotion.spinAt(0f, 1f))
        assertEquals(0f, SailLensMotion.spinAt(-3f, 1f))
    }

    @Test
    fun oldPositiveConventionDiffersFromNew() {
        val seconds = 1f
        val oldPositive = (seconds * SailLensMotion.SPIN_DEG_PER_SEC) % 360f
        val new = SailLensMotion.spinAt(seconds)
        assertTrue(abs(oldPositive - new) > 1f)
        assertEquals(-oldPositive, new, 0.001f)
    }
}