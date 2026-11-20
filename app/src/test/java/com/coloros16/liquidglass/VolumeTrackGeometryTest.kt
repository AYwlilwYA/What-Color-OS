package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.VolumeTrackGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeTrackGeometryTest {

    private fun base() = VolumeTrackGeometry.Input(
        viewWidth = 200,
        viewHeight = 600,
        paddingStart = 20,
        paddingEnd = 20,
        paddingTop = 10,
        paddingBottom = 10,
        curPaddingVertical = 33f,
        curBackgroundRadius = 24f,
        curBackgroundWidth = 96f,
        widthDeformed = 0f,
        heightTopDeformedUp = 0f,
        heightTopDeformedDown = 0f,
        heightBottomDeformedUp = 0f,
        heightBottomDeformedDown = 0f,
    )

    @Test
    fun centerMatchesSystemFormula() {
        val i = base()
        assertEquals(100f, VolumeTrackGeometry.centerX(i), 0f)
    }

    @Test
    fun normalTrackRect() {
        val t = VolumeTrackGeometry.track(base())
        assertEquals(52f, t.left, 0f)
        assertEquals(148f, t.right, 0f)
        assertEquals(19f, t.top, 0f)
        assertEquals(581f, t.bottom, 0f)
        assertEquals(24f, t.cornerRadius, 0f)
        assertTrue(t.isValid())
    }

    @Test
    fun thickTrackNarrowsAndShortens() {
        val normal = VolumeTrackGeometry.track(base())
        val thick = VolumeTrackGeometry.track(
            base().copy(
                curPaddingVertical = 66f,
                curBackgroundWidth = 48f,
                curBackgroundRadius = 12f,
            ),
        )
        assertTrue(thick.width < normal.width)
        assertTrue(thick.height < normal.height)
        assertEquals(76f, thick.left, 0f)
        assertEquals(124f, thick.right, 0f)
        assertEquals(64f, thick.top, 0f)
        assertEquals(536f, thick.bottom, 0f)
        assertEquals(12f, thick.cornerRadius, 0f)
    }

    @Test
    fun deformationShiftsEdges() {
        val t = VolumeTrackGeometry.track(
            base().copy(
                widthDeformed = 6f,
                heightTopDeformedUp = 8f,
                heightBottomDeformedDown = 5f,
            ),
        )
        assertEquals(58f, t.left, 0f)
        assertEquals(142f, t.right, 0f)
        assertEquals(11f, t.top, 0f)
        assertEquals(586f, t.bottom, 0f)
    }

    @Test
    fun zeroWidthIsInvalid() {
        val t = VolumeTrackGeometry.track(base().copy(curBackgroundWidth = 0f))
        assertFalse(t.isValid())
    }

    @Test
    fun trackStaysInsideViewHorizontally() {
        val i = base()
        val t = VolumeTrackGeometry.track(i)
        assertTrue(t.left >= 0f)
        assertTrue(t.right <= i.viewWidth.toFloat())
    }
}
