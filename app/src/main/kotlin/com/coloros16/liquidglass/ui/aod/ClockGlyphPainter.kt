package com.coloros16.liquidglass.ui.aod

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.coloros16.liquidglass.engine.edit.ClockInfo
import com.coloros16.liquidglass.engine.edit.ClockLayout

/**
 * 时钟文本唯一绘制件：时间 + 日期 + 附加信息，两套编辑画布与预览共用。
 *
 * 字号走 [ClockLayout.fontPx]；命中框按实测字串宽度回写，供拖动与手柄命中。
 * 画笔一律克隆后再改，不污染调用方共享 Paint。
 */
object ClockGlyphPainter {

    /** 日期字号相对时间字号的比例。 */
    const val DATE_SIZE_RATIO = 0.132f

    /** 日期基线相对时间字号的下移比例。 */
    const val DATE_BASELINE_RATIO = 0.64f

    /** 日期字距。 */
    const val DATE_LETTER_SPACING = 0.10f

    /** 附加信息行距（相对日期字号）。 */
    const val INFO_LINE_RATIO = 1.6f

    /** 命中框纵向半高相对字号的比例。 */
    const val BOUNDS_HALF_HEIGHT_RATIO = 0.45f

    /** 日期不透明度。 */
    const val DATE_ALPHA = 0.62f

    /** 附加信息文案；NONE 返回 null。 */
    fun infoText(info: ClockInfo): String? = when (info) {
        ClockInfo.LUNAR -> "农历 十月初五"
        ClockInfo.WEATHER -> "22° 晴"
        ClockInfo.NONE -> null
    }

    /**
     * 画时钟并回写命中框。
     *
     * [fontPx] 为已合成的最终字号；[bounds] 与 [paddingPx] 决定命中框横向留白。
     * 返回实际使用的时间字号，非正输入返回 0 且不画。
     */
    fun draw(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        fontPx: Float,
        time: String,
        date: String,
        timeColor: Int,
        dateColor: Int,
        timeTypeface: Typeface,
        dateTypeface: Typeface,
        letterSpacing: Float,
        info: ClockInfo,
        timePaint: Paint,
        datePaint: Paint,
        bounds: RectF,
        paddingPx: Float,
        stretchX: Float = 1f,
        stretchY: Float = 1f,
    ): Float {
        if (fontPx <= 0f) return 0f
        val sx = if (stretchX > 0f) stretchX else 1f
        val sy = if (stretchY > 0f) stretchY else 1f
        val main = Paint(timePaint).apply {
            textSize = fontPx
            typeface = timeTypeface
            this.letterSpacing = letterSpacing
            color = timeColor
            alpha = 255
            textAlign = Paint.Align.CENTER
            textScaleX = sx
        }
        val sub = Paint(datePaint).apply {
            textSize = fontPx * DATE_SIZE_RATIO
            typeface = dateTypeface
            this.letterSpacing = DATE_LETTER_SPACING
            color = dateColor
            alpha = (DATE_ALPHA * 255f).toInt()
            textAlign = Paint.Align.CENTER
            textScaleX = sx
        }
        val save = if (sy != 1f) canvas.save().also { canvas.scale(1f, sy, cx, cy) } else -1
        val metrics = main.fontMetrics
        canvas.drawText(time, cx, cy - (metrics.ascent + metrics.descent) / 2f, main)
        val dateBaseline = cy + fontPx * DATE_BASELINE_RATIO
        canvas.drawText(date, cx, dateBaseline, sub)
        infoText(info)?.let {
            canvas.drawText(it, cx, dateBaseline + sub.textSize * INFO_LINE_RATIO, sub)
        }
        if (save >= 0) canvas.restoreToCount(save)

        val halfW = main.measureText(time) / 2f + paddingPx
        val halfH = fontPx * BOUNDS_HALF_HEIGHT_RATIO * sy
        bounds.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
        return fontPx
    }
}