package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.AodDrift
import com.coloros16.liquidglass.engine.ClockPlacementMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockPlacementMathContractTest {

    private val screenW = 1080f
    private val screenH = 2376f

    @Test
    fun centerIsParentPlusLocalCenterPlusTranslation() {
        val c = ClockPlacementMath.screenCenter(
            viewLeft = 100f, viewTop = 900f, viewWidth = 800f, viewHeight = 100f,
            parentScreenX = 0f, parentScreenY = 40f,
            translationX = 40f, translationY = -6f,
        )
        assertEquals(540f, c[0], 0.001f)
        assertEquals(984f, c[1], 0.001f)
    }

    @Test
    fun zeroTranslationLeavesViewCenterUntouched() {
        val c = ClockPlacementMath.screenCenter(
            100f, 900f, 800f, 100f, 0f, 0f, 0f, 0f,
        )
        assertEquals(500f, c[0], 0.001f)
        assertEquals(950f, c[1], 0.001f)
    }

    @Test
    fun targetCenterMatchesAnchorTimesScreen() {
        val t = ClockPlacementMath.targetCenter(0.5f, 0.4366f, screenW, screenH)
        assertEquals(540f, t[0], 0.001f)
        assertEquals(1037.36f, t[1], 0.05f)
    }

    @Test
    fun impliedAnchorYRoundTrips() {
        val y = 974.5f
        val implied = ClockPlacementMath.impliedAnchorY(y, screenH)
        assertEquals(y, implied * screenH, 0.001f)
    }

    @Test
    fun impliedAnchorYGuardsZeroHeight() {
        assertEquals(0f, ClockPlacementMath.impliedAnchorY(100f, 0f), 0f)
    }

    @Test
    fun driftMaxIsAmplitudeTimesHeight() {
        assertEquals(52.272f, ClockPlacementMath.driftMaxPx(screenH), 0.001f)
    }

    @Test
    fun driftCannotExplainSixtyThreePixelGap() {
        val observed = 974.5f
        val expected = ClockPlacementMath.targetCenter(0.5f, 0.4366f, screenW, screenH)[1]
        val gap = observed - expected
        assertTrue("gap=$gap", gap < -60f)
        assertFalse(ClockPlacementMath.driftCanExplain(gap, screenH))
    }

    @Test
    fun driftCanExplainGapWithinAmplitude() {
        assertTrue(ClockPlacementMath.driftCanExplain(-52f, screenH))
        assertTrue(ClockPlacementMath.driftCanExplain(52f, screenH))
        assertFalse(ClockPlacementMath.driftCanExplain(-53f, screenH))
    }

    @Test
    fun driftBoundsHoldAcrossWholeCycle() {
        val max = ClockPlacementMath.driftMaxPx(screenH)
        var t = 0L
        while (t < AodDrift.STEP_MS * 6) {
            val px = AodDrift.offsetY(t) * screenH
            assertTrue("t=$t px=$px", kotlin.math.abs(px) <= max + 0.001f)
            t += 500L
        }
    }

    @Test
    fun glyphBoxCenterMayDifferFromViewCenter() {
        val off = ClockPlacementMath.glyphOffsetInBox(
            boxLeft = 396f, boxTop = 954f, boxRight = 696f, boxBottom = 995f,
            viewCenterX = 540f, viewCenterY = 1037.36f,
        )
        assertEquals(6f, off[0], 0.001f)
        assertEquals(-62.86f, off[1], 0.05f)
    }

    @Test
    fun stretchDoesNotMoveCenterWhenPivotIsViewCenter() {
        val base = ClockPlacementMath.screenCenter(100f, 900f, 800f, 100f, 0f, 0f, 40f, -6f)
        val stretched = ClockPlacementMath.screenCenter(100f, 900f, 800f, 100f, 0f, 0f, 40f, -6f)
        assertEquals(base[0], stretched[0], 0f)
        assertEquals(base[1], stretched[1], 0f)
    }
}