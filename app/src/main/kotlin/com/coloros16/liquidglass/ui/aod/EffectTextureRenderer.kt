package com.coloros16.liquidglass.ui.aod

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import com.coloros16.liquidglass.engine.effect.EffectTextureMath
import com.coloros16.liquidglass.engine.effect.EffectTextureSpec
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * 原版效果贴图的采样与着色：贴图是法线/高度图，按玻璃参数算折射亮度与方向高光。
 *
 * R/G 编码法线 XY，A 编码高度；输出是一张可叠加在壁纸上的 RGBA 位图。
 */
object EffectTextureRenderer {

    fun decode(assets: android.content.res.AssetManager, path: String): Bitmap? = runCatching {
        assets.open(path).use { android.graphics.BitmapFactory.decodeStream(it) }
    }.getOrNull()

    /** 按环绕模式把贴图铺满目标尺寸的采样表（每格存原样 AARRGGBB texel）。 */
    fun sampleGrid(source: Bitmap, spec: EffectTextureSpec, widthPx: Int, heightPx: Int): IntArray {
        val sw = source.width
        val sh = source.height
        val pixels = IntArray(sw * sh)
        source.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        return EffectTextureMath.sampleGrid(pixels, sw, sh, spec, widthPx, heightPx)
    }

    /** 逐像素求值：法线 → 折射亮度 + 方向光高光。 */
    fun render(source: Bitmap, spec: EffectTextureSpec, widthPx: Int, heightPx: Int, blurred: Boolean): Bitmap {
        val sw = source.width
        val sh = source.height
        val pixels = IntArray(sw * sh)
        source.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        val grid = EffectTextureMath.shadeGrid(pixels, sw, sh, spec, widthPx, heightPx, blurred)
        return Bitmap.createBitmap(grid, widthPx, heightPx, Bitmap.Config.ARGB_8888)
    }

    /** 把叠加层画到画布上（整屏拉伸）；器材局部持有，可在构建线程调用。 */
    fun draw(canvas: Canvas, bitmap: Bitmap, widthPx: Float, heightPx: Float) {
        val rect = RectF(0f, 0f, widthPx, heightPx)
        canvas.drawBitmap(bitmap, null, rect, Paint(Paint.FILTER_BITMAP_FLAG))
    }

    /**
     * 玻璃质感示意层：按法线做浮雕明暗，不透明度足以看清纹理。
     *
     * 4× 超采样后降采样，避免细密条纹被点采样吃掉。
     */
    fun relief(source: Bitmap, spec: EffectTextureSpec, widthPx: Int, heightPx: Int): Bitmap {
        val oversample = EffectTextureMath.RELIEF_OVERSAMPLE
        val sw = source.width
        val sh = source.height
        val pixels = IntArray(sw * sh)
        source.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        val grid = EffectTextureMath.reliefGrid(pixels, sw, sh, spec, widthPx, heightPx, oversample)
        val big = Bitmap.createBitmap(grid, widthPx * oversample, heightPx * oversample, Bitmap.Config.ARGB_8888)
        val out = Bitmap.createScaledBitmap(big, widthPx, heightPx, true)
        if (out !== big) big.recycle()
        return out
    }

    /** 按贴图法线做壁纸折射：把法线当偏移场，重采样壁纸。 */
    fun refract(
        wallpaper: Bitmap,
        source: Bitmap,
        spec: EffectTextureSpec,
        widthPx: Int,
        heightPx: Int,
        blurred: Boolean,
    ): Bitmap {
        val grid = sampleGrid(source, spec, widthPx, heightPx)
        val ww = wallpaper.width
        val wh = wallpaper.height
        val wp = IntArray(ww * wh)
        wallpaper.getPixels(wp, 0, ww, 0, 0, ww, wh)
        EffectTextureMath.refractGrid(wp, ww, wh, grid, spec, widthPx, heightPx, blurred)
        // 显式 mutable 画布底：createBitmap(int[]) 可能返回 immutable，后续 Canvas 叠加会抛 IllegalStateException
        val out = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        out.setPixels(grid, 0, widthPx, 0, 0, widthPx, heightPx)
        return out
    }

    /** 方向光高光条：用于缩略图等小尺寸示意。 */
    fun sheen(widthPx: Float, heightPx: Float, angleDeg: Float, strength: Float, color: Int): Shader {
        val rad = Math.toRadians(angleDeg.toDouble())
        val dx = cos(rad).toFloat()
        val dy = sin(rad).toFloat()
        val cx = widthPx / 2f
        val cy = heightPx / 2f
        val span = max(widthPx, heightPx)
        val a = (strength.coerceIn(0f, 1f) * 255f).toInt()
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return LinearGradient(
            cx - dx * span, cy - dy * span, cx + dx * span, cy + dy * span,
            intArrayOf(Color.argb(0, r, g, b), Color.argb(a, r, g, b), Color.argb(0, r, g, b)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
    }
}
