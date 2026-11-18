package com.coloros16.liquidglass.ui.aod

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.min

/**
 * 编辑画布上小组件卡片外观的唯一绘制实现：圆角底板 + 描边 + 居中名牌。
 *
 * 独立编辑画布与预览内联编辑共用这一份，避免两处各画一套而漂移；
 * 名牌字号按卡片宽度收敛，保证长名字不溢出卡片。
 */
object WidgetPlatePainter {

    /** 卡片圆角（dp）。 */
    const val CORNER_DP = 12f

    /** 名牌基准字号（dp）。 */
    const val NAME_TEXT_DP = 10.5f

    /** 画卡片底板与居中名牌；名牌为空时只画底板。 */
    fun draw(
        canvas: Canvas,
        rect: RectF,
        name: String,
        density: Float,
        plate: Paint,
        stroke: Paint,
        text: Paint,
    ) {
        if (density <= 0f || rect.width() <= 0f || rect.height() <= 0f) return
        val radius = CORNER_DP * density
        canvas.drawRoundRect(rect, radius, radius, plate)
        canvas.drawRoundRect(rect, radius, radius, stroke)
        if (name.isEmpty()) return
        val label = Paint(text)
        label.textAlign = Paint.Align.CENTER
        label.textSize = nameSizePx(rect.width(), name.length, density)
        canvas.drawText(name, rect.centerX(), rect.centerY() + label.textSize * 0.35f, label)
    }

    /** 名牌字号：基准字号与「卡片宽 / (字数+1)」取小，长名字自动缩到不溢出。 */
    fun nameSizePx(widthPx: Float, nameLength: Int, density: Float): Float {
        if (widthPx <= 0f || density <= 0f) return 0f
        return min(NAME_TEXT_DP * density, widthPx / (nameLength + 1f))
    }
}
