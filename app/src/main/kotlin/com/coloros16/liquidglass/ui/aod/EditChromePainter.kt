package com.coloros16.liquidglass.ui.aod

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF

/**
 * 编辑画布选中态外观的唯一实现：选框、八点变形手柄、右下角钩、大丸选中圈。
 *
 * 独立编辑画布与预览内联编辑共用这一份，避免两处各画一套而漂移；
 * 所有尺寸按 density 折算，颜色与画笔由调用方注入以便沿用各自主题。
 * 删除不画在画布上：小组件移除走长按菜单，画一个点不动的徽标等于假按钮。
 */
object EditChromePainter {

    /** 时钟与小组件选框圆角（dp）。 */
    const val SELECT_CORNER_DP = 10f

    /** 选框描边宽度（dp）。 */
    const val SELECT_STROKE_DP = 1.4f

    /** 选框虚线段长与间隔（dp）。 */
    const val SELECT_DASH_ON_DP = 6f
    const val SELECT_DASH_OFF_DP = 4f

    /** 吸附参考线虚线段长与间隔（dp）。 */
    const val GUIDE_DASH_ON_DP = 5f
    const val GUIDE_DASH_OFF_DP = 5f

    /** 大丸选中圈相对半径的外扩（dp）。 */
    const val PILL_RING_PAD_DP = 5f

    /** 八点变形手柄半边长（dp）。 */
    const val HANDLE_HALF_DP = 4.5f

    /** 右下角自由变形钩半径与外移系数。 */
    const val HOOK_RADIUS_DP = 8f
    const val HOOK_OFFSET_RATIO = 0.4f

    /** 手柄命中半径与钩心相对框右下角的外移（dp）；绘制与命中必须同源。 */
    const val HOOK_CENTER_PAD_DP = HOOK_RADIUS_DP * HOOK_OFFSET_RATIO
    const val HANDLE_HIT_DP = 24f

    /** 时钟命中框横向留白（dp）：绘制、离屏测量、缩放上限扣量三处共用。 */
    const val CLOCK_PAD_DP = 12f

    /** 小组件与大丸底板填充色。 */
    const val PLATE_COLOR = 0x24FFFFFF

    /** 日期文字色。 */
    const val DATE_COLOR = 0xFFE7EDF4.toInt()

    /** 选框画笔：虚线描边，颜色由调用方给。 */
    fun selectPaint(density: Float, color: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = SELECT_STROKE_DP * density
        this.color = color
        pathEffect = DashPathEffect(
            floatArrayOf(SELECT_DASH_ON_DP * density, SELECT_DASH_OFF_DP * density), 0f,
        )
    }

    /** 参考线画笔。 */
    fun guidePaint(density: Float, color: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        this.color = color
        pathEffect = DashPathEffect(
            floatArrayOf(GUIDE_DASH_ON_DP * density, GUIDE_DASH_OFF_DP * density), 0f,
        )
    }

    /** 画选中框：大丸走圆环，其余走圆角矩形。 */
    fun drawSelection(canvas: Canvas, rect: RectF, pill: Boolean, density: Float, paint: Paint) {
        if (density <= 0f || rect.width() <= 0f || rect.height() <= 0f) return
        if (pill) {
            canvas.drawCircle(
                rect.centerX(), rect.centerY(),
                rect.width() / 2f + PILL_RING_PAD_DP * density, paint,
            )
        } else {
            val r = SELECT_CORNER_DP * density
            canvas.drawRoundRect(rect, r, r, paint)
        }
    }

    /** 画时钟八点变形手柄与右下角自由变形钩。 */
    fun drawClockHandles(
        canvas: Canvas,
        rect: RectF,
        density: Float,
        fill: Paint,
        stroke: Paint,
    ) {
        if (density <= 0f || rect.width() <= 0f || rect.height() <= 0f) return
        val r = HANDLE_HALF_DP * density
        val xs = floatArrayOf(rect.left, rect.centerX(), rect.right)
        val ys = floatArrayOf(rect.top, rect.centerY(), rect.bottom)
        for (fx in xs) for (fy in ys) {
            if (fx == rect.centerX() && fy == rect.centerY()) continue
            canvas.drawRect(fx - r, fy - r, fx + r, fy + r, fill)
            canvas.drawRect(fx - r, fy - r, fx + r, fy + r, stroke)
        }
        val hookR = HOOK_RADIUS_DP * density
        val hx = rect.right + hookR * HOOK_OFFSET_RATIO
        val hy = rect.bottom + hookR * HOOK_OFFSET_RATIO
        canvas.drawCircle(hx, hy, hookR, fill)
        canvas.drawCircle(hx, hy, hookR, stroke)
    }

    /** 手柄命中：与 [drawClockHandles] 的钩心同一口径，画在哪就命中哪。 */
    fun nearHook(x: Float, y: Float, rect: RectF, density: Float): Boolean {
        val pad = HOOK_CENTER_PAD_DP * density
        val r = HANDLE_HIT_DP * density
        return kotlin.math.abs(x - (rect.right + pad)) <= r &&
            kotlin.math.abs(y - (rect.bottom + pad)) <= r
    }
}
