package com.coloros16.liquidglass.ui.aod

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.from
import com.coloros16.liquidglass.engine.to
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
 * 预览页内联编辑：在手机预览框内长按选中时钟/小组件/大丸并拖动缩放，
 * 点时钟出字体面板、长按小组件出尺寸菜单、点大丸出内容面板；空白触摸交回外层。
 */
class AodPreviewEditDelegate(private val host: View) {

    interface Listener {
        fun onClockTapped()
        fun onWidgetHold(typeId: String)
        fun onWidgetTapped(typeId: String)
        fun onPillTapped(typeId: String)
        fun onLayoutEdited()
        fun onSelectionChanged(selected: Boolean)
    }

    var listener: Listener? = null

    var lockSettings: LockLayoutSettings? = null
    var aodSettings: LockLayoutSettings? = null

    fun contentOf(slotId: String): String =
        settings?.pillContent(slotId) ?: slotId

    /** 手机预览框（host 视图坐标系）。 */
    val phoneRect = RectF()

    var state: SceneState = SceneState.AOD
    var interactive: Boolean = false

    private enum class Kind { CLOCK, WIDGET, PILL }

    private var selection: Kind? = null
    private var selectionId: String? = null

    private var target: Kind? = null
    private var targetId: String? = null
    private var armed = false
    private var resizing = false
    private var moved = false
    private var holdFired = false
    private var tracking = false

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
    private val touchSlop = ViewConfiguration.get(host.context).scaledTouchSlop
    private val density = host.resources.displayMetrics.density
    private val clockEdgeMargin = 0.05f

    private companion object {
        const val AOD_DEFAULT_Y = 0.435f
        const val LOCK_DEFAULT_Y = 0.155f
    }

    private val clockBounds = RectF()
    private var clockTextSize = 0f
    private val widgetRects = ArrayList<Pair<String, RectF>>()
    private val pillRects = ArrayList<Pair<String, RectF>>()
    private val tmpRect = RectF()
    private val solverGrid = EditLayoutSolver.Grid()
    private val solverRect = EditLayoutSolver.Rect()

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
        color = 0x24FFFFFF
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x40FFFFFF
    }
    private val selectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
        color = 0xFF9FC2FF.toInt()
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0xFF9FC2FF.toInt()
        pathEffect = DashPathEffect(floatArrayOf(5f * density, 5f * density), 0f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFF2F6FB.toInt()
    }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x66000000
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xCC2A3340.toInt()
    }
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = 0xF2FFFFFF.toInt()
        textSize = 13f * density
    }
    private val widgetTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10.5f * density
        color = 0xE6FFFFFF.toInt()
        textAlign = Paint.Align.CENTER
    }

    private val surface: EditSurface
        get() = if (state == SceneState.AOD) EditSurface.AOD else EditSurface.LOCK

    private val settings: LockLayoutSettings?
        get() = if (state == SceneState.AOD) aodSettings else lockSettings

    private val editable: Boolean
        get() = interactive && phoneRect.width() > 2f &&
            (state == SceneState.AOD || state == SceneState.LOCK) && settings != null

    private val holdClock = Runnable {
        if (target == Kind.CLOCK) {
            armed = true
            holdFired = true
            select(Kind.CLOCK, null)
            host.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            host.invalidate()
        }
    }

    private val holdWidget = Runnable {
        val id = targetId
        if (target == Kind.WIDGET && id != null) {
            holdFired = true
            armed = false
            moved = false
            host.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            listener?.onWidgetHold(id)
        }
    }

    fun clearSelection() {
        if (selection != null) {
            selection = null
            selectionId = null
            listener?.onSelectionChanged(false)
            host.invalidate()
        }
    }

    fun cancelTouch() {
        handler.removeCallbacks(holdClock)
        handler.removeCallbacks(holdWidget)
        tracking = false
        armed = false
        resizing = false
        guideX = -1f
        guideY = -1f
    }

    /** 命中可编辑元素或已在拖拽时接管触摸；否则交回外层。 */
    fun onTouchEvent(event: MotionEvent): Boolean {
        if (!editable) {
            cancelTouch()
            return false
        }
        val s = settings ?: return false
        val x = event.x - phoneRect.left
        val y = event.y - phoneRect.top
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!phoneRect.contains(event.x, event.y)) return false
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
                if (selection == Kind.CLOCK && nearHandle(x, y, clockBounds)) {
                    resizing = true
                    target = Kind.CLOCK
                    tracking = true
                    startScale = s.clockScale
                    return true
                }
                if (!pick(x, y, s)) {
                    if (selection != null) {
                        clearSelection()
                        tracking = true
                        return true
                    }
                    return false
                }
                tracking = true
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
                            val r = widgetRect(it, phoneRect.width(), phoneRect.height())
                            startVX = r.centerX()
                            startVY = r.centerY()
                        }
                        armed = true
                        select(Kind.WIDGET, targetId)
                        handler.postDelayed(holdWidget, ViewConfiguration.getLongPressTimeout().toLong())
                    }
                    Kind.PILL -> {
                        s.pill(targetId ?: "")?.let {
                            startX = it.x
                            startY = it.y
                        }
                        armed = true
                        select(Kind.PILL, targetId)
                    }
                    null -> Unit
                }
                host.invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                val dx = x - downX
                val dy = y - downY
                if (!moved && dx * dx + dy * dy > touchSlop.toFloat() * touchSlop) {
                    moved = true
                    handler.removeCallbacks(holdClock)
                    handler.removeCallbacks(holdWidget)
                }
                if (resizing) {
                    val base = min(phoneRect.width(), phoneRect.height())
                    val delta = (dy + dx) / (base * 0.35f)
                    s.clockScale = (startScale + delta).coerceIn(LockLayoutSettings.MIN_CLOCK_SCALE, fitMaxScale(s))
                    host.invalidate()
                    return true
                }
                if (moved && armed) {
                    dragTo(s, x, y)
                    host.invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!tracking) return false
                handler.removeCallbacks(holdClock)
                handler.removeCallbacks(holdWidget)
                val edited = resizing || (armed && moved)
                if (resizing) {
                    val base = min(phoneRect.width(), phoneRect.height())
                    val delta = ((y - downY) + (x - downX)) / (base * 0.35f)
                    s.clockScale = (startScale + delta).coerceIn(LockLayoutSettings.MIN_CLOCK_SCALE, fitMaxScale(s))
                    clampClock(s)
                }
                resizing = false
                armed = false
                tracking = false
                guideX = -1f
                guideY = -1f
                val tapped = !moved && !holdFired && event.actionMasked == MotionEvent.ACTION_UP
                when {
                    edited -> listener?.onLayoutEdited()
                    tapped && target == Kind.CLOCK -> listener?.onClockTapped()
                    tapped && target == Kind.WIDGET -> targetId?.let { listener?.onWidgetTapped(it) }
                    tapped && target == Kind.PILL -> targetId?.let { listener?.onPillTapped(it) }
                }
                target = null
                targetId = null
                host.invalidate()
                return true
            }
        }
        return false
    }

    /** 在手机框局部坐标系内画可编辑元素与选中态；由预览绘制层调用（框位置由外部注入）。 */
    fun draw(canvas: Canvas, w: Float, h: Float) {
        widgetRects.clear()
        pillRects.clear()
        val s = settings ?: return
        if (state != SceneState.AOD && state != SceneState.LOCK) return
        drawWidgets(canvas, s, w, h)
        if (state == SceneState.LOCK) drawPills(canvas, s, w, h)
        if (selection != null) drawSelection(canvas, s, w, h)
        drawGuides(canvas, w, h)
    }

    /** 编辑模式下接管时钟绘制：用布局配置的位置/字体/颜色画预览时钟。 */
    fun drawClockOverride(canvas: Canvas, w: Float, h: Float, time: String, date: String): Boolean {
        val s = settings ?: return false
        if (state != SceneState.AOD && state != SceneState.LOCK) return false
        drawClock(canvas, s, w, h, time, date)
        return true
    }

    /** 静态预览也按布局配置摆时钟（未进编辑模式时同样生效）。 */
    fun clockPose(): Pair<Float, Float>? {
        val s = settings ?: return null
        return s.clockX to s.clockY
    }

    /** 布局位置相对默认锚点的偏移；转场期按边插值，保证起止都落在用户设置的位置。 */
    fun clockOffset(edge: com.coloros16.liquidglass.engine.TransitionEdge?, t: Float): Pair<Float, Float> {
        if (edge == null) return 0f to 0f
        val from = offsetOf(edge.from)
        val to = offsetOf(edge.to)
        val e = t.coerceIn(0f, 1f)
        return (from.first + (to.first - from.first) * e) to
            (from.second + (to.second - from.second) * e)
    }

    /** 场景锚点（预览时钟默认位）由外部注入；布局偏移=用户位-锚点位。 */
    var anchorOf: ((SceneState) -> Pair<Float, Float>)? = null

    private fun offsetOf(state: SceneState): Pair<Float, Float> {
        val s = when (state) {
            SceneState.AOD -> aodSettings
            SceneState.LOCK -> lockSettings
            SceneState.HOME -> null
        } ?: return 0f to 0f
        if (!com.coloros16.liquidglass.engine.edit.EditAnchorOverlay.isDefaultPosition(s)) {
            val def = anchorOf?.invoke(state)
            val defX = def?.first ?: 0.5f
            val defY = def?.second ?: (if (state == SceneState.AOD) AOD_DEFAULT_Y else LOCK_DEFAULT_Y)
            return (s.clockX - defX) to (s.clockY - defY)
        }
        return 0f to 0f
    }

    private fun drawClock(canvas: Canvas, s: LockLayoutSettings, w: Float, h: Float, time: String, date: String) {
        val off = offsetOf(state)
        val anchor = anchorOf?.invoke(state)
        val ax = anchor?.first ?: 0.5f
        val ay = anchor?.second ?: (if (state == SceneState.AOD) AOD_DEFAULT_Y else LOCK_DEFAULT_Y)
        drawClockAt(
            canvas, w, h,
            (ax + off.first) * w, (ay + off.second) * h,
            s.clockScale, s.clock.colorArgb, typefaceOf(s), s.clock.letterSpacing, s.clock.info,
            time, date, 1f, 0f,
        )
    }

    /** 转场期时钟：位置/字号/颜色由调用方插值，命中框同步写回，静态与转场同源不跳变。 */
    fun drawSceneClockAt(
        canvas: Canvas,
        w: Float,
        h: Float,
        cx: Float,
        cy: Float,
        alpha: Float,
        rotationDeg: Float,
        edge: com.coloros16.liquidglass.engine.TransitionEdge?,
        t: Float,
        time: String,
        date: String,
        poseScale: Float = 1f,
    ): Boolean {
        val fromState = edge?.from ?: state
        val toState = edge?.to ?: state
        if (fromState == SceneState.HOME && toState == SceneState.HOME) return false
        val from = layoutOf(fromState)
        val to = layoutOf(toState)
        val base = from ?: to ?: return false
        val e = t.coerceIn(0f, 1f)
        val wLock = when {
            fromState == SceneState.HOME -> 0f
            toState == SceneState.HOME -> 1f - e
            fromState == SceneState.LOCK -> 1f
            toState == SceneState.LOCK -> e
            else -> 0f
        }
        val wAod = when {
            fromState == SceneState.HOME -> 0f
            toState == SceneState.HOME -> 0f
            fromState == SceneState.AOD -> 1f
            toState == SceneState.AOD -> e
            else -> 0f
        }
        val total = wLock + wAod
        if (total <= 0.001f) return false
        val lockScale = lockSettings?.clockScale ?: base.clockScale
        val aodScale = aodSettings?.clockScale ?: base.clockScale
        val scale = poseScale * (lockScale * wLock + aodScale * wAod) / total
        val lockColor = lockSettings?.clock?.colorArgb ?: base.clock.colorArgb
        val aodColor = aodSettings?.clock?.colorArgb ?: base.clock.colorArgb
        val color = blendWeighted(lockColor, aodColor, wLock / total)
        val dom = if (wLock >= wAod) (lockSettings ?: base) else (aodSettings ?: base)
        val layer = if (alpha < 0.999f) {
            canvas.saveLayerAlpha(0f, 0f, w, h, (alpha.coerceIn(0f, 1f) * 255f).toInt())
        } else {
            -1
        }
        val rotated = if (rotationDeg != 0f) {
            canvas.save().also { canvas.rotate(rotationDeg, cx, cy) }
        } else {
            -1
        }
        drawClockAt(canvas, w, h, cx, cy, scale, color, typefaceOf(dom), dom.clock.letterSpacing, dom.clock.info, time, date, alpha, rotationDeg)
        if (rotated >= 0) canvas.restoreToCount(rotated)
        if (layer >= 0) canvas.restoreToCount(layer)
        return true
    }

    private fun layoutOf(state: SceneState): LockLayoutSettings? = when (state) {
        SceneState.AOD -> aodSettings
        SceneState.LOCK -> lockSettings
        SceneState.HOME -> null
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun blendWeighted(a: Int, b: Int, wa: Float): Int = lerpColor(a, b, 1f - wa)

    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        val q = t.coerceIn(0f, 1f)
        fun ch(shift: Int): Int {
            val x = (a shr shift) and 0xFF
            val y = (b shr shift) and 0xFF
            return (x + ((y - x) * q).toInt()) and 0xFF
        }
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun drawClockAt(
        canvas: Canvas,
        w: Float,
        h: Float,
        cx: Float,
        cy: Float,
        scale: Float,
        color: Int,
        typeface: Typeface,
        letterSpacing: Float,
        info: ClockInfo,
        time: String,
        date: String,
        alpha: Float,
        rotationDeg: Float,
    ) {
        clockTextSize = ClockLayout.fontPx(w, h, scale)
        clockPaint.textSize = clockTextSize
        clockPaint.typeface = typeface
        clockPaint.letterSpacing = letterSpacing
        clockPaint.color = color
        val metrics = clockPaint.fontMetrics
        val baseline = cy - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(time, cx, baseline, clockPaint)
        subPaint.textSize = clockTextSize * 0.15f
        subPaint.color = (0x99FFFFFF.toInt() and 0x00FFFFFF) or ((color ushr 24) shl 24)
        val dateBaseline = cy + clockTextSize * 0.62f
        canvas.drawText(date, cx, dateBaseline, subPaint)
        val infoText = when (info) {
            ClockInfo.LUNAR -> "农历 十月初五"
            ClockInfo.WEATHER -> "22° 晴"
            ClockInfo.NONE -> null
        }
        if (infoText != null) {
            canvas.drawText(infoText, cx, dateBaseline + subPaint.textSize * 1.6f, subPaint)
        }
        val halfW = clockPaint.measureText(time) / 2f + 12f * density
        val halfH = clockTextSize * 0.45f
        clockBounds.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
    }

    private fun drawWidgets(canvas: Canvas, s: LockLayoutSettings, w: Float, h: Float) {
        for (widget in s.widgets()) {
            if (!widget.enabled) continue
            val rect = widgetRect(widget, w, h)
            widgetRects.add(widget.typeId to RectF(rect))
            val radius = 12f * density
            canvas.drawRoundRect(rect, radius, radius, platePaint)
            canvas.drawRoundRect(rect, radius, radius, strokePaint)
            val name = widgetName(widget.typeId)
            widgetTextPaint.textSize = min(10.5f * density, rect.width() / (name.length + 1f))
            canvas.drawText(name, rect.centerX(), rect.centerY() + widgetTextPaint.textSize * 0.35f, widgetTextPaint)
            if (selection == Kind.WIDGET && selectionId == widget.typeId) {
                drawRemoveBadge(canvas, rect)
            }
        }
    }

    private fun drawPills(canvas: Canvas, s: LockLayoutSettings, w: Float, h: Float) {
        for (pill in s.pills()) {
            if (!pill.enabled) continue
            val rect = pillRect(pill, w, h)
            pillRects.add(pill.typeId to RectF(rect))
            canvas.drawCircle(rect.centerX(), rect.centerY(), rect.width() / 2f, platePaint)
            canvas.drawCircle(rect.centerX(), rect.centerY(), rect.width() / 2f, strokePaint)
            when (contentOf(pill.typeId)) {
                "pill.none" -> Unit
                "pill.camera" -> drawCamera(canvas, rect)
                "pill.dial" -> drawDial(canvas, rect)
                "pill.recorder" -> drawRecorder(canvas, rect)
                "pill.calculator" -> drawCalculator(canvas, rect)
                "pill.scan" -> drawScan(canvas, rect)
                "pill.pay" -> drawPay(canvas, rect)
                else -> drawTorch(canvas, rect)
            }
        }
    }

    private fun drawTorch(canvas: Canvas, rect: RectF) {
        val c = rect.centerX()
        val cy = rect.centerY()
        val k = rect.width() / (58f * density)
        val body = RectF(c - 4f * density * k, cy - 8f * density * k, c + 4f * density * k, cy + 6f * density * k)
        canvas.drawRoundRect(body, 2f * density, 2f * density, widgetTextPaint)
    }

    private fun drawCamera(canvas: Canvas, rect: RectF) {
        val c = rect.centerX()
        val cy = rect.centerY()
        val k = rect.width() / (58f * density)
        val body = RectF(c - 10f * density * k, cy - 7f * density * k, c + 10f * density * k, cy + 7f * density * k)
        canvas.drawRoundRect(body, 3f * density, 3f * density, strokePaint)
        canvas.drawCircle(c, cy, 4f * density * k, strokePaint)
    }

    private fun drawDial(canvas: Canvas, rect: RectF) {
        val c = rect.centerX()
        val cy = rect.centerY()
        val k = rect.width() / (58f * density)
        val p = Paint(strokePaint)
        p.strokeWidth = 2.4f * density * k
        p.strokeCap = Paint.Cap.ROUND
        val path = android.graphics.Path()
        path.moveTo(c - 7f * density * k, cy - 7f * density * k)
        path.cubicTo(c - 9f * density * k, cy + 2f * density * k, c - 2f * density * k, cy + 9f * density * k, c + 7f * density * k, cy + 7f * density * k)
        canvas.drawPath(path, p)
    }

    private fun drawRecorder(canvas: Canvas, rect: RectF) {
        val c = rect.centerX()
        val cy = rect.centerY()
        val k = rect.width() / (58f * density)
        val bars = floatArrayOf(3f, 7f, 10f, 7f, 3f)
        for (i in bars.indices) {
            val x = c + (i - 2) * 4f * density * k
            val h = bars[i] * density * k
            canvas.drawRoundRect(RectF(x - 1.2f * density * k, cy - h, x + 1.2f * density * k, cy + h), 1.2f * density * k, 1.2f * density * k, widgetTextPaint)
        }
    }

    private fun drawCalculator(canvas: Canvas, rect: RectF) {
        val c = rect.centerX()
        val cy = rect.centerY()
        val k = rect.width() / (58f * density)
        val p = Paint(strokePaint)
        p.strokeWidth = 1.8f * density * k
        p.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(c - 7f * density * k, cy - 4f * density * k, c - 1f * density * k, cy - 4f * density * k, p)
        canvas.drawLine(c - 4f * density * k, cy - 7f * density * k, c - 4f * density * k, cy - 1f * density * k, p)
        canvas.drawLine(c + 2f * density * k, cy - 4f * density * k, c + 8f * density * k, cy - 4f * density * k, p)
        canvas.drawLine(c - 7f * density * k, cy + 4f * density * k, c - 1f * density * k, cy + 4f * density * k, p)
        canvas.drawLine(c + 2f * density * k, cy + 2f * density * k, c + 8f * density * k, cy + 2f * density * k, p)
        canvas.drawLine(c + 2f * density * k, cy + 6f * density * k, c + 8f * density * k, cy + 6f * density * k, p)
    }

    private fun drawScan(canvas: Canvas, rect: RectF) {
        val c = rect.centerX()
        val cy = rect.centerY()
        val k = rect.width() / (58f * density)
        val p = Paint(strokePaint)
        p.strokeWidth = 1.8f * density * k
        p.strokeCap = Paint.Cap.ROUND
        val r = 8f * density * k
        val l = 4f * density * k
        for (sx in floatArrayOf(-1f, 1f)) for (sy in floatArrayOf(-1f, 1f)) {
            canvas.drawLine(c + sx * r, cy + sy * r, c + sx * (r - l), cy + sy * r, p)
            canvas.drawLine(c + sx * r, cy + sy * r, c + sx * r, cy + sy * (r - l), p)
        }
        canvas.drawLine(c - r, cy, c + r, cy, p)
    }

    private fun drawPay(canvas: Canvas, rect: RectF) {
        val c = rect.centerX()
        val cy = rect.centerY()
        val k = rect.width() / (58f * density)
        val p = Paint(strokePaint)
        p.strokeWidth = 1.8f * density * k
        p.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(c - 6f * density * k, cy - 6f * density * k, c, cy - 1f * density * k, p)
        canvas.drawLine(c + 6f * density * k, cy - 6f * density * k, c, cy - 1f * density * k, p)
        canvas.drawLine(c, cy - 1f * density * k, c, cy + 7f * density * k, p)
        canvas.drawLine(c - 5f * density * k, cy + 1f * density * k, c + 5f * density * k, cy + 1f * density * k, p)
        canvas.drawLine(c - 5f * density * k, cy + 4f * density * k, c + 5f * density * k, cy + 4f * density * k, p)
    }

    private fun drawRemoveBadge(canvas: Canvas, rect: RectF) {
        val r = 9f * density
        val cx = rect.left + r * 0.4f
        val cy = rect.top + r * 0.4f
        canvas.drawCircle(cx, cy, r, badgePaint)
        canvas.drawLine(cx - r * 0.45f, cy, cx + r * 0.45f, cy, badgeText)
    }

    private fun drawSelection(canvas: Canvas, s: LockLayoutSettings, w: Float, h: Float) {
        val rect: RectF = when (selection) {
            Kind.CLOCK -> { tmpRect.set(clockBounds); tmpRect }
            Kind.WIDGET -> findRect(widgetRects, selectionId) ?: return
            Kind.PILL -> findRect(pillRects, selectionId) ?: return
            null -> return
        }
        if (selection == Kind.PILL) {
            canvas.drawCircle(rect.centerX(), rect.centerY(), rect.width() / 2f + 5f * density, selectPaint)
        } else {
            canvas.drawRoundRect(rect, 10f * density, 10f * density, selectPaint)
        }
        if (selection == Kind.CLOCK) {
            val r = 4.5f * density
            val xs = floatArrayOf(rect.left, rect.centerX(), rect.right)
            val ys = floatArrayOf(rect.top, rect.centerY(), rect.bottom)
            for (fx in xs) for (fy in ys) {
                if (fx == rect.centerX() && fy == rect.centerY()) continue
                canvas.drawRect(fx - r, fy - r, fx + r, fy + r, handlePaint)
                canvas.drawRect(fx - r, fy - r, fx + r, fy + r, handleStroke)
            }
            val hookR = 8f * density
            canvas.drawCircle(rect.right + hookR * 0.4f, rect.bottom + hookR * 0.4f, hookR, handlePaint)
            canvas.drawCircle(rect.right + hookR * 0.4f, rect.bottom + hookR * 0.4f, hookR, handleStroke)
        }
    }

    private fun drawGuides(canvas: Canvas, w: Float, h: Float) {
        if (guideX >= 0f) canvas.drawLine(guideX * w, 0f, guideX * w, h, guidePaint)
        if (guideY >= 0f) canvas.drawLine(0f, guideY * h, w, guideY * h, guidePaint)
    }

    private fun findRect(list: List<Pair<String, RectF>>, id: String?): RectF? =
        list.firstOrNull { it.first == id }?.second

    private fun select(kind: Kind, id: String?) {
        selection = kind
        selectionId = id
        listener?.onSelectionChanged(true)
    }

    private fun pick(x: Float, y: Float, s: LockLayoutSettings): Boolean {
        if (selection == Kind.WIDGET) {
            findRect(widgetRects, selectionId)?.let { rect ->
                val r = 14f * density
                if (abs(x - rect.left) <= r && abs(y - rect.top) <= r) {
                    target = Kind.WIDGET
                    targetId = selectionId
                    holdFired = true
                    handler.post { selectionId?.let { listener?.onWidgetHold(it) } }
                    return true
                }
            }
        }
        for ((id, rect) in widgetRects.asReversed()) {
            if (rect.contains(x, y)) {
                target = Kind.WIDGET
                targetId = id
                return true
            }
        }
        if (state == SceneState.LOCK) {
            for ((id, rect) in pillRects.asReversed()) {
                if (rect.contains(x, y)) {
                    target = Kind.PILL
                    targetId = id
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
        val w = phoneRect.width()
        val h = phoneRect.height()
        val nx = (startX + (x - downX) / w).coerceIn(0f, 1f)
        val ny = (startY + (y - downY) / h).coerceIn(0f, 1f)
        val axis = surface.axis
        when (target) {
            Kind.CLOCK -> {
                val cx = clampCenterX(nx, clockBounds.width() / 2f, w, clockEdgeMargin)
                val cy = if (axis == EditAxis.HORIZONTAL_ONLY) {
                    s.clockY
                } else {
                    clampCenterY(ny, clockBounds.height() / 2f, h, clockEdgeMargin)
                }
                val result = s.moveClock(cx, cy, if (s.snapEnabled) SnapEngine.DEFAULT_THRESHOLD else 0f)
                guideX = result.guideXPosition
                guideY = result.guideYPosition
            }
            Kind.WIDGET -> {
                val widget = s.widget(targetId ?: "") ?: return
                solverGrid.solve(w, h, EditLayoutSolver.WIDGET_GRID_TOP)
                val vx = startVX + (x - downX)
                val vy = if (axis == EditAxis.HORIZONTAL_ONLY) startVY else startVY + (y - downY)
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
                    clampCenterX(nx, half, w),
                    clampCenterY(ny, half, h),
                    SnapEngine.guides(surface), axis,
                    if (s.snapEnabled) SnapEngine.DEFAULT_THRESHOLD else 0f,
                )
                s.setPill(pill.copy(x = result.x, y = result.y))
                guideX = result.guideXPosition
                guideY = result.guideYPosition
            }
            null -> Unit
        }
    }

    private fun fitMaxScale(s: LockLayoutSettings): Float {
        val w = phoneRect.width()
        val drawn = clockBounds.width() - 24f * density
        if (w <= 0f || s.clockScale <= 0f || drawn <= 0f) return LockLayoutSettings.MAX_CLOCK_SCALE
        return ClockLayout.fitScale(drawn, s.clockScale, w * (1f - 2f * clockEdgeMargin))
    }

    private fun clampClock(s: LockLayoutSettings) {
        val w = phoneRect.width()
        val h = phoneRect.height()
        if (w <= 0f || h <= 0f) return
        s.clockX = clampCenterX(s.clockX, clockBounds.width() / 2f, w, clockEdgeMargin)
        if (surface.axis == EditAxis.FREE) {
            s.clockY = clampCenterY(s.clockY, clockBounds.height() / 2f, h, clockEdgeMargin)
        }
    }

    private fun clampCenterX(value: Float, halfPx: Float, w: Float, margin: Float = 0f): Float {
        if (w <= 0f || halfPx <= 0f) return value
        val half = halfPx / w + margin
        return if (half * 2f >= 1f) 0.5f else value.coerceIn(half, 1f - half)
    }

    private fun clampCenterY(value: Float, halfPx: Float, h: Float, margin: Float = 0f): Float {
        if (h <= 0f || halfPx <= 0f) return value
        val half = halfPx / h + margin
        return if (half * 2f >= 1f) 0.5f else value.coerceIn(half, 1f - half)
    }

    private fun nearHandle(x: Float, y: Float, rect: RectF): Boolean {
        val r = 24f * density
        return abs(x - (rect.right + 3f * density)) <= r && abs(y - (rect.bottom + 3f * density)) <= r
    }

    private fun widgetRect(widget: LockWidget, w: Float, h: Float): RectF {
        if (!EditLayoutSolver.widgetRect(widget, w, h, EditLayoutSolver.WIDGET_GRID_TOP, solverGrid, solverRect)) {
            return RectF()
        }
        EditLayoutSolver.clampInto(solverRect, w, h)
        return RectF(solverRect.left, solverRect.top, solverRect.right, solverRect.bottom)
    }

    private fun pillRect(pill: PillPlacement, w: Float, h: Float): RectF {
        val size = EditLayoutSolver.pillDiameterPx(w, h, pill.scale)
        val cx = pill.x * w
        val cy = pill.y * h
        return RectF(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
    }

    private fun widgetName(typeId: String): String {
        val key = WidgetCatalog.find(typeId)?.nameKey ?: return typeId
        val res = host.resources.getIdentifier(key, "string", host.context.packageName)
        return if (res != 0) host.context.getString(res) else typeId
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
}