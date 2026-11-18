package com.coloros16.liquidglass.ui.aod

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.coloros16.liquidglass.engine.effect.EffectTextureMath
import kotlin.math.max
import kotlin.math.min

/**
 * 移轴景深：清晰带居中偏下，带外按羽化曲线过渡到模糊。
 *
 * 参数取自系统壁纸的移轴配置（带高 45%、中心贴底、羽化 1.36 倍）。
 */
object TiltShiftRenderer {

    private const val MAX_BLUR_PX = 24f
    private const val DOWNSCALE = 6

    /** 相对高度 y（0..1）处的模糊权重，0=清晰、1=最模糊。 */
    fun weightAt(yFraction: Float, layoutHeightPx: Float): Float =
        EffectTextureMath.tiltShiftWeight(yFraction, layoutHeightPx)

    /** 生成模糊副本（降采样再放大，等效大半径高斯）。 */
    fun blurred(source: Bitmap, radiusPx: Float): Bitmap {
        val factor = max(1, (radiusPx / DOWNSCALE).toInt().coerceAtMost(12))
        val w = max(1, source.width / factor)
        val h = max(1, source.height / factor)
        // 绘制器材全部局部持有：本函数在后台构建线程跑，共享字段会与其它调用互相踩
        val src = Rect()
        val dst = Rect()
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(small)
        src.set(0, 0, source.width, source.height)
        dst.set(0, 0, w, h)
        canvas.drawBitmap(source, src, dst, paint)
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val up = Canvas(out)
        src.set(0, 0, w, h)
        dst.set(0, 0, out.width, out.height)
        up.drawBitmap(small, src, dst, paint)
        small.recycle()
        return out
    }

    /**
     * 把移轴景深画到画布：先整幅模糊图，再按行权重把清晰图叠回去。
     *
     * 逐行 drawBitmap 带权重，避免整屏 saveLayer 的开销。
     */
    fun draw(canvas: Canvas, sharp: Bitmap, blurred: Bitmap, widthPx: Float, heightPx: Float) {
        val rect = RectF()
        val src = Rect()
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        rect.set(0f, 0f, widthPx, heightPx)
        canvas.drawBitmap(blurred, null, rect, paint)
        val rows = min(96, max(1, heightPx.toInt()))
        val rowH = heightPx / rows
        for (i in 0 until rows) {
            val y = i * rowH
            val weight = weightAt((y + rowH / 2f) / heightPx, heightPx)
            val alpha = ((1f - weight) * 255f).toInt()
            if (alpha <= 0) continue
            paint.alpha = alpha
            val sy = (i.toFloat() / rows * sharp.height).toInt()
            val sy2 = min(sharp.height, ((i + 1f) / rows * sharp.height).toInt().coerceAtLeast(sy + 1))
            src.set(0, sy, sharp.width, sy2)
            rect.set(0f, y, widthPx, y + rowH + 0.5f)
            canvas.drawBitmap(sharp, src, rect, paint)
        }
        paint.alpha = 255
    }

    /** 便捷入口：自行生成模糊副本后绘制，调用方负责回收返回值。 */
    fun render(source: Bitmap, widthPx: Int, heightPx: Int): Bitmap {
        val blur = blurred(source, MAX_BLUR_PX)
        val out = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val sharp = Bitmap.createScaledBitmap(source, widthPx, heightPx, true)
        draw(canvas, sharp, blur, widthPx.toFloat(), heightPx.toFloat())
        if (sharp !== source) sharp.recycle()
        blur.recycle()
        return out
    }

    /** 清晰带上下边界（相对高度），供 UI 画参考线。 */
    fun clearBand(layoutHeightPx: Float): Pair<Float, Float> {
        val band = EffectTextureMath.tiltShiftBand(layoutHeightPx)
        return band[0] to band[1]
    }

}
