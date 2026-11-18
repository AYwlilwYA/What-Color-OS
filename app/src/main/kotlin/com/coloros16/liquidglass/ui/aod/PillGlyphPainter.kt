package com.coloros16.liquidglass.ui.aod

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.coloros16.liquidglass.engine.edit.PillType

/**
 * 底部大丸内容图标的唯一绘制实现：按配置内容分派到八种图标。
 *
 * 独立编辑画布与预览内联编辑共用这一份，避免两处各画一套而漂移；
 * 图标笔画按大丸直径缩放，颜色沿用调用方传入的描边与填充画笔。
 */
object PillGlyphPainter {

    /** 图标笔画的基准直径（dp）：实际缩放 = 大丸直径 / (基准 × density)。 */
    const val GLYPH_BASE_DP = 58f

    /** 画大丸内容图标；内容为「无」时不画任何东西。 */
    fun draw(canvas: Canvas, rect: RectF, contentId: String?, density: Float, stroke: Paint, fill: Paint) {
        if (density <= 0f || rect.width() <= 0f || rect.height() <= 0f) return
        val type = PillType.from(contentId) ?: PillType.FLASHLIGHT
        val c = rect.centerX()
        val cy = rect.centerY()
        val k = rect.width() / (GLYPH_BASE_DP * density)
        val u = density * k
        when (type) {
            PillType.NONE -> Unit
            PillType.FLASHLIGHT -> torch(canvas, c, cy, u, fill)
            PillType.CAMERA -> camera(canvas, c, cy, u, stroke)
            PillType.DIAL -> dial(canvas, c, cy, u, stroke)
            PillType.RECORDER -> recorder(canvas, c, cy, u, fill)
            PillType.CALCULATOR -> calculator(canvas, c, cy, u, stroke)
            PillType.SCAN -> scan(canvas, c, cy, u, stroke)
            PillType.PAY -> pay(canvas, c, cy, u, stroke)
        }
    }

    private fun linePaint(stroke: Paint, width: Float): Paint = Paint(stroke).apply {
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
    }

    private fun torch(canvas: Canvas, c: Float, cy: Float, u: Float, fill: Paint) {
        val body = RectF(c - 4f * u, cy - 8f * u, c + 4f * u, cy + 6f * u)
        canvas.drawRoundRect(body, 2f * u, 2f * u, fill)
        canvas.drawLine(c - 8f * u, cy - 10f * u, c - 14f * u, cy - 16f * u, fill)
        canvas.drawLine(c + 8f * u, cy - 10f * u, c + 14f * u, cy - 16f * u, fill)
    }

    private fun camera(canvas: Canvas, c: Float, cy: Float, u: Float, stroke: Paint) {
        val body = RectF(c - 10f * u, cy - 7f * u, c + 10f * u, cy + 7f * u)
        canvas.drawRoundRect(body, 3f * u, 3f * u, stroke)
        canvas.drawCircle(c, cy, 4f * u, stroke)
    }

    private fun dial(canvas: Canvas, c: Float, cy: Float, u: Float, stroke: Paint) {
        val p = linePaint(stroke, 2.4f * u)
        val path = Path()
        path.moveTo(c - 7f * u, cy - 7f * u)
        path.cubicTo(c - 9f * u, cy + 2f * u, c - 2f * u, cy + 9f * u, c + 7f * u, cy + 7f * u)
        canvas.drawPath(path, p)
    }

    private fun recorder(canvas: Canvas, c: Float, cy: Float, u: Float, fill: Paint) {
        val bars = floatArrayOf(3f, 7f, 10f, 7f, 3f)
        for (i in bars.indices) {
            val x = c + (i - 2) * 4f * u
            val h = bars[i] * u
            canvas.drawRoundRect(RectF(x - 1.2f * u, cy - h, x + 1.2f * u, cy + h), 1.2f * u, 1.2f * u, fill)
        }
    }

    private fun calculator(canvas: Canvas, c: Float, cy: Float, u: Float, stroke: Paint) {
        val p = linePaint(stroke, 1.8f * u)
        canvas.drawLine(c - 7f * u, cy - 4f * u, c - 1f * u, cy - 4f * u, p)
        canvas.drawLine(c - 4f * u, cy - 7f * u, c - 4f * u, cy - 1f * u, p)
        canvas.drawLine(c + 2f * u, cy - 4f * u, c + 8f * u, cy - 4f * u, p)
        canvas.drawLine(c - 7f * u, cy + 4f * u, c - 1f * u, cy + 4f * u, p)
        canvas.drawLine(c + 2f * u, cy + 2f * u, c + 8f * u, cy + 2f * u, p)
        canvas.drawLine(c + 2f * u, cy + 6f * u, c + 8f * u, cy + 6f * u, p)
    }

    private fun scan(canvas: Canvas, c: Float, cy: Float, u: Float, stroke: Paint) {
        val p = linePaint(stroke, 1.8f * u)
        val r = 8f * u
        val l = 4f * u
        for (sx in floatArrayOf(-1f, 1f)) for (sy in floatArrayOf(-1f, 1f)) {
            canvas.drawLine(c + sx * r, cy + sy * r, c + sx * (r - l), cy + sy * r, p)
            canvas.drawLine(c + sx * r, cy + sy * r, c + sx * r, cy + sy * (r - l), p)
        }
        canvas.drawLine(c - r, cy, c + r, cy, p)
    }

    private fun pay(canvas: Canvas, c: Float, cy: Float, u: Float, stroke: Paint) {
        val p = linePaint(stroke, 1.8f * u)
        canvas.drawLine(c - 6f * u, cy - 6f * u, c, cy - 1f * u, p)
        canvas.drawLine(c + 6f * u, cy - 6f * u, c, cy - 1f * u, p)
        canvas.drawLine(c, cy - 1f * u, c, cy + 7f * u, p)
        canvas.drawLine(c - 5f * u, cy + 1f * u, c + 5f * u, cy + 1f * u, p)
        canvas.drawLine(c - 5f * u, cy + 4f * u, c + 5f * u, cy + 4f * u, p)
    }
}
