package com.coloros16.liquidglass.hook.aod

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.coloros16.liquidglass.engine.PaintOccupancy
import com.coloros16.liquidglass.engine.edit.EditLayoutSolver
import com.coloros16.liquidglass.engine.edit.LockLayoutSettings
import com.coloros16.liquidglass.engine.edit.LockWidget
import com.coloros16.liquidglass.engine.edit.WidgetCatalog
import com.coloros16.liquidglass.log.Lg

/**
 * 锁屏小组件自绘层：按编辑配置在锁屏上画小组件底板与名称。
 *
 * 小组件是本模块自己的能力（系统锁屏没有可挂的容器），故自绘不去找系统容器。
 * 不可点击、不拦触摸；配置为空时整层透明。
 */
class LockWidgetLayer(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(56, 255, 255, 255)
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Color.argb(90, 255, 255, 255)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        textAlign = Paint.Align.CENTER
    }

    private val rect = EditLayoutSolver.Rect()
    private val grid = EditLayoutSolver.Grid()
    private val drawRect = RectF()
    private var widgets: List<LockWidget> = emptyList()
    private var names: Map<String, String> = emptyMap()

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
    }

    /** 由挂载方写入；空列表即整层不画。 */
    fun bind(settings: LockLayoutSettings?, nameOf: (String) -> String) {
        val next = if (settings == null) emptyList() else EditLayoutSolver.enabledWidgets(settings)
        if (next == widgets) return
        widgets = next
        names = next.associate { it.typeId to nameOf(it.typeId) }
        invalidate()
    }

    fun release() {
        if (widgets.isEmpty()) return
        widgets = emptyList()
        names = emptyMap()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (widgets.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val radius = CORNER_DP * density
        val boxes = ArrayList<PaintOccupancy.Box>(widgets.size)
        for (widget in widgets) {
            if (!EditLayoutSolver.widgetRect(widget, w, h, EditLayoutSolver.WIDGET_GRID_TOP, grid, rect)) {
                boxes.add(PaintOccupancy.Box(0f, 0f, 0f, 0f))
                continue
            }
            EditLayoutSolver.clampInto(rect, w, h)
            boxes.add(PaintOccupancy.Box(rect.left, rect.top, rect.width, rect.height))
        }
        val keep = PaintOccupancy.accept(boxes)
        if (keep.size < widgets.size) {
            Lg.i(TAG, "widget overlap: ${widgets.size - keep.size} dropped, kept=${keep.size}")
        }
        for (i in keep) {
            val widget = widgets[i]
            val box = boxes[i]
            drawRect.set(box.left, box.top, box.right, box.bottom)
            canvas.drawRoundRect(drawRect, radius, radius, plate)
            canvas.drawRoundRect(drawRect, radius, radius, stroke)
            val name = names[widget.typeId] ?: widget.typeId
            text.textSize = minOf(TEXT_DP * density, drawRect.width() / (name.length + 1f))
            canvas.drawText(name, drawRect.centerX(), drawRect.centerY() + text.textSize * 0.35f, text)
        }
    }

    private companion object {
        const val TAG = "LgLockWidget"
        const val CORNER_DP = 12f
        const val TEXT_DP = 10.5f
    }
}