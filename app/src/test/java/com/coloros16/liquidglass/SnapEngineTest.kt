package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.edit.EditAxis
import com.coloros16.liquidglass.engine.edit.EditSurface
import com.coloros16.liquidglass.engine.edit.GuideKind
import com.coloros16.liquidglass.engine.edit.PreviewSegment
import com.coloros16.liquidglass.engine.edit.PreviewShelf
import com.coloros16.liquidglass.engine.edit.SnapEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapEngineTest {

    private val guides = SnapEngine.guides(EditSurface.LOCK)

    @Test
    fun snapsToCenterLine() {
        val result = SnapEngine.snap(0.49f, 0.73f, guides)
        assertEquals(0.5f, result.x, 1e-6f)
        assertEquals(GuideKind.CENTER_X, result.guideX)
        assertEquals(0.5f, result.guideXPosition, 1e-6f)
    }

    @Test
    fun snapsToThirdLines() {
        val left = SnapEngine.snap(0.34f, 0.7f, guides)
        assertEquals(GuideKind.THIRD_LEFT, left.guideX)
        val bottom = SnapEngine.snap(0.7f, 0.66f, guides)
        assertEquals(GuideKind.THIRD_BOTTOM, bottom.guideY)
    }

    @Test
    fun keepsPositionWhenFarFromGuides() {
        val result = SnapEngine.snap(0.44f, 0.61f, guides)
        assertEquals(0.44f, result.x, 1e-6f)
        assertEquals(0.61f, result.y, 1e-6f)
        assertNull(result.guideX)
        assertNull(result.guideY)
    }

    @Test
    fun aodHasVerticalGuidesToo() {
        val aodGuides = SnapEngine.guides(EditSurface.AOD)
        assertTrue(aodGuides.any { it.axis == com.coloros16.liquidglass.engine.edit.SnapAxis.Y })

        val result = SnapEngine.snap(0.5f, 0.5f, aodGuides, EditSurface.AOD.axis)
        assertEquals(0.5f, result.x, 1e-6f)
        assertEquals(GuideKind.CENTER_X, result.guideX)
        assertEquals(GuideKind.CENTER_Y, result.guideY)
        assertEquals(0.5f, result.y, 1e-6f)
    }

    /** 阳性对照：HORIZONTAL_ONLY 约束本身仍生效（只是息屏不再用它）。 */
    @Test
    fun horizontalOnlyAxisStillIgnoresVerticalGuides() {
        val guides = SnapEngine.guides(EditSurface.LOCK)
        val result = SnapEngine.snap(0.5f, 0.5f, guides, EditAxis.HORIZONTAL_ONLY)
        assertNull(result.guideY)
        assertEquals(-1f, result.guideYPosition, 1e-6f)
    }

    @Test
    fun positionsAreClamped() {
        val result = SnapEngine.snap(-0.5f, 1.8f, guides)
        assertEquals(0f, result.x, 1e-6f)
        assertEquals(1f, result.y, 1e-6f)
    }

    @Test
    fun previewShelfWrapsAround() {
        assertEquals(PreviewSegment.LOCK, PreviewShelf.next(PreviewSegment.AOD, 1))
        assertEquals(PreviewSegment.AOD, PreviewShelf.next(PreviewSegment.HOME, 1))
        assertEquals(PreviewSegment.HOME, PreviewShelf.next(PreviewSegment.AOD, -1))
        assertEquals(3, PreviewShelf.segments.size)
        assertEquals(PreviewSegment.AOD, PreviewShelf.segments.first())
        assertNotNull(PreviewShelf.segments)
        assertTrue(PreviewShelf.BUTTON_ALPHA in 0f..1f)
    }
}
