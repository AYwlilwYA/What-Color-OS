package com.coloros16.liquidglass.ui.aod

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.coloros16.liquidglass.R
import com.coloros16.liquidglass.engine.edit.ClockFonts
import com.coloros16.liquidglass.engine.edit.ClockInfo
import com.coloros16.liquidglass.engine.edit.ClockLayout
import com.coloros16.liquidglass.engine.edit.EditAxis
import com.coloros16.liquidglass.engine.edit.EditLayoutSolver
import com.coloros16.liquidglass.engine.edit.EditSurface
import com.coloros16.liquidglass.engine.edit.LockLayoutSettings
import com.coloros16.liquidglass.engine.edit.LockWidget
import com.coloros16.liquidglass.engine.edit.PillPlacement
import com.coloros16.liquidglass.engine.edit.SnapEngine
import com.coloros16.liquidglass.engine.edit.WidgetCatalog
import kotlin.math.abs
import kotlin.math.min

/**
 * 锁屏 / 息屏编辑画布：时钟、右侧小组件、底部大丸的摆放与吸附参考线。
 *
 * 时钟长按进入拖动（点一下出字体面板），小组件与大丸直接拖动（长按改尺寸）；
 * 点空白交给外部进入全屏预览。
 */
class AodEditView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        fun onClockTapped()
        fun onWidgetHold(typeId: String)
        fun onPillHold(typeId: String)
        fun onEmptyTapped()
        fun onLayoutEdited()
    }

    var listener: Listener? = null

    var surface: EditSurface = EditSurface.LOCK
        set(value) {
            field = value
            invalidate()
        }

    var settings: LockLayoutSettings? = null
        set(value) {
            field = value
            selection = Selection.NONE
            invalidate()
        }

    private enum class Kind { CLOCK, WIDGET, PILL }

    private enum class Selection { NONE, CLOCK, WIDGET, PILL }

    private var selection = Selection.NONE
    private var selectionId: String? = null

    private var target: Kind? = null
    private var targetId: String? = null
    private var armed = false
    private var resizing = false
    private var moved = false
    private var holdFired = false

    private var downX = 0f
    private var downY = 0f
    private var startX = 0f
    private var startY = 0f
    private var startVX = 0f
    private var startVY = 0f
    private var startScale = 1f
    private var guideX = -1f
    private var guideY = -1f

    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = resources.displayMetrics.density
    private val clockEdgeMargin = 0.05f

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = 0xFFFFFFFF.toInt()
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = 0x99FFFFFF.toInt()
    }
    private val platePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x1FFFFFFF
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x40FFFFFF
    }
    private val selectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        color = context.getColor(R.color.lg_blued_ink)
        pathEffect = DashPathEffect(floatArrayOf(7f * density, 5f * density), 0f)
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = context.getColor(R.color.lg_blued_ink)
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 6f * density), 0f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = context.getColor(R.color.lg_blued_ink)
    }
    private val widgetTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * density
        color = 0xE6FFFFFF.toInt()
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * density
        color = 0x66FFFFFF.toInt()
        textAlign = Paint.Align.CENTER
    }

    private val clockBounds = RectF()
    private var clockTextSize = 0f

    private val holdClock = Runnable {
        if (target == Kind.CLOCK) {
            armed = true
            holdFired = true
            selection = Selection.CLOCK
            selectionId = null
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            invalidate()
        }
    }

    private val holdPill = Runnable {
        val id = targetId
        if (target == Kind.PILL && id != null) {
            holdFired = true
            armed = false
            moved = false
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            listener?.onPillHold(id)
        }
    }

    private val holdWidget = Runnable {
        val id = targetId
        if (target == Kind.WIDGET && id != null) {
            holdFired = true
            armed = false
            moved = false
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            listener?.onWidgetHold(id)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        backgroundPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            0xFF0B0F14.toInt(), 0xFF1A222C.toInt(), Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        canvas.drawRect(0f, 0f, w, h, backgroundPaint)
        val s = settings ?: return

        drawClock(canvas, s, w, h)
        drawWidgets(canvas, s, w, h)
        if (surface == EditSurface.LOCK) drawPills(canvas, s, w, h)
        drawSelection(canvas, s, w, h)
        drawGuides(canvas, w, h)
        drawHint(canvas, w, h)
    }

    private fun drawClock(canvas: Canvas, s: LockLayoutSettings, w: Float, h: Float) {
        val cx = s.clockX * w
        val cy = s.clockY * h
        clockTextSize = ClockLayout.fontPx(w, h, s.clockScale)
        clockPaint.textSize = clockTextSize
        clockPaint.typeface = typefaceOf(s)
        clockPaint.letterSpacing = s.clock.letterSpacing
        clockPaint.color = s.clock.colorArgb

        val time = "10:08"
        val metrics = clockPaint.fontMetrics
        val baseline = cy - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(time, cx, baseline, clockPaint)

        subPaint.textSize = clockTextSize * 0.15f
        val dateBaseline = cy + clockTextSize * 0.62f
        canvas.drawText("11月17日 星期二", cx, dateBaseline, subPaint)
        val info = when (s.clock.info) {
            ClockInfo.LUNAR -> "农历 十月初五"
            ClockInfo.WEATHER -> "22° 晴"
            ClockInfo.NONE -> null
        }
        if (info != null) {
            canvas.drawText(info, cx, dateBaseline + subPaint.textSize * 1.6f, subPaint)
        }

        val halfW = clockPaint.measureText(time) / 2f + 14f * density
        val halfH = clockTextSize * 0.45f
        clockBounds.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
    }

    private fun drawWidgets(canvas: Canvas, s: LockLayoutSettings, w: Float, h: Float) {
        for (widget in s.widgets()) {
            if (!widget.enabled) continue
            val rect = widgetRect(widget, w, h)
            val radius = 14f * density
            canvas.drawRoundRect(rect, radius, radius, platePaint)
            canvas.drawRoundRect(rect, radius, radius, strokePaint)
            val name = widgetName(widget.typeId)
            canvas.drawText(name, rect.left + 12f * density, rect.top + 22f * density, widgetTextPaint)
            val inner = RectF(
                rect.left + 12f * density,
                rect.top + 30f * density,
                rect.right - 12f * density,
                rect.bottom - 12f * density,
            )
            if (inner.height() > 0f) {
                canvas.drawRoundRect(inner, 8f * density, 8f * density, platePaint)
            }
        }
    }

    private fun drawPills(canvas: Canvas, s: LockLayoutSettings, w: Float, h: Float) {
        for (pill in s.pills()) {
            if (!pill.enabled) continue
            val rect = pillRect(pill, w, h)
            canvas.drawCircle(rect.centerX(), rect.centerY(), rect.width() / 2f, platePaint)
            canvas.drawCircle(rect.centerX(), rect.centerY(), rect.width() / 2f, strokePaint)
            PillGlyphPainter.draw(canvas, rect, s.pillContent(pill.typeId), density, strokePaint, widgetTextPaint)
        }
    }

    private fun drawSelection(canvas: Canvas, s: LockLayoutSettings, w: Float, h: Float) {
        if (selection == Selection.NONE) return
        val rect = when (selection) {
            Selection.CLOCK -> RectF(clockBounds)
            Selection.WIDGET -> selectionId?.let { id ->
                s.widget(id)?.let { widgetRect(it, w, h) }
            } ?: return
            Selection.PILL -> selectionId?.let { id ->
                s.pill(id)?.let { pillRect(it, w, h) }
            } ?: return
            Selection.NONE -> return
        }
        canvas.drawRoundRect(rect, 12f * density, 12f * density, selectPaint)
        if (selection == Selection.CLOCK) {
            canvas.drawCircle(rect.right, rect.bottom, 7f * density, handlePaint)
        }
    }

    private fun drawGuides(canvas: Canvas, w: Float, h: Float) {
        if (guideX >= 0f) canvas.drawLine(guideX * w, 0f, guideX * w, h, guidePaint)
        if (guideY >= 0f) canvas.drawLine(0f, guideY * h, w, guideY * h, guidePaint)
    }

    private fun drawHint(canvas: Canvas, w: Float, h: Float) {
        canvas.drawText(context.getString(R.string.settings_aod_edit_stage_hint), w / 2f, 16f * density, hintPaint)
    }

    private val solverGrid = EditLayoutSolver.Grid()
    private val solverRect = EditLayoutSolver.Rect()
    private val pillSolverRect = EditLayoutSolver.Rect()

    private fun widgetRect(widget: LockWidget, w: Float, h: Float): RectF {
        if (!EditLayoutSolver.widgetRect(widget, w, h, EditLayoutSolver.WIDGET_GRID_TOP, solverGrid, solverRect)) {
            return RectF()
        }
        EditLayoutSolver.clampInto(solverRect, w, h)
        return RectF(solverRect.left, solverRect.top, solverRect.right, solverRect.bottom)
    }

    private fun pillRect(pill: PillPlacement, w: Float, h: Float): RectF {
        if (!EditLayoutSolver.pillRect(pill, w, h, pillSolverRect)) return RectF()
        return RectF(
            pillSolverRect.left,
            pillSolverRect.top,
            pillSolverRect.right,
            pillSolverRect.bottom,
        )
    }

    private fun widgetName(typeId: String): String {
        val key = WidgetCatalog.find(typeId)?.nameKey ?: return typeId
        val res = resources.getIdentifier(key, "string", context.packageName)
        return if (res != 0) context.getString(res) else typeId
    }

    private fun typefaceOf(s: LockLayoutSettings): Typeface {
        val base = when (ClockFonts.normalize(s.clock.fontFamily)) {
            ClockFonts.SERIF -> Typeface.SERIF
            ClockFonts.MONO -> Typeface.MONOSPACE
            ClockFonts.CASUAL -> Typeface.create("casual", Typeface.NORMAL)
            else -> Typeface.SANS_SERIF
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                Typeface.create(base, s.clock.weight, false)
            } catch (t: Throwable) {
                base
            }
        } else {
            base
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val s = settings ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x
                val y = event.y
                downX = x
                downY = y
                moved = false
                holdFired = false
                armed = false
                resizing = false
                guideX = -1f
                guideY = -1f
                target = null
                targetId = null
                if (selection == Selection.CLOCK && nearClockHandle(x, y)) {
                    resizing = true
                    target = Kind.CLOCK
                    startScale = s.clockScale
                    return true
                }
                if (!pick(x, y, s)) {
                    selection = Selection.NONE
                    selectionId = null
                }
                when (target) {
                    Kind.CLOCK -> {
                        startX = s.clockX
                        startY = s.clockY
                        handler.postDelayed(holdClock, ViewConfiguration.getLongPressTimeout().toLong())
                    }
                    Kind.WIDGET -> {
                        s.widget(targetId ?: "")?.let {
                            startX = it.x
                            startY = it.y
                            val r = widgetRect(it, width.toFloat(), height.toFloat())
                            startVX = r.centerX()
                            startVY = r.centerY()
                        }
                        armed = true
                        selection = Selection.WIDGET
                        selectionId = targetId
                        handler.postDelayed(holdWidget, ViewConfiguration.getLongPressTimeout().toLong())
                    }
                    Kind.PILL -> {
                        s.pill(targetId ?: "")?.let {
                            startX = it.x
                            startY = it.y
                        }
                        armed = true
                        selection = Selection.PILL
                        selectionId = targetId
                        handler.postDelayed(holdPill, ViewConfiguration.getLongPressTimeout().toLong())
                    }
                    null -> Unit
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (!moved && dx * dx + dy * dy > touchSlop.toFloat() * touchSlop) {
                    moved = true
                    handler.removeCallbacks(holdClock)
                    handler.removeCallbacks(holdWidget)
                    handler.removeCallbacks(holdPill)
                }
                if (resizing) {
                    val base = min(width, height).toFloat()
                    val delta = (dy + dx) / (base * 0.35f)
                    s.clockScale = (startScale + delta).coerceAtMost(fitMaxScale())
                    invalidate()
                    return true
                }
                if (moved && armed) {
                    dragTo(s, event.x, event.y)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(holdClock)
                handler.removeCallbacks(holdWidget)
                handler.removeCallbacks(holdPill)
                val w = width.toFloat()
                val h = height.toFloat()
                val edited = resizing || (armed && moved)
                if (resizing) {
                    val delta = ((event.y - downY) + (event.x - downX)) / (min(w, h) * 0.35f)
                    s.clockScale = (startScale + delta).coerceAtMost(fitMaxScale())
                    clampClock(s)
                }
                resizing = false
                armed = false
                guideX = -1f
                guideY = -1f
                val tapped = !moved && !holdFired && event.actionMasked == MotionEvent.ACTION_UP
                when {
                    edited -> listener?.onLayoutEdited()
                    tapped && target == Kind.CLOCK -> listener?.onClockTapped()
                    tapped && target == null -> listener?.onEmptyTapped()
                }
                target = null
                targetId = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun pick(x: Float, y: Float, s: LockLayoutSettings): Boolean {
        val w = width.toFloat()
        val h = height.toFloat()
        for (widget in s.widgets().asReversed()) {
            if (widget.enabled && widgetRect(widget, w, h).contains(x, y)) {
                target = Kind.WIDGET
                targetId = widget.typeId
                return true
            }
        }
        if (surface == EditSurface.LOCK) {
            for (pill in s.pills().asReversed()) {
                if (pill.enabled && pillRect(pill, w, h).contains(x, y)) {
                    target = Kind.PILL
                    targetId = pill.typeId
                    return true
                }
            }
        }
        if (clockBounds.contains(x, y)) {
            target = Kind.CLOCK
            return true
        }
        return false
    }

    private fun dragTo(s: LockLayoutSettings, x: Float, y: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        val nx = (startX + (x - downX) / w).coerceIn(0f, 1f)
        val ny = (startY + (y - downY) / h).coerceIn(0f, 1f)
        when (target) {
            Kind.CLOCK -> {
                val cx = clampCenterX(nx, clockBounds.width() / 2f, clockEdgeMargin)
                val cy = if (surface.axis == EditAxis.HORIZONTAL_ONLY) {
                    ny
                } else {
                    clampCenterY(ny, clockBounds.height() / 2f, clockEdgeMargin)
                }
                val result = if (s.snapEnabled) {
                    s.moveClock(cx, cy)
                } else {
                    s.moveClock(cx, cy, 0f)
                }
                guideX = result.guideXPosition
                guideY = result.guideYPosition
            }
            Kind.WIDGET -> {
                val widget = s.widget(targetId ?: "") ?: return
                solverGrid.solve(w, h, EditLayoutSolver.WIDGET_GRID_TOP)
                val vx = startVX + (x - downX)
                val vy = if (surface.axis == EditAxis.HORIZONTAL_ONLY) startVY else startVY + (y - downY)
                val cell = EditLayoutSolver.cellFromVisual(vx, vy, widget.size, solverGrid) ?: return
                val config = EditLayoutSolver.configOf(cell[0], cell[1])
                val moved = widget.copy(x = config[0], y = config[1])
                s.setWidget(moved)
                val r = widgetRect(moved, w, h)
                guideX = if (r.width() > 0f) r.centerX() / w else -1f
                guideY = if (r.height() > 0f) r.centerY() / h else -1f
            }
            Kind.PILL -> {
                val pill = s.pill(targetId ?: "") ?: return
                val half = EditLayoutSolver.pillDiameterPx(w, h, pill.scale) / 2f
                val result = SnapEngine.snap(
                    clampCenterX(nx, half),
                    clampCenterY(ny, half),
                    SnapEngine.guides(surface),
                    surface.axis,
                    if (s.snapEnabled) SnapEngine.DEFAULT_THRESHOLD else 0f,
                )
                s.setPill(pill.copy(x = result.x, y = result.y))
                guideX = result.guideXPosition
                guideY = result.guideYPosition
            }
            null -> Unit
        }
    }

    /** 时钟放大上限：按整串时间宽度换算（与真机同一套 [ClockLayout]）。 */
    private fun fitMaxScale(): Float {
        val w = width.toFloat()
        val current = settings?.clockScale ?: 1f
        val drawn = clockBounds.width() - 28f * density
        if (w <= 0f || current <= 0f || drawn <= 0f) return LockLayoutSettings.MAX_CLOCK_SCALE
        return ClockLayout.fitScale(drawn, current, w * (1f - 2f * clockEdgeMargin))
    }

    private fun clampClock(s: LockLayoutSettings) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        s.clockX = clampCenterX(s.clockX, clockBounds.width() / 2f, clockEdgeMargin)
        if (surface.axis == EditAxis.FREE) s.clockY = clampCenterY(s.clockY, clockBounds.height() / 2f, clockEdgeMargin)
    }

    private fun clampCenterX(value: Float, halfPx: Float, margin: Float = 0f): Float {
        val w = width.toFloat()
        if (w <= 0f || halfPx <= 0f) return value
        val half = halfPx / w + margin
        return if (half * 2f >= 1f) 0.5f else value.coerceIn(half, 1f - half)
    }

    private fun clampCenterY(value: Float, halfPx: Float, margin: Float = 0f): Float {
        val h = height.toFloat()
        if (h <= 0f || halfPx <= 0f) return value
        val half = halfPx / h + margin
        return if (half * 2f >= 1f) 0.5f else value.coerceIn(half, 1f - half)
    }

    private fun nearClockHandle(x: Float, y: Float): Boolean {
        val r = 22f * density
        return abs(x - clockBounds.right) <= r && abs(y - clockBounds.bottom) <= r
    }
}
