package com.coloros16.liquidglass.ui.aod

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.coloros16.liquidglass.engine.effect.EffectCatalog
import com.coloros16.liquidglass.engine.effect.EffectTextureCatalog

/** 效果卡片缩略图：按效果 id 画示意纹理。 */
class AodEffectThumbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var effectId: String = EffectCatalog.LOCK_WP_NONE
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val src = android.graphics.Rect()
    private val dst = RectF()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val bitmapCache = HashMap<String, Bitmap>()
    private val path = Path()
    private val clip = Path()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        clip.reset()
        clip.addRoundRect(r(0f, 0f, w, h), dp(9f), dp(9f), Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(clip)
        fill.style = Paint.Style.FILL
        fill.shader = null

        baseGradient(canvas, w, h)
        if (drawOriginal(canvas, w, h)) {
            canvas.restoreToCount(save)
            stroke.color = 0x1FFFFFFF
            stroke.strokeWidth = dp(1f)
            canvas.drawRoundRect(r(dp(0.5f), dp(0.5f), w - dp(0.5f), h - dp(0.5f)), dp(9f), dp(9f), stroke)
            return
        }
        when (effectId) {
            EffectCatalog.LOCK_WP_NONE, EffectCatalog.LOCK_SP_NONE, EffectCatalog.HOME_TRANSITION_NONE -> {
                stroke.color = 0x66FFFFFF
                stroke.strokeWidth = dp(1.4f)
                canvas.drawLine(w * 0.24f, h * 0.76f, w * 0.76f, h * 0.24f, stroke)
            }
            EffectCatalog.LOCK_WP_RAINBOW -> rainbow(canvas, w, h)
            EffectCatalog.LOCK_WP_WAVE -> wave(canvas, w, h, 3)
            EffectCatalog.LOCK_WP_FOG -> fog(canvas, w, h)
            EffectCatalog.LOCK_WP_BLUR -> blurDots(canvas, w, h)
            EffectCatalog.LOCK_WP_STRIPES_FINE -> stripes(canvas, w, h, 9, dp(1f))
            EffectCatalog.LOCK_WP_STRIPES_COARSE -> stripes(canvas, w, h, 5, dp(2.4f))
            EffectCatalog.LOCK_WP_LIGHT_STRIPES -> lightStripes(canvas, w, h)
            EffectCatalog.LOCK_WP_GRID -> grid(canvas, w, h)
            EffectCatalog.LOCK_WP_WATER -> wave(canvas, w, h, 5)
            EffectCatalog.LOCK_WP_RIPPLE -> ripple(canvas, w, h)
            EffectCatalog.LOCK_SP_LIQUID -> liquid(canvas, w, h)
            EffectCatalog.LOCK_SP_LENTICULAR -> lenticular(canvas, w, h)
            EffectCatalog.LOCK_SP_FLASH_CARD -> flashCard(canvas, w, h)
            EffectCatalog.LOCK_SP_DEPTH_3D -> depth3d(canvas, w, h)
            EffectCatalog.LOCK_SP_BOKEH -> bokeh(canvas, w, h)
            EffectCatalog.HOME_TRANSITION_CROSSFADE -> crossfade(canvas, w, h)
            EffectCatalog.HOME_ICON_FOREGROUND_FLY -> iconFly(canvas, w, h, 0)
            EffectCatalog.HOME_ICON_BACKGROUND_FLY -> iconFly(canvas, w, h, 1)
            EffectCatalog.HOME_ICON_ROTATE_FLY -> iconFly(canvas, w, h, 2)
            EffectCatalog.HOME_ICON_CROSSFADE -> crossfade(canvas, w, h)
            else -> Unit
        }
        canvas.restoreToCount(save)
        stroke.color = 0x1FFFFFFF
        stroke.strokeWidth = dp(1f)
        canvas.drawRoundRect(r(dp(0.5f), dp(0.5f), w - dp(0.5f), h - dp(0.5f)), dp(9f), dp(9f), stroke)
    }

    /** 原版素材优先：贴图类画浮雕纹理，图标类画官方图，景深画移轴带。 */
    private fun drawOriginal(canvas: Canvas, w: Float, h: Float): Boolean {
        if (effectId == EffectCatalog.LOCK_SP_BOKEH) {
            tiltShift(canvas, w, h)
            return true
        }
        val spec = EffectTextureCatalog.find(effectId)
        if (spec != null) {
            val normal = originalBitmap(spec.texturePath) ?: return false
            val tw = w.toInt().coerceAtLeast(1)
            val th = h.toInt().coerceAtLeast(1)
            val key = "relief|${spec.effectId}|$tw|$th"
            val layer = bitmapCache[key]?.takeIf { !it.isRecycled }
                ?: EffectTextureRenderer.relief(normal, spec, tw, th).also { bitmapCache[key] = it }
            src.set(0, 0, layer.width, layer.height)
            dst.set(0f, 0f, w, h)
            bitmapPaint.alpha = 190
            canvas.drawBitmap(layer, src, dst, bitmapPaint)
            bitmapPaint.alpha = 255
            if (spec.lightStrength > 0f) {
                fill.shader = EffectTextureRenderer.sheen(
                    w, h, spec.lightAngleDeg, spec.lightStrength * 3f, 0xFFFFFFFF.toInt(),
                )
                canvas.drawRect(0f, 0f, w, h, fill)
                fill.shader = null
            }
            return true
        }
        val assetPath = AssetVector.iconPath(effectId) ?: return false
        if (assetPath.endsWith(".xml")) {
            val vector = AssetVector.load(context.assets, assetPath) ?: return false
            val size = minOf(w, h) * 0.62f
            val left = (w - size) / 2f
            val top = (h - size) / 2f
            vector.setBounds(left.toInt(), top.toInt(), (left + size).toInt(), (top + size).toInt())
            vector.draw(canvas)
            return true
        }
        val bitmap = originalBitmap(assetPath) ?: return false
        src.set(0, 0, bitmap.width, bitmap.height)
        dst.set(0f, 0f, w, h)
        canvas.drawBitmap(bitmap, src, dst, bitmapPaint)
        return true
    }

    private fun originalBitmap(path: String): Bitmap? {
        bitmapCache[path]?.let { if (!it.isRecycled) return it }
        val decoded = runCatching {
            context.assets.open(path).use { BitmapFactory.decodeStream(it) }
        }.getOrNull() ?: return null
        bitmapCache[path] = decoded
        return decoded
    }

    private fun tiltShift(canvas: Canvas, w: Float, h: Float) {
        val (top, bottom) = TiltShiftRenderer.clearBand(h)
        fill.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(0x00FFFFFF, 0x40FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, ((top + bottom) / 2f).coerceIn(0.02f, 0.98f), 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
        stroke.color = 0x66FFFFFF
        stroke.strokeWidth = dp(1f)
        canvas.drawLine(w * 0.1f, h * top, w * 0.9f, h * top, stroke)
        canvas.drawLine(w * 0.1f, h * bottom, w * 0.9f, h * bottom, stroke)
    }

    private fun baseGradient(canvas: Canvas, w: Float, h: Float) {
        fill.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(0xFF1B2434.toInt(), 0xFF0B101A.toInt()),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
    }

    private fun rainbow(canvas: Canvas, w: Float, h: Float) {
        val colors = intArrayOf(0xB3FF6B6B.toInt(), 0xB3FFC46B.toInt(), 0xB36BFF9E.toInt(), 0xB36BC4FF.toInt(), 0xB3B06BFF.toInt())
        stroke.strokeWidth = dp(4f)
        for (i in colors.indices) {
            stroke.color = colors[i]
            val y = h * (0.26f + i * 0.12f)
            canvas.drawLine(w * 0.1f, y + dp(6f), w * 0.9f, y - dp(6f), stroke)
        }
    }

    private fun wave(canvas: Canvas, w: Float, h: Float, lines: Int) {
        stroke.color = 0x996BC4FF.toInt()
        stroke.strokeWidth = dp(1.6f)
        for (l in 0 until lines) {
            path.reset()
            val baseY = h * (0.2f + l * 0.6f / lines)
            path.moveTo(0f, baseY)
            var x = 0f
            while (x <= w) {
                val y = baseY + kotlin.math.sin(x / w * Math.PI.toFloat() * 3f + l) * dp(3.5f)
                path.lineTo(x, y)
                x += dp(3f)
            }
            canvas.drawPath(path, stroke)
        }
    }

    private fun fog(canvas: Canvas, w: Float, h: Float) {
        fill.color = 0x33DDE6F0
        canvas.drawCircle(w * 0.3f, h * 0.4f, w * 0.24f, fill)
        fill.color = 0x26DDE6F0
        canvas.drawCircle(w * 0.62f, h * 0.55f, w * 0.3f, fill)
        fill.color = 0x1ADDE6F0
        canvas.drawCircle(w * 0.5f, h * 0.3f, w * 0.34f, fill)
    }

    private fun blurDots(canvas: Canvas, w: Float, h: Float) {
        fill.color = 0x8C8FB8E8.toInt()
        canvas.drawCircle(w * 0.34f, h * 0.42f, dp(7f), fill)
        fill.color = 0x4D8FB8E8
        canvas.drawCircle(w * 0.34f, h * 0.42f, dp(12f), fill)
        fill.color = 0x8CE8C08F.toInt()
        canvas.drawCircle(w * 0.66f, h * 0.6f, dp(6f), fill)
        fill.color = 0x4DE8C08F
        canvas.drawCircle(w * 0.66f, h * 0.6f, dp(11f), fill)
    }

    private fun stripes(canvas: Canvas, w: Float, h: Float, count: Int, widthPx: Float) {
        stroke.color = 0x73AFC8E8.toInt()
        stroke.strokeWidth = widthPx
        for (i in 0 until count) {
            val x = w * (0.12f + i * 0.76f / (count - 1))
            canvas.drawLine(x + dp(8f), 0f, x - dp(8f), h, stroke)
        }
    }

    private fun lightStripes(canvas: Canvas, w: Float, h: Float) {
        stroke.strokeWidth = dp(2.2f)
        val alphas = intArrayOf(0x33, 0x66, 0xB3, 0x66, 0x33)
        for (i in alphas.indices) {
            stroke.color = (alphas[i] shl 24) or 0xE8DFAF
            val x = w * (0.2f + i * 0.15f)
            canvas.drawLine(x + dp(10f), 0f, x - dp(10f), h, stroke)
        }
    }

    private fun grid(canvas: Canvas, w: Float, h: Float) {
        stroke.color = 0x59AFC8E8.toInt()
        stroke.strokeWidth = dp(1f)
        for (i in 1..3) {
            canvas.drawLine(w * i / 4f, 0f, w * i / 4f, h, stroke)
            canvas.drawLine(0f, h * i / 4f, w, h * i / 4f, stroke)
        }
    }

    private fun ripple(canvas: Canvas, w: Float, h: Float) {
        stroke.color = 0x8C6BC4FF.toInt()
        stroke.strokeWidth = dp(1.4f)
        for (i in 1..3) {
            canvas.drawCircle(w * 0.5f, h * 0.5f, w * 0.12f * i, stroke)
        }
    }

    private fun liquid(canvas: Canvas, w: Float, h: Float) {
        fill.color = 0xB36BD1FF.toInt()
        canvas.drawCircle(w * 0.38f, h * 0.46f, w * 0.17f, fill)
        fill.color = 0xB39C6BFF.toInt()
        canvas.drawCircle(w * 0.62f, h * 0.56f, w * 0.15f, fill)
        fill.color = 0xB37FA8FF.toInt()
        canvas.drawCircle(w * 0.52f, h * 0.48f, w * 0.12f, fill)
    }

    private fun lenticular(canvas: Canvas, w: Float, h: Float) {
        stroke.strokeWidth = dp(2f)
        for (i in 0 until 8) {
            stroke.color = if (i % 2 == 0) 0x996BC4FF.toInt() else 0x99FFB06B.toInt()
            val x = w * (0.1f + i * 0.1f)
            canvas.drawLine(x + dp(6f), 0f, x - dp(6f), h, stroke)
        }
    }

    private fun flashCard(canvas: Canvas, w: Float, h: Float) {
        fill.color = 0x33FFFFFF
        canvas.drawRoundRect(r(w * 0.18f, h * 0.2f, w * 0.66f, h * 0.8f), dp(4f), dp(4f), fill)
        fill.shader = LinearGradient(
            w * 0.3f, 0f, w * 0.7f, h,
            intArrayOf(0x00FFFFFF, 0xB3FFFFFF.toInt(), 0x00FFFFFF),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(r(w * 0.34f, h * 0.14f, w * 0.82f, h * 0.74f), dp(4f), dp(4f), fill)
        fill.shader = null
    }

    private fun depth3d(canvas: Canvas, w: Float, h: Float) {
        stroke.color = 0x8CAFC8E8.toInt()
        stroke.strokeWidth = dp(1.4f)
        canvas.drawRect(r(w * 0.16f, h * 0.3f, w * 0.6f, h * 0.82f), stroke)
        stroke.color = 0xB3E8C08F.toInt()
        canvas.drawRect(r(w * 0.4f, h * 0.16f, w * 0.84f, h * 0.68f), stroke)
    }

    private fun bokeh(canvas: Canvas, w: Float, h: Float) {
        val dots = listOf(
            Triple(0.26f, 0.36f, 8f), Triple(0.58f, 0.28f, 5f), Triple(0.74f, 0.58f, 9f),
            Triple(0.38f, 0.66f, 6f), Triple(0.52f, 0.5f, 4f),
        )
        for ((fx, fy, r) in dots) {
            fill.color = 0x66E8DFAF.toInt()
            canvas.drawCircle(w * fx, h * fy, dp(r), fill)
            fill.color = 0x2EE8DFAF.toInt()
            canvas.drawCircle(w * fx, h * fy, dp(r + 4f), fill)
        }
    }

    private fun crossfade(canvas: Canvas, w: Float, h: Float) {
        fill.color = 0x666BC4FF.toInt()
        canvas.drawRoundRect(r(w * 0.12f, h * 0.24f, w * 0.56f, h * 0.76f), dp(4f), dp(4f), fill)
        fill.color = 0x66E8C08F.toInt()
        canvas.drawRoundRect(r(w * 0.44f, h * 0.24f, w * 0.88f, h * 0.76f), dp(4f), dp(4f), fill)
    }

    private fun iconFly(canvas: Canvas, w: Float, h: Float, mode: Int) {
        fill.color = 0xB38FB8E8.toInt()
        val size = dp(9f)
        for (i in 0 until 3) {
            val fx = 0.24f + i * 0.24f
            val fy = when (mode) {
                0 -> 0.62f - i * 0.12f
                1 -> 0.3f + i * 0.12f
                else -> 0.5f
            }
            val cx = w * fx
            val cy = h * fy
            if (mode == 2) {
                val save = canvas.save()
                canvas.rotate(i * 24f - 24f, cx, cy)
                canvas.drawRoundRect(r(cx - size, cy - size, cx + size, cy + size), dp(3f), dp(3f), fill)
                canvas.restoreToCount(save)
            } else {
                canvas.drawRoundRect(r(cx - size, cy - size, cx + size, cy + size), dp(3f), dp(3f), fill)
            }
        }
    }

    private fun r(l: Float, t: Float, rr: Float, b: Float): RectF {
        rect.set(l, t, rr, b)
        return rect
    }

    private fun dp(value: Float): Float = value * density
}