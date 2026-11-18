package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.edit.EditLayoutSolver
import com.coloros16.liquidglass.engine.edit.WidgetSize
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditLayoutSolverCellTest {

    private val grid = EditLayoutSolver.Grid()
    private val rect = EditLayoutSolver.Rect()
    private val screenW = 1080f
    private val screenH = 2376f
    private val top = EditLayoutSolver.WIDGET_GRID_TOP

    private fun solved(): EditLayoutSolver.Grid {
        grid.solve(screenW, screenH, top)
        return grid
    }

    @Test
    fun configRoundTripIsStableForEveryCell() {
        for (col in 0 until EditLayoutSolver.GRID_COLUMNS) {
            for (row in 0 until EditLayoutSolver.GRID_ROWS) {
                val cfg = EditLayoutSolver.configOf(col, row)
                val back = EditLayoutSolver.cellOf(cfg[0], cfg[1], top, WidgetSize.SPAN_1X1)
                assertArrayEquals("col=$col row=$row", intArrayOf(col, row), back)
            }
        }
    }

    @Test
    fun visualReverseMatchesForwardForEveryCell() {
        val g = solved()
        for (col in 0 until EditLayoutSolver.GRID_COLUMNS) {
            for (row in 0 until EditLayoutSolver.GRID_ROWS) {
                val vx = g.originX + col * g.cellPx + g.cellPx / 2f
                val vy = g.originY + row * g.cellPx + g.cellPx / 2f
                val got = EditLayoutSolver.cellFromVisual(vx, vy, WidgetSize.SPAN_1X1, g)
                assertNotNull(got)
                assertArrayEquals("col=$col row=$row", intArrayOf(col, row), got!!)
            }
        }
    }

    @Test
    fun configSpaceIsNotVisualSpace() {
        val g = solved()
        val cfg = EditLayoutSolver.configOf(1, 1)
        val visualX = g.originX + 1 * g.cellPx + g.cellPx / 2f
        assertTrue(kotlin.math.abs(visualX - cfg[0] * screenW) > 1f)
    }

    @Test
    fun oneCellDragChangesConfigImmediately() {
        val g = solved()
        val startCol = 3
        val startRow = 2
        val startVX = g.originX + startCol * g.cellPx + g.cellPx / 2f
        val startVY = g.originY + startRow * g.cellPx + g.cellPx / 2f
        val moved = EditLayoutSolver.cellFromVisual(
            startVX, startVY - g.cellPx, WidgetSize.SPAN_1X1, g,
        )!!
        assertEquals(startCol, moved[0])
        assertEquals(startRow - 1, moved[1])
    }

    @Test
    fun diagonalDragMovesBothAxes() {
        val g = solved()
        val vx = g.originX + 1 * g.cellPx + g.cellPx / 2f
        val vy = g.originY + 1 * g.cellPx + g.cellPx / 2f
        val moved = EditLayoutSolver.cellFromVisual(
            vx + g.cellPx, vy + g.cellPx, WidgetSize.SPAN_1X1, g,
        )!!
        assertArrayEquals(intArrayOf(2, 2), moved)
    }

    @Test
    fun visualClampsAtGridEdges() {
        val g = solved()
        val low = EditLayoutSolver.cellFromVisual(-9999f, -9999f, WidgetSize.SPAN_1X1, g)!!
        assertArrayEquals(intArrayOf(0, 0), low)
        val high = EditLayoutSolver.cellFromVisual(99999f, 99999f, WidgetSize.SPAN_1X1, g)!!
        assertArrayEquals(
            intArrayOf(EditLayoutSolver.GRID_COLUMNS - 1, EditLayoutSolver.GRID_ROWS - 1),
            high,
        )
    }

    @Test
    fun visualClampsRespectSpan() {
        val g = solved()
        val wide = EditLayoutSolver.cellFromVisual(99999f, 99999f, WidgetSize.SPAN_2X2, g)!!
        assertArrayEquals(
            intArrayOf(EditLayoutSolver.GRID_COLUMNS - 2, EditLayoutSolver.GRID_ROWS - 2),
            wide,
        )
        val tall = EditLayoutSolver.cellFromVisual(99999f, 99999f, WidgetSize.SPAN_1X4, g)!!
        assertArrayEquals(intArrayOf(EditLayoutSolver.GRID_COLUMNS - 1, 0), tall)
    }

    @Test
    fun unsolvedGridReturnsNull() {
        val fresh = EditLayoutSolver.Grid()
        assertNull(EditLayoutSolver.cellFromVisual(100f, 100f, WidgetSize.SPAN_1X1, fresh))
        fresh.solve(0f, screenH, top)
        assertNull(EditLayoutSolver.cellFromVisual(100f, 100f, WidgetSize.SPAN_1X1, fresh))
    }

    @Test
    fun visualToConfigToRectLandsOnSameCell() {
        val g = solved()
        val vx = g.originX + 2 * g.cellPx + g.cellPx / 2f
        val vy = g.originY + 1 * g.cellPx + g.cellPx / 2f
        val cell = EditLayoutSolver.cellFromVisual(vx, vy, WidgetSize.SPAN_1X1, g)!!
        val cfg = EditLayoutSolver.configOf(cell[0], cell[1])
        val w = com.coloros16.liquidglass.engine.edit.LockWidget("weather", true, cfg[0], cfg[1], WidgetSize.SPAN_1X1)
        assertTrue(EditLayoutSolver.widgetRect(w, screenW, screenH, top, g, rect))
        assertEquals(g.originX + 2 * g.cellPx, rect.left, 0.01f)
        assertEquals(g.originY + 1 * g.cellPx, rect.top, 0.01f)
    }

    @Test
    fun configOfProducesNormalizedValues() {
        val cfg = EditLayoutSolver.configOf(3, 2)
        assertEquals(0.75f, cfg[0], 0.0001f)
        assertEquals(0.5f, cfg[1], 0.0001f)
    }
}