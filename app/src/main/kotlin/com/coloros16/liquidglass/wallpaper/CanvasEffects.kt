package com.coloros16.liquidglass.wallpaper

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 2D 壁纸的 Canvas 程序化特效。 */
object CanvasEffects {

    private class Star(val x: Float, val y: Float, val size: Float, val phase: Float, val tint: Int)

    private val stars: List<Star> by lazy {
        val random = Random(0xC0FFEEL)
        (0 until 220).map {
            val tint = when {
                random.nextFloat() < 0.12f -> Color.rgb(255, 214, 170)
                random.nextFloat() < 0.20f -> Color.rgb(176, 214, 255)
                else -> Color.WHITE
            }
            Star(
                x = random.nextFloat(),
                y = random.nextFloat() * 0.92f + 0.02f,
                size = 0.6f + random.nextFloat() * 2.2f,
                phase = random.nextFloat() * (2f * PI.toFloat()),
                tint = tint,
            )
        }
    }

    fun draw(
        canvas: Canvas,
        width: Float,
        height: Float,
        timeSec: Float,
        effect: String,
        offsetX: Float,
        parallax: Float,
    ) {
        when (effect) {
            WallpaperSettings.EFFECT_STARFIELD -> drawStarfield(canvas, width, height, timeSec, offsetX, parallax)
            WallpaperSettings.EFFECT_FLOW -> drawFlow(canvas, width, height, timeSec, offsetX, parallax)
            else -> drawAurora(canvas, width, height, timeSec, offsetX, parallax)
        }
    }

    private fun drawAurora(
        canvas: Canvas,
        width: Float,
        height: Float,
        timeSec: Float,
        offsetX: Float,
        parallax: Float,
    ) {
        val background = Paint(Paint.ANTI_ALIAS_FLAG)
        background.shader = LinearGradient(
            0f, 0f, 0f, height,
            intArrayOf(0xFF04070F.toInt(), 0xFF081426.toInt(), 0xFF0B1F35.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width, height, background)

        val shift = (offsetX - 0.5f) * width * 0.10f * parallax
        val bands = arrayOf(
            intArrayOf(46, 232, 176),
            intArrayOf(64, 176, 255),
            intArrayOf(138, 108, 255),
            intArrayOf(36, 255, 210),
        )
        for (i in bands.indices) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = height * (0.16f + i * 0.015f)
                strokeCap = Paint.Cap.ROUND
                color = Color.argb(150 - i * 18, bands[i][0], bands[i][1], bands[i][2])
                maskFilter = BlurMaskFilter(height * 0.055f, BlurMaskFilter.Blur.NORMAL)
            }
            val path = Path()
            val baseY = height * (0.26f + i * 0.085f)
            val amplitude = height * (0.045f + i * 0.008f)
            val phase = timeSec * (0.22f + i * 0.05f) + i * 1.7f
            val steps = 48
            for (step in 0..steps) {
                val t = step / steps.toFloat()
                val x = t * width + shift * (0.7f + i * 0.1f)
                val wave = sin(t * 5.4f + phase) * amplitude + sin(t * 2.1f + phase * 1.7f) * amplitude * 0.55f
                val y = baseY + wave
                if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, paint)
        }

        val horizon = Paint(Paint.ANTI_ALIAS_FLAG)
        horizon.shader = LinearGradient(
            0f, height * 0.55f, 0f, height,
            intArrayOf(Color.TRANSPARENT, 0x330B2A45),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, height * 0.55f, width, height, horizon)

        val bokeh = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until 14) {
            val cx = width * (0.08f + 0.84f * ((i * 0.37f + 0.11f) % 1f)) + shift * 1.4f
            val cy = height * (0.12f + 0.72f * ((i * 0.53f + 0.27f) % 1f))
            val radius = height * (0.012f + 0.018f * ((i * 0.19f) % 1f))
            val color = if (i % 3 == 0) 0x33A8FFE0 else 0x2278B7FF
            bokeh.shader = RadialGradient(cx, cy, radius, color, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, radius, bokeh)
        }
    }

    private fun drawStarfield(
        canvas: Canvas,
        width: Float,
        height: Float,
        timeSec: Float,
        offsetX: Float,
        parallax: Float,
    ) {
        val background = Paint(Paint.ANTI_ALIAS_FLAG)
        background.shader = LinearGradient(
            0f, 0f, 0f, height,
            intArrayOf(0xFF03040A.toInt(), 0xFF071021.toInt(), 0xFF04060E.toInt()),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width, height, background)

        val shift = (offsetX - 0.5f) * width * 0.12f * parallax
        val nebula = Paint(Paint.ANTI_ALIAS_FLAG)
        nebula.shader = RadialGradient(
            width * 0.72f + shift,
            height * 0.24f,
            height * 0.45f,
            intArrayOf(0x3A3A6BFF, 0x142A2C74, Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width, height, nebula)
        nebula.shader = RadialGradient(
            width * 0.22f + shift * 0.6f,
            height * 0.68f,
            height * 0.38f,
            intArrayOf(0x2E9C55FF, 0x122D1C63, Color.TRANSPARENT),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width, height, nebula)

        val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val unit = height / 1080f
        stars.forEach { star ->
            val twinkle = 0.55f + 0.45f * sin(timeSec * 1.6f + star.phase)
            val alpha = (twinkle * 220f).toInt().coerceIn(30, 235)
            val x = star.x * width + shift * (0.4f + star.size * 0.12f)
            val y = star.y * height
            starPaint.color = Color.argb(alpha, Color.red(star.tint), Color.green(star.tint), Color.blue(star.tint))
            val radius = star.size * unit * (0.9f + twinkle * 0.5f)
            canvas.drawCircle(x, y, radius, starPaint)
            if (star.size > 2.4f) {
                starPaint.strokeWidth = unit * 0.7f
                starPaint.style = Paint.Style.STROKE
                val arm = radius * 3.4f
                canvas.drawLine(x - arm, y, x + arm, y, starPaint)
                canvas.drawLine(x, y - arm, x, y + arm, starPaint)
                starPaint.style = Paint.Style.FILL
            }
        }
    }

    private fun drawFlow(
        canvas: Canvas,
        width: Float,
        height: Float,
        timeSec: Float,
        offsetX: Float,
        parallax: Float,
    ) {
        val background = Paint(Paint.ANTI_ALIAS_FLAG)
        background.shader = LinearGradient(
            0f, 0f, width, height,
            intArrayOf(0xFF070410.toInt(), 0xFF0B0A22.toInt(), 0xFF050612.toInt()),
            null,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width, height, background)

        val shift = (offsetX - 0.5f) * width * 0.08f * parallax
        val colors = intArrayOf(0xFF2F6BFF.toInt(), 0xFF8A3CFF.toInt(), 0xFF00C9A7.toInt(), 0xFFFF4E8A.toInt(), 0xFF31B6FF.toInt())
        val blob = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in colors.indices) {
            val speed = 0.18f + i * 0.035f
            val t = timeSec * speed + i * 1.3f
            val cx = width * (0.5f + 0.34f * sin(t * 1.27f + i) + 0.10f * cos(t * 2.1f)) + shift
            val cy = height * (0.48f + 0.30f * cos(t * 0.93f + i * 0.7f) + 0.08f * sin(t * 1.7f))
            val radius = height * (0.24f + 0.10f * ((i * 0.3f + 0.5f) % 1f))
            blob.shader = RadialGradient(
                cx, cy, radius,
                intArrayOf((colors[i] and 0x00FFFFFF) or 0x66000000, (colors[i] and 0x00FFFFFF) or 0x22000000, Color.TRANSPARENT),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, radius, blob)
        }

        val streak = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = height * 0.004f
            color = 0x22FFFFFF
        }
        for (i in 0 until 6) {
            val y = height * (0.18f + i * 0.12f)
            val path = Path()
            val phase = timeSec * 0.5f + i
            for (step in 0..24) {
                val x = step / 24f * width
                val wave = sin(step / 24f * 4.6f + phase) * height * 0.03f
                if (step == 0) path.moveTo(x, y + wave) else path.lineTo(x, y + wave)
            }
            canvas.drawPath(path, streak)
        }
    }
}
