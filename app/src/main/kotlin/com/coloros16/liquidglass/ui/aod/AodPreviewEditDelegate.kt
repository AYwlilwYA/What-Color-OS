package com.coloros16.liquidglass.ui.aod

import android.graphics.Canvas
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
import com.coloros16.liquidglass.engine.SceneSkyMath
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.from
import com.coloros16.liquidglass.engine.to
import com.coloros16.liquidglass.engine.edit.ClockFonts
import com.coloros16.liquidglass.engine.edit.ClockInfo
import com.coloros16.liquidglass.engine.edit.ClockLayout
import com.coloros16.liquidglass.engine.edit.ClockStyle
import com.coloros16.liquidglass.engine.edit.EditAxis
import com.coloros16.liquidglass.engine.edit.EditLayoutSolver
import com.coloros16.liquidglass.engine.edit.EditSurface
import com.coloros16.liquidglass.engine.edit.LockLayoutSettings
import com.coloros16.liquidglass.engine.edit.LockWidget
import com.coloros16.liquidglass.engine.edit.PillPlacement
import com.coloros16.liquidglass.engine.edit.SnapEngine
import com.coloros16.liquidglass.engine.edit.WidgetCatalog
import com.coloros16.liquidglass.log.Lg
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

        /** 点大丸横带空位：在该位置新增一个图标（归一化坐标，位置由用户点决定）。 */
        fun onPillSlotTapped(xFrac: Float, yFrac: Float)
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

    /** 已选中元素即处于编辑态：此时防烧屏漂移必须停，否则手柄锚点跟着漂。 */
    val editing: Boolean get() = interactive && selection != null

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
    private var startStretchX = 1f
    private var startStretchY = 1f
    private var resizeAnchorLeft = 0f
    private var resizeAnchorTop = 0f
    private var resizeAnchorHalfW = 0f
    private var resizeAnchorHalfH = 0f
    private var lastTimeText = "10:08"
    private var guideX = -1f
    private var guideY = -1f

    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(host.context).scaledTouchSlop
    private val density = host.resources.displayMetrics.density
    private val clockEdgeMargin = 0.05f

    private companion object {
        const val TAG = "LgAodEdit"
        const val AOD_DEFAULT_Y = 0.435f
        const val LOCK_DEFAULT_Y = 0.155f
        const val DOCK_BAND_TOP = 0.74f
        const val DOCK_BAND_BOTTOM = 0.99f
    }

    private val clockBounds = RectF()
    private var lastPlaceLogAt = 0L
    private var lastOversizeLogAt = 0L
    private var clockTextSize = 0f
    private val widgetRects = ArrayList<Pair<String, RectF>>()
    private val pillRects = ArrayList<Pair<String, RectF>>()
    private val tmpRect = RectF()
    private val solverGrid = EditLayoutSolver.Grid()
    private val solverRect = EditLayoutSolver.Rect()
    private val pillSolverRect = EditLayoutSolver.Rect()

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
    private val selectPaint = EditChromePainter.selectPaint(density, 0xFF9FC2FF.toInt())
    private val guidePaint = EditChromePainter.guidePaint(density, 0xFF9FC2FF.toInt())
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFF2F6FB.toInt()
    }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x66000000
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

    /** 从设置行进来时直接选中时钟，手柄立刻可见可拖。 */
    fun selectClock() {
        if (settings == null) {
            Lg.w(TAG, "select clock skipped no layout state=$state")
            return
        }
        cancelTouch()
        select(Kind.CLOCK, null)
        host.invalidate()
        Lg.i(TAG, "select clock inline state=$state")
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
                // 存量配置可能带着超框拉伸进来，手柄那时已经不在可点范围，先收一次再判命中
                ensureStretchFits(s)
                if (selection == Kind.CLOCK) logHandleHit(x, y)
                if (selection == Kind.CLOCK && nearHandle(x, y, clockBounds)) {
                    resizing = true
                    target = Kind.CLOCK
                    tracking = true
                    startStretchX = s.clockStretchX
                    startStretchY = s.clockStretchY
                    resizeAnchorLeft = clockBounds.left
                    resizeAnchorTop = clockBounds.top
                    resizeAnchorHalfW = clockBounds.width() / 2f
                    resizeAnchorHalfH = clockBounds.height() / 2f
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
                        val c = clockCenterPx(s, phoneRect.width(), phoneRect.height())
                        startX = c.first / phoneRect.width()
                        startY = c.second / phoneRect.height()
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
                    resizeClock(s, dx, dy)
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
                    resizeClock(s, x - downX, y - downY)
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
                    // 大丸横带内的空白处：在该位置新增图标（横带外不响应，避免画布空白处误弹面板）
                    tapped && target == null && inDockBand(y, phoneRect.height()) ->
                        listener?.onPillSlotTapped(
                            (x / phoneRect.width()).coerceIn(0f, 1f),
                            (y / phoneRect.height()).coerceIn(0f, 1f),
                        )
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
    fun drawClockOverride(
        canvas: Canvas,
        w: Float,
        h: Float,
        time: String,
        date: String,
        sceneColor: Int = 0,
    ): Boolean {
        val s = settings ?: return false
        if (state != SceneState.AOD && state != SceneState.LOCK) return false
        lastTimeText = time
        drawClock(canvas, s, w, h, time, date, sceneColor)
        return true
    }

    /**
     * 布局位置相对默认锚点的偏移；静止态取当前态，转场期按边插值。
     *
     * 进度必须与状态机 pose 同曲线：播放走该边系统曲线，拖动走线性。
     */
    fun clockOffset(
        edge: com.coloros16.liquidglass.engine.TransitionEdge?,
        t: Float,
        direct: Boolean = false,
    ): Pair<Float, Float> {
        if (edge == null) return offsetOf(state)
        val from = offsetOf(edge.from)
        val to = offsetOf(edge.to)
        val e = clockProgress(edge, t, direct)
        return (from.first + (to.first - from.first) * e) to
            (from.second + (to.second - from.second) * e)
    }

    /** 时钟专用进度：与 `ClockStateMachine.onTransition` 同一条曲线，拖动时 1:1。 */
    fun clockProgress(
        edge: com.coloros16.liquidglass.engine.TransitionEdge?,
        t: Float,
        direct: Boolean,
    ): Float {
        val q = t.coerceIn(0f, 1f)
        if (direct || edge == null) return q
        return com.coloros16.liquidglass.engine.SystemSceneMotion.clockCurve(edge).map(q)
    }

    /** 场景锚点（预览时钟默认位）由外部注入；布局偏移=用户位-锚点位。 */
    var anchorOf: ((SceneState) -> Pair<Float, Float>)? = null

    private fun offsetOf(state: SceneState): Pair<Float, Float> {
        val s = when (state) {
            SceneState.AOD -> aodSettings
            SceneState.LOCK -> lockSettings
            SceneState.HOME -> null
        } ?: return 0f to 0f
        val overlay = com.coloros16.liquidglass.engine.edit.EditAnchorOverlay
        val def = anchorOf?.invoke(state)
        val defX = def?.first ?: overlay.defaultX()
        val defY = def?.second ?: overlay.defaultYOf(surfaceOf(state))
        val dx = ClockLayout.configToVisual(s.clockX, defX, overlay.defaultX()) - defX
        val dy = ClockLayout.configToVisual(s.clockY, defY, overlay.defaultYOf(surfaceOf(state))) - defY
        return dx to dy
    }

    private fun surfaceOf(state: SceneState): EditSurface =
        if (state == SceneState.AOD) EditSurface.AOD else EditSurface.LOCK

    /** 时钟当前实际绘制中心（手机框局部 px）：场景锚点 + 逐轴布局偏移。 */
    private fun clockCenterPx(s: LockLayoutSettings, w: Float, h: Float): Pair<Float, Float> {
        val anchor = anchorOf?.invoke(state)
        val overlay = com.coloros16.liquidglass.engine.edit.EditAnchorOverlay
        val ax = anchor?.first ?: overlay.defaultX()
        val ay = anchor?.second ?: overlay.defaultYOf(surface)
        return ClockLayout.configToVisual(s.clockX, ax, overlay.defaultX()) * w to
            ClockLayout.configToVisual(s.clockY, ay, overlay.defaultYOf(surface)) * h
    }

    private fun drawClock(
        canvas: Canvas,
        s: LockLayoutSettings,
        w: Float,
        h: Float,
        time: String,
        date: String,
        sceneColor: Int = 0,
    ) {
        val center = clockCenterPx(s, w, h)
        drawClockAt(
            canvas, w, h,
            center.first, center.second,
            s.clockScale, colorOf(s, sceneColor), typefaceOf(s), s.clock.letterSpacing, s.clock.info,
            time, date, 1f, 0f, s.clockStretchX, s.clockStretchY,
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
        sceneColor: Int = 0,
        direct: Boolean = false,
    ): Boolean {
        lastTimeText = time
        val fromState = edge?.from ?: state
        val toState = edge?.to ?: state
        if (fromState == SceneState.HOME && toState == SceneState.HOME) return false
        val base = layoutOf(fromState) ?: layoutOf(toState) ?: return false
        val e = clockProgress(edge, t, direct)
        val fromSettings = layoutOf(fromState) ?: base
        val toSettings = layoutOf(toState) ?: base
        val scale = poseScale * lerp(fromSettings.clockScale, toSettings.clockScale, e)
        val stretchX = lerp(fromSettings.clockStretchX, toSettings.clockStretchX, e)
        val stretchY = lerp(fromSettings.clockStretchY, toSettings.clockStretchY, e)
        val color = lerpColor(colorOf(fromSettings, sceneColor), colorOf(toSettings, sceneColor), e)
        val dom = if (e < 0.5f) fromSettings else toSettings
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
        drawClockAt(
            canvas, w, h, cx, cy, scale, color, typefaceOf(dom), dom.clock.letterSpacing,
            dom.clock.info, time, date, alpha, rotationDeg, stretchX, stretchY,
        )
        if (rotated >= 0) canvas.restoreToCount(rotated)
        if (layer >= 0) canvas.restoreToCount(layer)
        return true
    }

    /** 未自定义颜色（默认白）时用调用方给的三态色，自定义过则尊重用户值。 */
    private fun colorOf(s: LockLayoutSettings, sceneColor: Int): Int =
        if (sceneColor != 0 && s.clock.colorArgb == ClockStyle.WHITE) sceneColor else s.clock.colorArgb

    /** 当前姿态里息屏端的权重：静止态取端值，转场期按边与进度线性插值。 */
    fun aodWeight(edge: com.coloros16.liquidglass.engine.TransitionEdge?, t: Float): Float =
        com.coloros16.liquidglass.engine.AodClockEntry.weightOf(edge, t, state)

    private fun layoutOf(state: SceneState): LockLayoutSettings? = when (state) {
        SceneState.AOD -> aodSettings
        SceneState.LOCK -> lockSettings
        SceneState.HOME -> null
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t


    private fun lerpColor(a: Int, b: Int, t: Float): Int = SceneSkyMath.lerpColor(a, b, t)

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
        stretchX: Float = 1f,
        stretchY: Float = 1f,
    ) {
        clockTextSize = ClockGlyphPainter.draw(
            canvas, cx, cy, ClockLayout.fontPx(w, h, scale),
            time, date, color, EditChromePainter.DATE_COLOR,
            typeface, dateTypeface(), letterSpacing, info,
            clockPaint, subPaint, clockBounds, EditChromePainter.CLOCK_PAD_DP * density,
            stretchX, stretchY,
        )
    }

    private fun drawWidgets(canvas: Canvas, s: LockLayoutSettings, w: Float, h: Float) {
        for (widget in s.widgets()) {
            if (!widget.enabled) continue
            val rect = widgetRect(widget, w, h)
            widgetRects.add(widget.typeId to RectF(rect))
            WidgetPlatePainter.draw(canvas, rect, widgetName(widget.typeId), density, platePaint, strokePaint, widgetTextPaint)
        }
    }

    private fun drawPills(canvas: Canvas, s: LockLayoutSettings, w: Float, h: Float) {
        // 大丸每个槽的 typeId 与矩形必须能取证：只数图元分不清「八种都画对」与「全画成同一个」
        val traced = StringBuilder()
        for (pill in s.pills()) {
            if (!pill.enabled) continue
            val rect = pillRect(pill, w, h)
            pillRects.add(pill.typeId to RectF(rect))
            traced.append(pill.typeId).append('@')
                .append(rect.centerX().toInt()).append(',').append(rect.centerY().toInt()).append(' ')
            canvas.drawCircle(rect.centerX(), rect.centerY(), rect.width() / 2f, platePaint)
            canvas.drawCircle(rect.centerX(), rect.centerY(), rect.width() / 2f, strokePaint)
            PillGlyphPainter.draw(canvas, rect, contentOf(pill.typeId), density, strokePaint, widgetTextPaint)
        }
        if (traced.isNotEmpty()) Lg.i(TAG, "pill-slots $traced")
    }

    private fun drawSelection(canvas: Canvas, s: LockLayoutSettings, w: Float, h: Float) {
        val rect: RectF = when (selection) {
            Kind.CLOCK -> { tmpRect.set(clockBounds); tmpRect }
            Kind.WIDGET -> findRect(widgetRects, selectionId) ?: return
            Kind.PILL -> findRect(pillRects, selectionId) ?: return
            null -> return
        }
        EditChromePainter.drawSelection(canvas, rect, selection == Kind.PILL, density, selectPaint)
        if (selection == Kind.CLOCK) {
            EditChromePainter.drawClockHandles(canvas, rect, density, handlePaint, handleStroke)
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
                val overlay = com.coloros16.liquidglass.engine.edit.EditAnchorOverlay
                val anchor = anchorOf?.invoke(state)
                val ax = anchor?.first ?: overlay.defaultX()
                val ay = anchor?.second ?: overlay.defaultYOf(surface)
                val visualCx = clampVisualCenter(nx, clockBounds.width() / 2f, w)
                val visualCy = if (axis == EditAxis.HORIZONTAL_ONLY) {
                    clockBounds.centerY() / h
                } else {
                    clampVisualCenter(ny, clockBounds.height() / 2f, h)
                }
                val result = s.moveClock(
                    ClockLayout.visualToConfig(visualCx, ax, overlay.defaultX()),
                    ClockLayout.visualToConfig(visualCy, ay, overlay.defaultYOf(surface)),
                    if (s.snapEnabled) SnapEngine.DEFAULT_THRESHOLD else 0f,
                )
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

    /** 右下角手柄自由变形：横向位移拉宽、纵向位移拉高，左上角钉死。 */
    private fun resizeClock(s: LockLayoutSettings, dx: Float, dy: Float) {
        val w = phoneRect.width()
        val h = phoneRect.height()
        if (w <= 0f || h <= 0f) return
        val next = ClockLayout.resizeStretch(
            startStretchX, startStretchY, dx, dy, resizeAnchorHalfW, resizeAnchorHalfH,
        )
        s.clockStretchX = next[0]
        s.clockStretchY = next[1]
        measureClockBounds(s, w, h)
        // 钩心出屏才是真自锁：手柄是唯一收窄入口，超手机框但钩心仍在屏内时不动，
        // 否则用户放大时钟后随便点一下就被压窄（框超框不一定是拉伸造成的）。
        val fit = ClockLayout.fitStretch(
            s.clockStretchX, s.clockStretchY, clockBounds.width(), clockBounds.height(),
            2f * EditChromePainter.CLOCK_PAD_DP * density, hookReachW(w), h,
        )
        if (fit[0] != s.clockStretchX || fit[1] != s.clockStretchY) {
            s.clockStretchX = fit[0]
            s.clockStretchY = fit[1]
            measureClockBounds(s, w, h)
        }
        placeClockTopLeft(s, resizeAnchorLeft, resizeAnchorTop, w, h)
    }

    /**
     * 按「框左上角落在该点」反算并写回配置中心。
     *
     * 绘制中心 = 场景锚点 + 逐轴布局偏移，故配置中心须减掉锚点再加回默认值；
     * 直接用框中心当归一化坐标会让时钟跳到别处（锚点不在 0.5/默认 Y 时）。
     */
    private fun placeClockTopLeft(s: LockLayoutSettings, left: Float, top: Float, w: Float, h: Float) {
        val overlay = com.coloros16.liquidglass.engine.edit.EditAnchorOverlay
        val anchor = anchorOf?.invoke(state)
        val ax = anchor?.first ?: overlay.defaultX()
        val ay = anchor?.second ?: overlay.defaultYOf(surface)
        val cx = left + clockBounds.width() / 2f
        val cy = top + clockBounds.height() / 2f
        s.clockX = ClockLayout.visualToConfig(cx / w, ax, overlay.defaultX())
        s.clockY = ClockLayout.visualToConfig(cy / h, ay, overlay.defaultYOf(surface))
        logPlace(left, top, cx, cy, s)
    }

    /** 反算链路值：锚点、框左上、写回的配置中心，用于定位跳位。 */
    private fun logPlace(left: Float, top: Float, cx: Float, cy: Float, s: LockLayoutSettings) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastPlaceLogAt < 800L) return
        lastPlaceLogAt = now
        Lg.i(
            TAG,
            "clock-place state=$state anchorL=${"%.1f".format(left)} anchorT=${"%.1f".format(top)} " +
                "center=(${"%.1f".format(cx)},${"%.1f".format(cy)}) " +
                "cfg=(${"%.4f".format(s.clockX)},${"%.4f".format(s.clockY)}) " +
                "stretch=(${"%.3f".format(s.clockStretchX)},${"%.3f".format(s.clockStretchY)})",
        )
    }

    /**
     * 只量框不绘制：中心取「实际绘制中心」而非配置值，命中框与画面才同源。
     *
     * 场景锚点不在 (0.5, 默认Y) 时，配置中心与绘制中心相差一个锚点偏移；
     * 用配置值量框会让手柄与选框画在时钟之外。
     */
    /** 钩心出屏时按比例收拉伸并落盘：手柄是唯一收窄入口，出屏即不可达。 */
    private fun ensureStretchFits(s: LockLayoutSettings) {
        val w = phoneRect.width()
        val h = phoneRect.height()
        if (w <= 0f || h <= 0f || clockBounds.width() <= 0f) return
        val fit = ClockLayout.fitStretch(
            s.clockStretchX, s.clockStretchY, clockBounds.width(), clockBounds.height(),
            2f * EditChromePainter.CLOCK_PAD_DP * density, hookReachW(w), h,
        )
        if (fit[0] == s.clockStretchX && fit[1] == s.clockStretchY) return
        s.clockStretchX = fit[0]
        s.clockStretchY = fit[1]
        measureClockBounds(s, w, h)
        listener?.onLayoutEdited()
        Lg.i(TAG, "clock-stretch fit-on-touch sx=${"%.3f".format(fit[0])} sy=${"%.3f".format(fit[1])}")
    }

    /**
     * 框宽的可达上限：钩心（框右 + 3.2dp）不越过屏幕右沿时，框能有多宽。
     *
     * 预览页画布只有手机框宽，但屏幕比手机框宽，钩心在手机框外仍可点 ⇒
     * 用屏幕右沿而不是手机框右沿做上限，用户才能把时钟拉得足够长。
     */
    private fun hookReachW(phoneW: Float): Float {
        val screenW = host.width.toFloat()
        if (screenW <= 0f) return phoneW
        val reach = screenW - phoneRect.left - EditChromePainter.HOOK_CENTER_PAD_DP * density
        return maxOf(phoneW, reach)
    }

    private fun measureClockBounds(s: LockLayoutSettings, w: Float, h: Float) {
        val size = ClockLayout.fontPx(w, h, s.clockScale)
        clockPaint.textSize = size
        clockPaint.typeface = typefaceOf(s)
        clockPaint.letterSpacing = s.clock.letterSpacing
        clockPaint.textScaleX = s.clockStretchX
        val halfW = clockPaint.measureText(lastTimeText) / 2f + EditChromePainter.CLOCK_PAD_DP * density
        val halfH = size * ClockGlyphPainter.BOUNDS_HALF_HEIGHT_RATIO * s.clockStretchY
        val center = clockCenterPx(s, w, h)
        val cx = center.first
        val cy = center.second
        clockBounds.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
    }

    private fun fitMaxScale(s: LockLayoutSettings): Float {
        val w = phoneRect.width()
        val drawn = clockBounds.width() - 2f * EditChromePainter.CLOCK_PAD_DP * density
        if (w <= 0f || s.clockScale <= 0f || drawn <= 0f) return LockLayoutSettings.MAX_CLOCK_SCALE
        return ClockLayout.fitScale(drawn, s.clockScale, w * (1f - 2f * clockEdgeMargin))
    }

    /** 边界钳位按实际绘制中心做，再换算回配置值；直接钳配置值会让左上锚点漂。 */
    private fun clampClock(s: LockLayoutSettings) {
        val w = phoneRect.width()
        val h = phoneRect.height()
        if (w <= 0f || h <= 0f) return
        val overlay = com.coloros16.liquidglass.engine.edit.EditAnchorOverlay
        val anchor = anchorOf?.invoke(state)
        val ax = anchor?.first ?: overlay.defaultX()
        val ay = anchor?.second ?: overlay.defaultYOf(surface)
        val center = clockCenterPx(s, w, h)
        val vx = clampVisualCenter(center.first / w, clockBounds.width() / 2f, w)
        s.clockX = ClockLayout.visualToConfig(vx, ax, overlay.defaultX())
        if (surface.axis == EditAxis.FREE) {
            val vy = clampVisualCenter(center.second / h, clockBounds.height() / 2f, h)
            s.clockY = ClockLayout.visualToConfig(vy, ay, overlay.defaultYOf(surface))
        }
    }

    /**
     * 拉伸后框比屏还宽时不做边界钳位：钳位会把中心拍回 0.5，左上锚点当场跳走。
     *
     * 只有框还装得下（半宽 + 边距 < 半屏）才钳；装不下就保留用户拖出的中心，
     * 允许溢出屏幕——「拉成非常长」本来就要求能超出屏宽。
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

    private fun logOversize(half: Float, visual: Float) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastOversizeLogAt < 1500L) return
        lastOversizeLogAt = now
        Lg.i(TAG, "clock-clamp skipped oversize half=${"%.3f".format(half)} visual=${"%.3f".format(visual)}")
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

    private var handleHitLogAt = 0L

    /** 手柄命中诊断：报画面坐标与框内坐标，定位点不中时不用猜。 */
    private fun logHandleHit(x: Float, y: Float) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - handleHitLogAt < 500L) return
        handleHitLogAt = now
        Lg.i(
            TAG,
            "clock-hit x=${"%.1f".format(x)} y=${"%.1f".format(y)} " +
                "box=(${"%.1f".format(clockBounds.left)},${"%.1f".format(clockBounds.top)}," +
                "${"%.1f".format(clockBounds.right)},${"%.1f".format(clockBounds.bottom)}) " +
                "near=${nearHandle(x, y, clockBounds)} " +
                "phone=(${"%.1f".format(phoneRect.left)},${"%.1f".format(phoneRect.top)}," +
                "${"%.1f".format(phoneRect.width())},${"%.1f".format(phoneRect.height())})",
        )
    }

    private fun nearHandle(x: Float, y: Float, rect: RectF): Boolean =
        EditChromePainter.nearHook(x, y, rect, density)

    private fun widgetRect(widget: LockWidget, w: Float, h: Float): RectF {
        if (!EditLayoutSolver.widgetRect(widget, w, h, EditLayoutSolver.WIDGET_GRID_TOP, solverGrid, solverRect)) {
            return RectF()
        }
        EditLayoutSolver.clampInto(solverRect, w, h)
        return RectF(solverRect.left, solverRect.top, solverRect.right, solverRect.bottom)
    }

    /** 大丸横带：默认两个槽在 y=0.88，取上下各留一档余量。 */
    private fun inDockBand(y: Float, h: Float): Boolean =
        h > 0f && y / h in DOCK_BAND_TOP..DOCK_BAND_BOTTOM

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
        val res = host.resources.getIdentifier(key, "string", host.context.packageName)
        return if (res != 0) host.context.getString(res) else typeId
    }

    private fun typefaceOf(s: LockLayoutSettings): Typeface =
        ClockTypefaces.timeOf(host.context, s.clock)

    private fun dateTypeface(): Typeface = ClockTypefaces.date()
}