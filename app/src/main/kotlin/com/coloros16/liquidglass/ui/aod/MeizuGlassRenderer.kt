package com.coloros16.liquidglass.ui.aod

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import com.coloros16.liquidglass.engine.effect.EffectCatalog

/**
 * 魅族锁屏玻璃效果（长虹/波浪/雾花）：按官方 glass shader 做条纹/波浪/磨砂折射。
 *
 * 长虹=直线条纹水平折射，波浪=正弦波浪双向折射，雾花=灰度梯度磨砂折射；
 * 遮罩把效果约束在屏幕中部带状区域，边缘原样露出壁纸。
 */
object MeizuGlassRenderer {

    const val RAINBOW = 0
    const val WAVE = 1
    const val FOG = 2

    fun kindOf(effectId: String?): Int? = when (effectId) {
        EffectCatalog.LOCK_WP_RAINBOW -> RAINBOW
        EffectCatalog.LOCK_WP_WAVE -> WAVE
        EffectCatalog.LOCK_WP_FOG -> FOG
        else -> null
    }

    /** 官方 glassState 锁屏档参数：线宽/线距/遮罩阈值/偏移强度。 */
    fun params(kind: Int): FloatArray = when (kind) {
        WAVE -> floatArrayOf(0.05f, 0.072f, 0f, 0.03f, 0.007f)
        else -> floatArrayOf(0.04f, 0.076f, 0f, 0.03f, 0f)
    }

    private val renderLock = Any()

    /** 程序与画笔每次渲染各自新建：uniform 是着色器实例状态，共用一份会被并发渲染互相覆盖。 */
    private fun newProgram(): RuntimeShader? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { RuntimeShader(SHADER) }.getOrNull()
        } else {
            null
        }

    /**
     * 把壁纸按玻璃效果折射后铺满整屏。
     * [kind] 取 [RAINBOW]/[WAVE]/[FOG]；[intensity] 0–1 控制折射强度；
     * [fogGray]/[fogMask] 仅雾花用（灰度图与遮罩图，缺省回退程序化磨砂）。
     */
    fun render(
        wallpaper: Bitmap,
        kind: Int,
        widthPx: Int,
        heightPx: Int,
        intensity: Float,
        fogGray: Bitmap? = null,
        fogMask: Bitmap? = null,
    ): Bitmap? {
        val shader = newProgram() ?: return null
        if (widthPx <= 0 || heightPx <= 0) return null
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val wallShader = BitmapShader(wallpaper, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val strength = intensity.coerceIn(0f, 1f)
        val p = params(kind)
        return runCatching {
            shader.setInputBuffer("uWall", wallShader)
            shader.setFloatUniform("uSize", widthPx.toFloat(), heightPx.toFloat())
            shader.setFloatUniform("uWallSize", wallpaper.width.toFloat(), wallpaper.height.toFloat())
            shader.setFloatUniform("uKind", kind.toFloat())
            shader.setFloatUniform("uStrength", strength)
            shader.setFloatUniform("uTiling", 1f)
            shader.setFloatUniform("uLineWidth", p[0])
            shader.setFloatUniform("uLineOffset", p[1])
            shader.setFloatUniform("uMaskThreshold", p[2])
            shader.setFloatUniform("uOffsetX", p[3] * strength)
            shader.setFloatUniform("uOffsetY", p[4] * strength)
            shader.setFloatUniform("uLineFrequency", 40f)
            shader.setFloatUniform("uLineAmplitude", 0.045f)
            if (kind == FOG) {
                val gray = fogGray ?: wallpaper
                val mask = fogMask ?: wallpaper
                shader.setInputBuffer("uGray", BitmapShader(gray, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT))
                shader.setInputBuffer("uMask", BitmapShader(mask, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
                shader.setFloatUniform("uGraySize", gray.width.toFloat(), gray.height.toFloat())
                shader.setFloatUniform("uMaskSize", mask.width.toFloat(), mask.height.toFloat())
                shader.setFloatUniform("uMinGray", 0.03f)
                shader.setFloatUniform("uDudv", 1f / gray.width, 1f / gray.height)
            }
            paint.shader = shader
            drawHardware(widthPx, heightPx, paint)
        }.getOrElse {
            android.util.Log.w("MeizuGlass", "render failed kind=$kind", it)
            paint.shader = null
            null
        }
    }

    /**
     * RuntimeShader 只能画在硬件画布上：用 ImageReader 的硬件 Surface 当离屏画布，
     * 画完取回最新一帧按行读像素。串行化避免多线程共用一块 Surface。
     */
    private fun drawHardware(widthPx: Int, heightPx: Int, paint: Paint): Bitmap? = synchronized(renderLock) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val reader = android.media.ImageReader.newInstance(
            widthPx, heightPx, android.graphics.PixelFormat.RGBA_8888, 2,
            android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or
                android.hardware.HardwareBuffer.USAGE_CPU_READ_OFTEN,
        )
        val handlerThread = android.os.HandlerThread("meizu-glass-readback")
        handlerThread.start()
        val handler = android.os.Handler(handlerThread.looper)
        val latch = java.util.concurrent.CountDownLatch(1)
        var image: android.media.Image? = null
        reader.setOnImageAvailableListener({ r ->
            image?.close()
            image = r.acquireLatestImage()
            latch.countDown()
        }, handler)
        try {
            val canvas = reader.surface.lockHardwareCanvas()
            try {
                canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
                canvas.drawRect(0f, 0f, widthPx.toFloat(), heightPx.toFloat(), paint)
            } finally {
                reader.surface.unlockCanvasAndPost(canvas)
            }
            if (!latch.await(2, java.util.concurrent.TimeUnit.SECONDS)) return null
            val img = image ?: return null
            val plane = img.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val out = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            val row = ByteArray(rowStride)
            val pixels = IntArray(widthPx)
            for (y in 0 until heightPx) {
                buffer.position(y * rowStride)
                buffer.get(row, 0, rowStride)
                for (x in 0 until widthPx) {
                    val base = x * pixelStride
                    val r = row[base].toInt() and 0xFF
                    val g = row[base + 1].toInt() and 0xFF
                    val b = row[base + 2].toInt() and 0xFF
                    val a = row[base + 3].toInt() and 0xFF
                    pixels[x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
                out.setPixels(pixels, 0, widthPx, 0, y, widthPx, 1)
            }
            out
        } finally {
            paint.shader = null
            image?.close()
            reader.close()
            handlerThread.quitSafely()
        }
    }

    private const val SHADER = """
uniform shader uWall;
uniform shader uGray;
uniform shader uMask;
uniform vec2 uSize;
uniform vec2 uWallSize;
uniform vec2 uGraySize;
uniform vec2 uMaskSize;
uniform float uKind;
uniform float uStrength;
uniform float uTiling;
uniform float uLineWidth;
uniform float uLineOffset;
uniform float uMaskThreshold;
uniform float uOffsetX;
uniform float uOffsetY;
uniform float uLineFrequency;
uniform float uLineAmplitude;
uniform float uMinGray;
uniform vec2 uDudv;

float interp(float x) { return x * x * x; }

float lineGray(vec2 uv) {
    float x = uv.x - (0.5 - uLineWidth * 0.5);
    float diff = mod(x, uLineOffset);
    for (int i = 0; i < 10; i++) {
        if (diff > uLineWidth) { return -1.0; }
        else if (diff < 0.0) { diff += uLineOffset; }
        else { return interp(diff / uLineWidth); }
    }
    return interp(diff / uLineWidth);
}

float waveGray(vec2 uv) {
    float x = uv.x - (0.5 - uLineAmplitude - uLineWidth * 0.5);
    float f = uLineAmplitude * sin(uv.y * uLineFrequency) + uLineAmplitude;
    float diff = mod(x, uLineOffset) - f;
    for (int i = 0; i < 10; i++) {
        if (diff > uLineWidth) { return -1.0; }
        else if (diff < 0.0) { diff += uLineOffset; }
        else { return interp(diff / uLineWidth); }
    }
    return interp(diff / uLineWidth);
}

float straightG(float gray, float maskOffset) {
    float gap = 0.01;
    if (maskOffset > gap) {
        float a = 0.7;
        if (abs(gray - a) < 0.07) { return abs(gray - a); }
        if (gray < a) { return abs(gray - a) / a; }
        return abs(gray - a) / (1.0 - a);
    }
    return abs(maskOffset - gap * 0.5);
}

float waveG(float gray, float maskOffset) {
    float gap = 0.01;
    if (maskOffset > gap) { return abs(gray - 0.5) * 2.0; }
    return abs(maskOffset - gap * 0.5);
}

vec2 wallUv(vec2 uv) {
    vec2 lock = uv * uTiling + (1.0 - uTiling) * 0.5;
    return clamp(lock, 0.0, 1.0) * uWallSize;
}

vec4 main(vec2 coord) {
    vec2 uv = coord / uSize;
    vec3 base = uWall.eval(wallUv(uv)).rgb;
    if (uStrength <= 0.001) { return vec4(base, 1.0); }

    if (uKind < 0.5) {
        float mask = clamp(1.0 - abs(uv.x - 0.5) * 2.0, 0.0, 1.0);
        if (mask <= uMaskThreshold) { return vec4(base, 1.0); }
        float gray = lineGray(uv);
        float maskOffset = abs(mask - uMaskThreshold);
        if (gray < 0.0 && maskOffset > 0.01) { return vec4(base, 1.0); }
        float g = straightG(gray, maskOffset);
        float ox = (1.0 - sqrt(g) - 0.5) * 2.0;
        vec2 off = vec2(ox, 0.0) * vec2(uOffsetX, uOffsetY);
        return vec4(uWall.eval(wallUv(uv + off)).rgb, 1.0);
    }

    if (uKind < 1.5) {
        float f = uLineAmplitude * sin(uv.y * uLineFrequency);
        float dist = abs((uv.x - 0.5) - f);
        float mask = clamp(1.0 - dist / (0.5 + uLineAmplitude), 0.0, 1.0);
        if (mask <= uMaskThreshold) { return vec4(base, 1.0); }
        float gray = waveGray(uv);
        float maskOffset = abs(mask - uMaskThreshold);
        if (gray < 0.0 && maskOffset > 0.01) { return vec4(base, 1.0); }
        float g = waveG(gray, maskOffset);
        float ox = (1.0 - sqrt(g) - 0.5) * 2.0;
        vec2 off = vec2(ox, -ox) * vec2(uOffsetX, uOffsetX);
        return vec4(uWall.eval(wallUv(uv + off)).rgb, 1.0);
    }

    float mask = uMask.eval(uv * uMaskSize).r;
    if (mask <= uMaskThreshold) { return vec4(base, 1.0); }
    float maskOffset = abs(mask - uMaskThreshold);
    if (maskOffset < 0.05) { return vec4(base, 1.0); }
    vec2 guv = uv * uGraySize;
    float g1 = max(uGray.eval(guv).r, uMinGray);
    float g2 = max(uGray.eval(guv - vec2(uDudv.x, 0.0) * uGraySize).r, uMinGray);
    float g3 = max(uGray.eval(guv - vec2(0.0, uDudv.y) * uGraySize).r, uMinGray);
    vec2 off = vec2(g2 - g1, g3 - g1) * (0.1 * uStrength);
    return vec4(uWall.eval(wallUv(uv + off)).rgb, 1.0);
}
"""
}
