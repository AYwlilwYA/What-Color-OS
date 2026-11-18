package com.coloros16.liquidglass.ui.aod

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import com.coloros16.liquidglass.engine.SailLensMotion
import kotlin.math.max
import kotlin.math.min

/** 启航镜片渲染：模糊壁纸、桶形畸变、饱和增强与镜片高光。 */
class AodLensRenderer(context: Context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fallback = Paint(Paint.ANTI_ALIAS_FLAG)
    private val circle = Path()
    private val rect = RectF()

    private val decorBitmap: Bitmap? = runCatching {
        context.assets.open("aod/lens_decorator.png").use { BitmapFactory.decodeStream(it) }
    }.getOrNull()

    private val program: RuntimeShader? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { RuntimeShader(SHADER) }.getOrNull()
        } else {
            null
        }

    private var cover: Bitmap? = null
    private var soft: Bitmap? = null
    private var lightSoft: Bitmap? = null
    private var sourceKey: Bitmap? = null
    private var sourceAspect = 0f

    private var sharpShader: BitmapShader? = null
    private var softShader: BitmapShader? = null
    private var decorShader: BitmapShader? = null
    private var decorWidth = 1
    private var decorHeight = 1

    private val fallbackMatrix = ColorMatrix().apply { setSaturation(1.5f) }
    private val fallbackFilter = ColorMatrixColorFilter(fallbackMatrix)

    /** 换壁纸或预览比例变化时重建采样纹理。 */
    fun setSource(bitmap: Bitmap?, aspect: Float) {
        if (bitmap == null) {
            cover = null
            soft = null
            lightSoft = null
            sourceKey = null
            return
        }
        if (bitmap === sourceKey && kotlin.math.abs(aspect - sourceAspect) < 1e-4f) return
        sourceKey = bitmap
        sourceAspect = aspect
        val built = runCatching { AodBitmap.cover(bitmap, aspect) }.getOrNull() ?: return
        cover = built
        soft = runCatching { AodBitmap.blur(built, 8, 2) }.getOrNull()
        lightSoft = runCatching { AodBitmap.blur(built, 4, 1) }.getOrNull()
        sharpShader = BitmapShader(built, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        softShader = soft?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
    }

    fun draw(
        canvas: Canvas,
        width: Float,
        height: Float,
        pose: SailLensMotion.Pose,
        zoom: Float = 0f,
        colorMix: Float = 0f,
    ) {
        if (width <= 0f || height <= 0f) return
        val shader = program
        val sharp = sharpShader
        if (shader == null || sharp == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            drawFallback(canvas, width, height, pose, pose.angleDeg)
            return
        }
        val coverBitmap = cover ?: return
        runCatching {
            shader.setInputBuffer("uPhoto", sharp)
            softShader?.let { shader.setInputBuffer("uPhotoSoft", it) }
            val decor = decorShader ?: decorBitmap?.let {
                decorWidth = it.width
                decorHeight = it.height
                BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also { s -> decorShader = s }
            }
            decor?.let { shader.setInputBuffer("uDecor", it) }
            shader.setFloatUniform("uSize", width, height)
            shader.setFloatUniform("uCenter", pose.centerX, pose.centerY)
            shader.setFloatUniform("uLensScale", pose.scale.coerceAtLeast(0.0001f))
            shader.setFloatUniform("uAspect", height / width)
            shader.setFloatUniform("uAspectInv", width / height)
            shader.setFloatUniform("uDistortion", pose.distortion / pose.scale.coerceAtLeast(0.0001f))
            shader.setFloatUniform("uDecorAlpha", pose.decorAlpha)
            shader.setFloatUniform("uAngle", pose.angleDeg)
            shader.setFloatUniform("uDecorAngle", pose.decorAngleDeg)
            shader.setFloatUniform(
                "uPivot",
                SailLensMotion.PIVOT_PX * width / SailLensMotion.REFERENCE_WIDTH,
            )
            shader.setFloatUniform("uBlurMix", (pose.blur / SailLensMotion.BLUR).coerceIn(0f, 1f))
            shader.setFloatUniform("uColorMix", colorMix.coerceIn(0f, 1f))
            shader.setFloatUniform(
                "uZoom",
                if (zoom > 0f) zoom else 1f / (min(pose.scale, 0.5f).coerceAtLeast(1e-3f) * 4f),
            )
            shader.setFloatUniform("uPhotoSize", coverBitmap.width.toFloat(), coverBitmap.height.toFloat())
            shader.setFloatUniform("uDecorSize", decorWidth.toFloat(), decorHeight.toFloat())

            paint.shader = shader
            paint.alpha = 255
            canvas.drawRect(0f, 0f, width, height, paint)
            paint.shader = null
        }.onFailure {
            paint.shader = null
            drawFallback(canvas, width, height, pose, pose.angleDeg)
        }
    }

    /** 以 cover 同框把预模糊壁纸铺满整屏，用于锁屏↔桌面的轻模糊过渡。 */
    fun drawSoftBackdrop(canvas: Canvas, width: Float, height: Float, alpha: Float, zoom: Float) {
        val bitmap = lightSoft ?: return
        val a = alpha.coerceIn(0f, 1f)
        if (a <= 0.002f || width <= 0f || height <= 0f) return
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()
        val scale = max(width / bw, height / bh) * zoom.coerceAtLeast(1e-3f)
        val dw = bw * scale
        val dh = bh * scale
        rect.set((width - dw) / 2f, (height - dh) / 2f, (width + dw) / 2f, (height + dh) / 2f)
        fallback.style = Paint.Style.FILL
        fallback.shader = null
        fallback.color = Color.WHITE
        fallback.alpha = (a * 255f).toInt()
        canvas.drawBitmap(bitmap, null, rect, fallback)
        fallback.alpha = 255
    }

    private fun drawFallback(
        canvas: Canvas,
        width: Float,
        height: Float,
        pose: SailLensMotion.Pose,
        angleDeg: Float,
    ) {
        val bitmap = cover ?: return
        val cx = pose.centerX * width
        val cy = pose.centerY * height
        val radius = (pose.scale * width).coerceAtLeast(1f)
        circle.reset()
        circle.addCircle(cx, cy, radius, Path.Direction.CW)

        val save = canvas.save()
        canvas.clipPath(circle)
        fallback.style = Paint.Style.FILL
        fallback.shader = null
        fallback.color = Color.BLACK
        canvas.drawRect(0f, 0f, width, height, fallback)

        val coverScale = max(width / bitmap.width, height / bitmap.height)
        canvas.rotate(-angleDeg, cx, cy + SailLensMotion.PIVOT_PX * width / SailLensMotion.REFERENCE_WIDTH)
        fallback.colorFilter = if (pose.decorAlpha > 0.001f) fallbackFilter else null
        rect.set(
            cx - bitmap.width * coverScale / 2f,
            cy - bitmap.height * coverScale / 2f,
            cx + bitmap.width * coverScale / 2f,
            cy + bitmap.height * coverScale / 2f,
        )
        canvas.drawBitmap(bitmap, null, rect, fallback)
        fallback.colorFilter = null

        fallback.shader = RadialGradient(
            cx - radius * 0.30f,
            cy - radius * 0.34f,
            radius * 1.45f,
            intArrayOf(0x33FFFFFF, 0x12FFFFFF, 0x99000000.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radius, fallback)
        fallback.shader = null
        canvas.restoreToCount(save)
    }

    private companion object {
        const val SHADER = """
uniform shader uPhoto;
uniform shader uPhotoSoft;
uniform shader uDecor;
uniform vec2 uSize;
uniform vec2 uCenter;
uniform float uLensScale;
uniform float uAspect;
uniform float uAspectInv;
uniform float uDistortion;
uniform float uDecorAlpha;
uniform float uAngle;
uniform float uDecorAngle;
uniform float uPivot;
uniform float uBlurMix;
uniform float uColorMix;
uniform float uZoom;
uniform vec2 uPhotoSize;
uniform vec2 uDecorSize;

vec2 spin(vec2 p, float deg) {
    float a = radians(deg);
    float c = cos(a);
    float s = sin(a);
    return vec2(c * p.x - s * p.y, s * p.x + c * p.y);
}

vec4 main(vec2 coord) {
    vec2 p = coord / uSize;
    vec2 ca = vec2(uCenter.x, uCenter.y * uAspect);
    vec2 uv = vec2(p.x, p.y * uAspect);
    float dist = distance(uv, ca);
    if (dist > uLensScale) {
        return vec4(0.0, 0.0, 0.0, 1.0);
    }
    vec2 d = uv - ca;
    vec2 warped = d * (1.0 - uDistortion * dot(d, d)) * uZoom;
    vec2 sn = vec2(ca.x + warped.x, (ca.y + warped.y) * uAspectInv);

    vec2 px = sn * uSize - uCenter * uSize;
    px += vec2(0.0, uPivot);
    px = spin(px, -uAngle);
    px -= vec2(0.0, uPivot);
    px += uSize * 0.5;
    vec2 tex = px / uSize;

    vec2 samplePos = tex * uPhotoSize;
    vec3 sharp = uPhoto.eval(samplePos).rgb;
    vec3 blurred = uPhotoSoft.eval(samplePos).rgb;
    vec3 color = mix(sharp, blurred, uBlurMix);

    if (uColorMix > 0.0001) {
        vec3 warm = color * vec3(1.18, 1.02, 0.82);
        vec3 cool = color * vec3(0.82, 1.0, 1.2);
        color = mix(color, mix(warm, cool, 0.5 + 0.5 * sin(uColorMix * 6.2831853)), uColorMix);
    }

    if (uDecorAlpha > 0.0001) {
        float luma = dot(color, vec3(0.2125, 0.7154, 0.0721));
        color = mix(vec3(luma), color, 1.0 + uDecorAlpha * 0.5);
        vec2 dq = spin(vec2(p.x - uCenter.x, (p.y - uCenter.y) * uAspect), -uDecorAngle);
        vec2 duv = dq / (2.0 * uLensScale) + vec2(0.5);
        vec4 decor = uDecor.eval(duv * uDecorSize);
        float a = decor.a * uDecorAlpha;
        color = color * (1.0 - a) + decor.rgb * uDecorAlpha;
    }
    color = mix(color, vec3(0.0), smoothstep(uLensScale - 0.002, uLensScale, dist));
    return vec4(color, 1.0);
}
"""
    }
}
