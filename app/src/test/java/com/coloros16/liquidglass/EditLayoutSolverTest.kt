package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.edit.EditLayoutSolver
import com.coloros16.liquidglass.engine.edit.EditSurface
import com.coloros16.liquidglass.engine.edit.LockLayoutSettings
import com.coloros16.liquidglass.engine.edit.PillPlacement
import com.coloros16.liquidglass.engine.edit.PillType
import com.coloros16.liquidglass.engine.edit.WidgetCatalog
import com.coloros16.liquidglass.engine.edit.WidgetSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditLayoutSolverTest {

    private val grid = EditLayoutSolver.Grid()
    private val rect = EditLayoutSolver.Rect()
    private val other = EditLayoutSolver.Rect()

    private val screenW = 1080f
    private val screenH = 2376f

    @Test
    fun gridSolvesCellAndInset() {
        grid.solve(screenW, screenH, 0.3f)
        val inset = screenW * EditLayoutSolver.GRID_INSET_FRACTION
        val usable = screenW - inset * 2f
        assertEquals(usable / 4f, grid.cellPx, 0.01f)
        assertEquals(inset, grid.originX, 0.01f)
        assertEquals(screenH * 0.3f, grid.originY, 0.01f)
        assertEquals(grid.cellPx * EditLayoutSolver.GRID_GAP_FRACTION, grid.gapPx, 0.01f)
    }

    @Test
    fun degenerateScreenYieldsNoRect() {
        val w = WidgetCatalog.create(WidgetCatalog.WEATHER)!!
        assertFalse(EditLayoutSolver.widgetRect(w, 0f, screenH, 0.3f, grid, rect))
        assertFalse(EditLayoutSolver.widgetRect(w, screenW, -1f, 0.3f, grid, rect))
        assertEquals(0f, EditLayoutSolver.pillDiameterPx(0f, screenH, 1f), 0.001f)
    }

    @Test
    fun span1x1OccupiesOneCell() {
        val w = WidgetCatalog.create(WidgetCatalog.WEATHER)!!.copy(x = 0f, y = 0f, size = WidgetSize.SPAN_1X1)
        assertTrue(EditLayoutSolver.widgetRect(w, screenW, screenH, 0.3f, grid, rect))
        assertEquals(grid.cellPx, rect.width, 0.01f)
        assertEquals(grid.cellPx, rect.height, 0.01f)
        assertEquals(grid.originX, rect.left, 0.01f)
        assertEquals(grid.originY, rect.top, 0.01f)
    }

    @Test
    fun span2x2SubtractsOneGap() {
        val w = WidgetCatalog.create(WidgetCatalog.WEATHER)!!.copy(x = 0f, y = 0f, size = WidgetSize.SPAN_2X2)
        EditLayoutSolver.widgetRect(w, screenW, screenH, 0.3f, grid, rect)
        assertEquals(grid.cellPx * 2 - grid.gapPx, rect.width, 0.01f)
        assertEquals(grid.cellPx * 2 - grid.gapPx, rect.height, 0.01f)
    }

    @Test
    fun span1x4IsOneColumnFourRows() {
        val w = WidgetCatalog.create(WidgetCatalog.MUSIC)!!.copy(x = 0f, y = 0f, size = WidgetSize.SPAN_1X4)
        EditLayoutSolver.widgetRect(w, screenW, screenH, 0.2f, grid, rect)
        assertEquals(grid.cellPx, rect.width, 0.01f)
        assertEquals(grid.cellPx * 4 - grid.gapPx * 3, rect.height, 0.01f)
    }

    @Test
    fun cellClampsSoSpanNeverOverflowsGrid() {
        val wide = EditLayoutSolver.cellOf(1f, 1f, 0.3f, WidgetSize.SPAN_2X2)
        assertEquals(EditLayoutSolver.GRID_COLUMNS - 2, wide[0])
        assertEquals(EditLayoutSolver.GRID_ROWS - 2, wide[1])
        val tall = EditLayoutSolver.cellOf(1f, 1f, 0.3f, WidgetSize.SPAN_1X4)
        assertEquals(EditLayoutSolver.GRID_COLUMNS - 1, tall[0])
        assertEquals(0, tall[1])
    }

    @Test
    fun cellSnapsToNearest() {
        val c = EditLayoutSolver.cellOf(0.30f, 0.30f, 0.3f, WidgetSize.SPAN_1X1)
        assertEquals(1, c[0])
        assertEquals(1, c[1])
    }

    @Test
    fun widgetStaysInsideScreenHorizontally() {
        val w = WidgetCatalog.create(WidgetCatalog.BATTERY)!!.copy(x = 1f, y = 0.5f, size = WidgetSize.SPAN_2X2)
        EditLayoutSolver.widgetRect(w, screenW, screenH, 0.3f, grid, rect)
        assertTrue(rect.right <= screenW + 0.01f)
        assertTrue(rect.left >= 0f)
    }

    @Test
    fun clampKeepsRectInsideScreen() {
        rect.set(-50f, screenH - 10f, 200f, 200f)
        EditLayoutSolver.clampInto(rect, screenW, screenH)
        assertEquals(0f, rect.left, 0.01f)
        assertEquals(screenH - 200f, rect.top, 0.01f)
        rect.set(screenW - 10f, -20f, 200f, 200f)
        EditLayoutSolver.clampInto(rect, screenW, screenH)
        assertEquals(screenW - 200f, rect.left, 0.01f)
        assertEquals(0f, rect.top, 0.01f)
    }

    @Test
    fun clampOversizedRectPinsToOrigin() {
        rect.set(500f, 500f, screenW + 100f, screenH + 100f)
        EditLayoutSolver.clampInto(rect, screenW, screenH)
        assertEquals(0f, rect.left, 0.01f)
        assertEquals(0f, rect.top, 0.01f)
    }

    @Test
    fun pillCentersOnNormalizedPoint() {
        val p = PillPlacement(PillType.CAMERA.id, x = 0.8f, y = 0.88f, scale = 1f)
        assertTrue(EditLayoutSolver.pillRect(p, screenW, screenH, rect))
        assertEquals(screenW * 0.8f, rect.centerX, 0.01f)
        assertEquals(screenH * 0.88f, rect.centerY, 0.01f)
        assertEquals(rect.width, rect.height, 0.01f)
    }

    @Test
    fun pillScaleIsClamped() {
        val base = EditLayoutSolver.pillDiameterPx(screenW, screenH, 1f)
        assertEquals(base * EditLayoutSolver.MAX_PILL_SCALE, EditLayoutSolver.pillDiameterPx(screenW, screenH, 9f), 0.01f)
        assertEquals(base * EditLayoutSolver.MIN_PILL_SCALE, EditLayoutSolver.pillDiameterPx(screenW, screenH, 0.01f), 0.01f)
    }

    @Test
    fun pillUsesShortSide() {
        val portrait = EditLayoutSolver.pillDiameterPx(1080f, 2376f, 1f)
        val landscape = EditLayoutSolver.pillDiameterPx(2376f, 1080f, 1f)
        assertEquals(portrait, landscape, 0.01f)
    }

    @Test
    fun overlapDetectsIntersectionNotTouching() {
        rect.set(0f, 0f, 100f, 100f)
        other.set(50f, 50f, 100f, 100f)
        assertTrue(EditLayoutSolver.overlaps(rect, other))
        other.set(100f, 0f, 100f, 100f)
        assertFalse(EditLayoutSolver.overlaps(rect, other))
        other.set(200f, 200f, 10f, 10f)
        assertFalse(EditLayoutSolver.overlaps(rect, other))
    }

    @Test
    fun enabledFiltersRespectConfig() {
        val s = LockLayoutSettings.defaults(EditSurface.LOCK)
        s.setWidget(WidgetCatalog.create(WidgetCatalog.WEATHER)!!)
        s.setWidget(WidgetCatalog.create(WidgetCatalog.MUSIC, enabled = false)!!)
        assertEquals(1, EditLayoutSolver.enabledWidgets(s).size)
        assertEquals(WidgetCatalog.WEATHER, EditLayoutSolver.enabledWidgets(s)[0].typeId)
        assertEquals(2, EditLayoutSolver.enabledPills(s).size)
        s.setPill(PillPlacement(PillType.CAMERA.id, enabled = false))
        assertEquals(1, EditLayoutSolver.enabledPills(s).size)
    }

    @Test
    fun gridCanonicalPositionsRoundTripThroughCellOf() {
        for (span in WidgetSize.values()) {
            for (col in 0..EditLayoutSolver.GRID_COLUMNS - span.columns) {
                for (row in 0..EditLayoutSolver.GRID_ROWS - span.rows) {
                    val x = col.toFloat() / EditLayoutSolver.GRID_COLUMNS
                    val y = row.toFloat() / EditLayoutSolver.GRID_ROWS
                    val cell = EditLayoutSolver.cellOf(x, y, EditLayoutSolver.WIDGET_GRID_TOP, span)
                    assertEquals(col, cell[0])
                    assertEquals(row, cell[1])
                }
            }
        }
    }

    @Test
    fun aodDefaultsHaveNoPills() {
        val s = LockLayoutSettings.defaults(EditSurface.AOD)
        assertEquals(0, EditLayoutSolver.enabledPills(s).size)
        assertEquals(0, EditLayoutSolver.enabledWidgets(s).size)
    }
}