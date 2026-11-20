package com.coloros16.liquidglass.ui.aod

import android.content.Context
import android.graphics.Canvas
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
import com.coloros16.liquidglass.engine.SCREEN_ASPECT
import com.coloros16.liquidglass.engine.edit.ClockFonts
import com.coloros16.liquidglass.engine.edit.ClockInfo
import com.coloros16.liquidglass.engine.edit.ClockLayout
import com.coloros16.liquidglass.engine.edit.EditAnchorOverlay
import com.coloros16.liquidglass.engine.edit.EditAxis
import com.coloros16.liquidglass.engine.edit.EditLayoutSolver
import com.coloros16.liquidglass.engine.edit.EditSurface
import com.coloros16.liquidglass.engine.edit.LockLayoutSettings
import com.coloros16.liquidglass.engine.edit.LockWidget
import com.coloros16.liquidglass.engine.edit.PillPlacement
import com.coloros16.liquidglass.engine.edit.SnapEngine
import com.coloros16.liquidglass.engine.edit.WidgetCatalog
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
        fun onWidgetTapped(typeId: String)
        fun onPillHold(typeId: String)
        fun onPillTapped(typeId: String)
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

    /** 样式时钟锚点（归一化）由外部注入；未注入时回落编辑缺省位。 */
    var anchor: Pair<Float, Float>? = null
        set(value) {
            field = value
            invalidate()
        }

    private fun anchorX(): Float =
        anchor?.first ?: EditAnchorOverlay.defaultX()

    private fun anchorY(): Float =
        anchor?.second ?: EditAnchorOverlay.defaultYOf(surface)

    /** 时钟实际绘制中心（px）：这一轴没编辑过就落样式锚点，编辑过取用户值。 */
    private fun clockCenterPx(s: LockLayoutSettings, w: Float, h: Float): Pair<Float, Float> =
        ClockLayout.configToVisual(s.clockX, anchorX(), EditAnchorOverlay.defaultX()) * w to
            ClockLayout.configToVisual(s.clockY, anchorY(), EditAnchorOverlay.defaultYOf(surface)) * h

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
    private var resizeAnchorLeft = 0f
    private var resizeAnchorTop = 0f
    private var startStretchX = 1f
    private var startStretchY = 1f
    private var resizeAnchorHalfW = 0f
    private var resizeAnchorHalfH = 0f
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
        color = EditChromePainter.PLATE_COLOR
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x40FFFFFF
    }
    private val selectPaint =
        EditChromePainter.selectPaint(density, context.getColor(R.color.lg_blued_ink))
    private val guidePaint =
        EditChromePainter.guidePaint(density, context.getColor(R.color.lg_blued_ink))
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = context.getColor(R.color.lg_blued_ink)
    }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x66000000
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
    private val dateColor = EditChromePainter.DATE_COLOR

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

    /**
     * 画布按整屏等比测：容器给多大都在里面摆一个手机比例的框，
     * 归一化坐标才与真机一致，不会被正方形容器压扁。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availW = MeasureSpec.getSize(widthMeasureSpec).toFloat().coerceAtLeast(1f)
        val availH = MeasureSpec.getSize(heightMeasureSpec).toFloat().coerceAtLeast(1f)
        var w = availH * SCREEN_ASPECT
        var h = availH
        if (w > availW) {
            w = availW
            h = w / SCREEN_ASPECT
        }
        setMeasuredDimension(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1))
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
        val center = clockCenterPx(s, w, h)
        ClockGlyphPainter.draw(
            canvas, center.first, center.second,
            ClockLayout.fontPx(w, h, s.clockScale),
            timeText(), dateText(), s.clock.colorArgb, dateColor,
            typefaceOf(s), dateTypeface(), s.clock.letterSpacing, s.clock.info,
            clockPaint, subPaint, clockBounds, EditChromePainter.CLOCK_PAD_DP * density,
            s.clockStretchX, s.clockStretchY,
        )
    }

    /** 编辑页时钟走真实时间，与预览同源。 */
    private fun timeText(): String {
        val now = java.time.LocalDateTime.now()
        return if (android.text.format.DateFormat.is24HourFormat(context)) {
            String.format("%02d:%02d", now.hour, now.minute)
        } else {
            val hour = now.hour % 12
            String.format("%d:%02d", if (hour == 0) 12 else hour, now.minute)
        }
    }

    private fun dateText(): String {
        val now = java.time.LocalDateTime.now()
        val weekday = when (now.dayOfWeek.value) {
            1 -> "周一"
            2 -> "周二"
            3 -> "周三"
            4 -> "周四"
            5 -> "周五"
            6 -> "周六"
            else -> "周日"
        }
        return "${now.monthValue}月${now.dayOfMonth}日 $weekday"
    }

    private fun drawWidgets(canvas: Canvas, s: LockLayoutSettings, w: Float, h: Float) {
        for (widget in s.widgets()) {
            if (!widget.enabled) continue
            val rect = widgetRect(widget, w, h)
            WidgetPlatePainter.draw(canvas, rect, widgetName(widget.typeId), density, platePaint, strokePaint, widgetTextPaint)
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
        EditChromePainter.drawSelection(canvas, rect, selection == Selection.PILL, density, selectPaint)
        if (selection == Selection.CLOCK) {
            EditChromePainter.drawClockHandles(canvas, rect, density, handlePaint, handleStroke)
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

    private fun typefaceOf(s: LockLayoutSettings): Typeface = ClockTypefaces.timeOf(context, s.clock)

    private fun dateTypeface(): Typeface = ClockTypefaces.date()

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
                // 存量配置可能带着超框拉伸进来，手柄那时已经不在可点范围，先收一次再判命中
                ensureStretchFits(s)
                if (selection == Selection.CLOCK && nearClockHandle(x, y)) {
                    resizing = true
                    target = Kind.CLOCK
                    startStretchX = s.clockStretchX
                    startStretchY = s.clockStretchY
                    resizeAnchorLeft = clockBounds.left
                    resizeAnchorTop = clockBounds.top
                    resizeAnchorHalfW = clockBounds.width() / 2f
                    resizeAnchorHalfH = clockBounds.height() / 2f
                    return true
                }
                if (!pick(x, y, s)) {
                    selection = Selection.NONE
                    selectionId = null
                }
                when (target) {
                    Kind.CLOCK -> {
                        val c = clockCenterPx(s, width.toFloat(), height.toFloat())
                        startX = c.first / width.toFloat()
                        startY = c.second / height.toFloat()
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
                    resizeClock(s, dx, dy)
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
                    resizeClock(s, event.x - downX, event.y - downY)
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
                    tapped && target == Kind.WIDGET -> targetId?.let { listener?.onWidgetTapped(it) }
                    tapped && target == Kind.PILL -> targetId?.let { listener?.onPillTapped(it) }
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
                val vx = clampVisualCenter(nx, clockBounds.width() / 2f, w)
                val vy = if (surface.axis == EditAxis.HORIZONTAL_ONLY) {
                    clockBounds.centerY() / h
                } else {
                    clampVisualCenter(ny, clockBounds.height() / 2f, h)
                }
                val cx = ClockLayout.visualToConfig(vx, anchorX(), EditAnchorOverlay.defaultX())
                val cy = ClockLayout.visualToConfig(
                    vy, anchorY(), EditAnchorOverlay.defaultYOf(surface),
                )
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

    /** 右下角手柄自由变形：横向位移拉宽、纵向位移拉高，左上角钉死。 */
    private fun resizeClock(s: LockLayoutSettings, dx: Float, dy: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val next = ClockLayout.resizeStretch(
            startStretchX, startStretchY, dx, dy, resizeAnchorHalfW, resizeAnchorHalfH,
        )
        s.clockStretchX = next[0]
        s.clockStretchY = next[1]
        drawClockOffscreen(s, w, h)
        // 框超出画布时钩心会被推到屏幕外，手柄是唯一收窄入口 ⇒ 拉过头就拉不回来。
        val fit = ClockLayout.fitStretch(
            s.clockStretchX, s.clockStretchY, clockBounds.width(), clockBounds.height(),
            2f * EditChromePainter.CLOCK_PAD_DP * density, w, h,
        )
        if (fit[0] != s.clockStretchX || fit[1] != s.clockStretchY) {
            s.clockStretchX = fit[0]
            s.clockStretchY = fit[1]
            drawClockOffscreen(s, w, h)
        }
        val cx = (resizeAnchorLeft + clockBounds.width() / 2f) / w
        val cy = (resizeAnchorTop + clockBounds.height() / 2f) / h
        s.clockX = ClockLayout.visualToConfig(cx, anchorX(), EditAnchorOverlay.defaultX())
        s.clockY = ClockLayout.visualToConfig(
            cy, anchorY(), EditAnchorOverlay.defaultYOf(surface),
        )
        logResize(next[0], next[1], s)
    }

    /** 手柄变形全链路值：锚点、两轴拉伸、反算中心，用于定位左上角漂移。 */
    private fun logResize(sx: Float, sy: Float, s: LockLayoutSettings) {
        val now = android.os.SystemClock.uptimeMillis()
        val key = "${(sx * 100f).toInt()}|${(sy * 100f).toInt()}|${(s.clockX * 500f).toInt()}"
        if (key == resizeLogKey && now - resizeLogAt < 1200L) return
        resizeLogKey = key
        resizeLogAt = now
        com.coloros16.liquidglass.log.Lg.i(
            "AodEdit",
            "clock-stretch sx=$sx sy=$sy anchorL=$resizeAnchorLeft anchorT=$resizeAnchorTop " +
                "anchorHalfW=$resizeAnchorHalfW anchorHalfH=$resizeAnchorHalfH " +
                "newHalfW=${clockBounds.width() / 2f} newHalfH=${clockBounds.height() / 2f} " +
                "cx=${s.clockX} cy=${s.clockY}",
        )
    }

    private var resizeLogKey = ""
    private var resizeLogAt = 0L

    /** 只量框不绘制：变形中按新拉伸重算命中框，供左上角反算中心。 */
    private fun drawClockOffscreen(s: LockLayoutSettings, w: Float, h: Float) {
        val size = ClockLayout.fontPx(w, h, s.clockScale)
        val probe = Paint(clockPaint).apply {
            textSize = size
            typeface = typefaceOf(s)
            letterSpacing = s.clock.letterSpacing
            textScaleX = s.clockStretchX
        }
        val halfW = probe.measureText(timeText()) / 2f + EditChromePainter.CLOCK_PAD_DP * density
        val halfH = size * ClockGlyphPainter.BOUNDS_HALF_HEIGHT_RATIO * s.clockStretchY
        val center = clockCenterPx(s, w, h)
        val cx = center.first
        val cy = center.second
        clockBounds.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
    }

    /** 时钟放大上限：按整串时间宽度换算（与真机同一套 [ClockLayout]）。 */
    private fun fitMaxScale(): Float {
        val w = width.toFloat()
        val current = settings?.clockScale ?: 1f
        val drawn = clockBounds.width() - 2f * EditChromePainter.CLOCK_PAD_DP * density
        if (w <= 0f || current <= 0f || drawn <= 0f) return LockLayoutSettings.MAX_CLOCK_SCALE
        return ClockLayout.fitScale(drawn, current, w * (1f - 2f * clockEdgeMargin))
    }

    /** 边界钳位按实际绘制中心做，再换算回配置值；直接钳配置值会让左上锚点漂。 */
    private fun clampClock(s: LockLayoutSettings) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val center = clockCenterPx(s, w, h)
        val vx = clampVisualCenter(center.first / w, clockBounds.width() / 2f, w)
        s.clockX = ClockLayout.visualToConfig(vx, anchorX(), EditAnchorOverlay.defaultX())
        if (surface.axis == EditAxis.FREE) {
            val vy = clampVisualCenter(center.second / h, clockBounds.height() / 2f, h)
            s.clockY = ClockLayout.visualToConfig(
                vy, anchorY(), EditAnchorOverlay.defaultYOf(surface),
            )
        }
    }

    /**
     * 拉伸后框比屏还宽时不钳位：钳位会把中心拍回 0.5，左上锚点当场跳走。
     *
     * 只有框还装得下才钳；装不下就保留用户拖出的中心，允许溢出屏幕。
     */
    private fun clampVisualCenter(visual: Float, halfPx: Float, sizePx: Float): Float {
        if (sizePx <= 0f || halfPx <= 0f) return visual
        val half = halfPx / sizePx + clockEdgeMargin
        if (half * 2f >= 1f) {
            logOversize(half, visual)
            return visual
        }
        return visual.coerceIn(half, 1f - half)
    }

    private var oversizeLogAt = 0L

    private fun logOversize(half: Float, visual: Float) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - oversizeLogAt < 1500L) return
        oversizeLogAt = now
        com.coloros16.liquidglass.log.Lg.i(
            "AodEdit",
            "clock-clamp skipped oversize half=${"%.3f".format(half)} visual=${"%.3f".format(visual)}",
        )
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

    /** 框超出画布时按比例收拉伸并落盘：手柄是唯一收窄入口，超框即不可达。 */
    private fun ensureStretchFits(s: LockLayoutSettings) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || clockBounds.width() <= 0f) return
        val fit = ClockLayout.fitStretch(
            s.clockStretchX, s.clockStretchY, clockBounds.width(), clockBounds.height(),
            2f * EditChromePainter.CLOCK_PAD_DP * density, w, h,
        )
        if (fit[0] == s.clockStretchX && fit[1] == s.clockStretchY) return
        s.clockStretchX = fit[0]
        s.clockStretchY = fit[1]
        drawClockOffscreen(s, w, h)
        listener?.onLayoutEdited()
    }

    private fun nearClockHandle(x: Float, y: Float): Boolean =
        EditChromePainter.nearHook(x, y, clockBounds, density)
}
