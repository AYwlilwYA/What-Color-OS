package com.coloros16.liquidglass.ui.aod

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.coloros16.liquidglass.engine.HandPaintGeometry
import com.coloros16.liquidglass.engine.HandPaintMotion
import com.coloros16.liquidglass.engine.HandPaintSilk

/**
 * 手绘累积渲染：把完整轨迹按进度补画到离屏画布，画过的保留。
 *
 * 厂商原件只播固定容量的点窗口、播完贴完成图；本件按有效点全量累积，
 * 停笔已在解析期折叠故进度不含停顿。对称副本用画布变换复制，不重复存点。
 * 预览与 hook 各持一个实例，共用同一份求值与换算。
 */
class HandPaintRenderer(private val side: Int) : AutoCloseable {

    private val buffer: Bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    private val bufferCanvas = Canvas(buffer)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val blit = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val dst = RectF()

    private var drawnStyle = -1
    private var drawnCount = 0
    private var active: HandPaintSilk? = null

    /** 已画到的有效点数。 */
    fun drawn(): Int = drawnCount

    /** 累积画布本体。 */
    fun buffer(): Bitmap = buffer

    /**
     * 推进到给定进度：只补画新增点，进度回退或换款则清空重画。
     *
     * 返回本次补画的点数；无新增返回 0，上层据此跳过重绘。
     */
    fun advance(style: Int, silk: HandPaintSilk, progress: Float): Int {
        val total = silk.points.size
        if (total <= 0) return 0
        if (drawnStyle != style || HandPaintGeometry.needsReset(drawnCount, progress, total)) {
            buffer.eraseColor(Color.TRANSPARENT)
            drawnCount = 0
            drawnStyle = style
            active = silk
        }
        val target = active ?: silk
        val range = HandPaintGeometry.incrementRange(drawnCount, progress, total)
        if (range[1] <= range[0]) return 0
        drawRange(target, range[0], range[1])
        drawnCount = range[1]
        return range[1] - range[0]
    }

    /** 把累积画布贴到给定矩形（两侧共用 `HandPaintGeometry.presentRect` 定矩形）。 */
    fun present(canvas: Canvas, left: Float, top: Float, drawSide: Float, alpha: Float) {
        if (drawSide <= 0f) return
        dst.set(left, top, left + drawSide, top + drawSide)
        blit.shader = null
        blit.alpha = HandPaintGeometry.strokeAlphaByte(alpha)
        canvas.drawBitmap(buffer, null, dst, blit)
        blit.alpha = 255
    }

    /**
     * 补画一段：起点取前一点保证线段连续，逐份对称复制。
     *
     * 螺旋层缩半径并错开半个旋转格，镜像层绕中心水平翻转。
     */
    private fun drawRange(silk: HandPaintSilk, from: Int, to: Int) {
        val canvasSide = HandPaintGeometry.canvasSide(silk.centerX, silk.centerY)
        val fit = HandPaintGeometry.fit(canvasSide, side.toFloat(), side.toFloat())
        stroke.strokeWidth = (silk.brushLineWidth * fit[0]).coerceAtLeast(1f)
        val cx = HandPaintGeometry.mapX(silk.centerX, fit)
        val cy = HandPaintGeometry.mapY(silk.centerY, fit)
        val rotates = if (silk.rotates > 0) silk.rotates else 1
        val spirals = if (silk.spirals > 0) silk.spirals else 1
        val mirrors = if (silk.mirror) 2 else 1
        val start = if (from > 0) from - 1 else from

        for (s in 0 until spirals) {
            val scale = HandPaintGeometry.spiralScale(s)
            val offset = HandPaintGeometry.spiralAngleDeg(s, rotates)
            for (r in 0 until rotates) {
                val angle = HandPaintMotion.copyAngleDeg(silk, r) + offset
                for (m in 0 until mirrors) {
                    val save = bufferCanvas.save()
                    bufferCanvas.translate(cx, cy)
                    bufferCanvas.rotate(angle)
                    bufferCanvas.scale(scale, scale)
                    if (m == 1) bufferCanvas.scale(-1f, 1f)
                    bufferCanvas.translate(-cx, -cy)
                    strokeSegment(silk, fit, start, to)
                    bufferCanvas.restoreToCount(save)
                }
            }
        }
    }

    /** 同色相邻点合成一条路径一次描边，换色即提交。 */
    private fun strokeSegment(silk: HandPaintSilk, fit: FloatArray, from: Int, to: Int) {
        var open = false
        var lastColor = 0
        for (i in from until to) {
            val p = silk.points[i]
            val color = Color.argb(
                HandPaintGeometry.strokeAlphaByte(p.a),
                (p.r.coerceIn(0f, 1f) * 255f).toInt(),
                (p.g.coerceIn(0f, 1f) * 255f).toInt(),
                (p.b.coerceIn(0f, 1f) * 255f).toInt(),
            )
            val x = HandPaintGeometry.mapX(p.x, fit)
            val y = HandPaintGeometry.mapY(p.y, fit)
            if (!open) {
                path.reset()
                path.moveTo(x, y)
                lastColor = color
                open = true
            } else if (color != lastColor) {
                path.lineTo(x, y)
                stroke.color = lastColor
                bufferCanvas.drawPath(path, stroke)
                path.reset()
                path.moveTo(x, y)
                lastColor = color
            } else {
                path.lineTo(x, y)
            }
        }
        if (open) {
            stroke.color = lastColor
            bufferCanvas.drawPath(path, stroke)
            path.reset()
        }
    }

    override fun close() {
        runCatching { buffer.recycle() }
        drawnStyle = -1
        drawnCount = 0
        active = null
    }

    companion object {
        /** 离屏画布边长上限：过大在系统进程里吃内存，与照片类同量级。 */
        const val MAX_SIDE = 720

        /** 按目标短边定画布边长，钳到上限。 */
        fun sideFor(targetW: Int, targetH: Int): Int =
            minOf(targetW, targetH).coerceIn(1, MAX_SIDE)
    }
}