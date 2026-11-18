package com.coloros16.liquidglass.engine.edit

import com.coloros16.liquidglass.engine.PaintOccupancy
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 小组件与大丸的布局求解：归一化配置 → 像素矩形与网格占位。
 *
 * 小组件按网格吸附（列宽由屏宽与列数决定），大丸按中心点 + 缩放定位。
 * 纯 JVM，预览与 hook 共用同一套换算。
 */
object EditLayoutSolver {

    /** 锁屏小组件网格列数。 */
    const val GRID_COLUMNS = 4

    /** 网格行数。 */
    const val GRID_ROWS = 4

    /** 网格左右外边距占屏宽比例。 */
    const val GRID_INSET_FRACTION = 0.06f

    /** 单元格间距占列宽比例。 */
    const val GRID_GAP_FRACTION = 0.08f

    /** 小组件网格顶部在屏高的归一化位置（预览与 hook 共用）。 */
    const val WIDGET_GRID_TOP = 0.30f

    /** 大丸基准直径占屏短边比例。 */
    const val PILL_DIAMETER_FRACTION = 0.14f

    const val MIN_PILL_SCALE = 0.6f
    const val MAX_PILL_SCALE = 1.6f

    /** 一个像素矩形（左上 + 宽高）。 */
    class Rect {
        var left = 0f
        var top = 0f
        var width = 0f
        var height = 0f

        val right: Float get() = left + width
        val bottom: Float get() = top + height
        val centerX: Float get() = left + width / 2f
        val centerY: Float get() = top + height / 2f

        fun set(l: Float, t: Float, w: Float, h: Float) {
            left = l; top = t; width = w; height = h
        }
    }

    /** 网格度量：单元格边长与原点，按屏幕尺寸一次算好。 */
    class Grid {
        var cellPx = 0f
        var gapPx = 0f
        var originX = 0f
        var originY = 0f

        fun solve(widthPx: Float, heightPx: Float, topFraction: Float) {
            if (widthPx <= 0f || heightPx <= 0f) {
                cellPx = 0f; gapPx = 0f; originX = 0f; originY = 0f
                return
            }
            val inset = widthPx * GRID_INSET_FRACTION
            val usable = widthPx - inset * 2f
            cellPx = usable / GRID_COLUMNS
            gapPx = cellPx * GRID_GAP_FRACTION
            originX = inset
            originY = heightPx * topFraction.coerceIn(0f, 1f)
        }
    }

    /**
     * 配置值 → 网格列行（正向映射，吸附到最近格；越界钳进网格）。
     *
     * 只用于「已存的配置值 → 格」与 round-trip 校验；
     * 手势拖拽请用 [cellFromVisual]，配置空间与视觉空间因 inset/gap 非线性同坐标。
     */
    fun cellOf(x: Float, y: Float, topFraction: Float, span: WidgetSize): IntArray {
        val col = (x.coerceIn(0f, 1f) * GRID_COLUMNS).roundToInt()
            .coerceIn(0, max(0, GRID_COLUMNS - span.columns))
        val row = (y.coerceIn(0f, 1f) * GRID_ROWS).roundToInt()
            .coerceIn(0, max(0, GRID_ROWS - span.rows))
        return intArrayOf(col, row)
    }

    /**
     * 视觉像素坐标 → 网格列行（手势拖拽用）。
     *
     * 按网格原点与格宽反算，落格即取整；[grid] 必须已 solve。
     * 返回 null 表示网格未就绪。
     */
    fun cellFromVisual(vx: Float, vy: Float, span: WidgetSize, grid: Grid): IntArray? {
        if (grid.cellPx <= 0f) return null
        val col = ((vx - grid.originX) / grid.cellPx).toInt()
            .coerceIn(0, max(0, GRID_COLUMNS - span.columns))
        val row = ((vy - grid.originY) / grid.cellPx).toInt()
            .coerceIn(0, max(0, GRID_ROWS - span.rows))
        return intArrayOf(col, row)
    }

    /** 格 → 配置值（拖拽落格后写回配置用，与 [cellOf] 互为逆）。 */
    fun configOf(col: Int, row: Int): FloatArray = floatArrayOf(
        col.toFloat() / GRID_COLUMNS,
        row.toFloat() / GRID_ROWS,
    )

    /**
     * 小组件像素矩形：按占位数扩展，扣掉间距。
     *
     * [topFraction] 是网格顶部在屏高的归一化位置（由调用方按当前态给）。
     */
    fun widgetRect(
        widget: LockWidget,
        widthPx: Float,
        heightPx: Float,
        topFraction: Float,
        grid: Grid,
        out: Rect,
    ): Boolean {
        grid.solve(widthPx, heightPx, topFraction)
        if (grid.cellPx <= 0f) return false
        val cell = cellOf(widget.x, widget.y, topFraction, widget.size)
        val w = grid.cellPx * widget.size.columns - grid.gapPx * (widget.size.columns - 1)
        val h = grid.cellPx * widget.size.rows - grid.gapPx * (widget.size.rows - 1)
        out.set(
            grid.originX + cell[0] * grid.cellPx,
            grid.originY + cell[1] * grid.cellPx,
            max(0f, w),
            max(0f, h),
        )
        return true
    }

    /** 大丸直径（像素）：短边 × 基准比例 × 缩放。 */
    fun pillDiameterPx(widthPx: Float, heightPx: Float, scale: Float): Float {
        if (widthPx <= 0f || heightPx <= 0f) return 0f
        val clamped = scale.coerceIn(MIN_PILL_SCALE, MAX_PILL_SCALE)
        return min(widthPx, heightPx) * PILL_DIAMETER_FRACTION * clamped
    }

    /** 大丸像素矩形：归一化中心对齐，尺寸按直径。 */
    fun pillRect(
        pill: PillPlacement,
        widthPx: Float,
        heightPx: Float,
        out: Rect,
    ): Boolean {
        val d = pillDiameterPx(widthPx, heightPx, pill.scale)
        if (d <= 0f) return false
        val cx = pill.x.coerceIn(0f, 1f) * widthPx
        val cy = pill.y.coerceIn(0f, 1f) * heightPx
        out.set(cx - d / 2f, cy - d / 2f, d, d)
        return true
    }

    /** 把矩形平移进屏幕内（尺寸超过屏幕时贴左上）。 */
    fun clampInto(rect: Rect, widthPx: Float, heightPx: Float) {
        val maxLeft = max(0f, widthPx - rect.width)
        val maxTop = max(0f, heightPx - rect.height)
        rect.left = min(max(0f, rect.left), maxLeft)
        rect.top = min(max(0f, rect.top), maxTop)
    }

    /**
     * 两个矩形是否重叠（边相接不算重叠）。
     *
     * 用于避免小组件压住大丸；调用方据此决定是否让位。
     */
    fun overlaps(a: Rect, b: Rect): Boolean = PaintOccupancy.intersects(
        PaintOccupancy.Box(a.left, a.top, a.width, a.height),
        PaintOccupancy.Box(b.left, b.top, b.width, b.height),
    )

    /** 只保留启用项，顺序按配置顺序（稳定，便于逐帧去重）。 */
    fun enabledWidgets(settings: LockLayoutSettings): List<LockWidget> =
        settings.widgets().filter { it.enabled }

    fun enabledPills(settings: LockLayoutSettings): List<PillPlacement> =
        settings.pills().filter { it.enabled }
}