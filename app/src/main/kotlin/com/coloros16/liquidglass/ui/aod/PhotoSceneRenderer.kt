package com.coloros16.liquidglass.ui.aod

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import com.coloros16.liquidglass.engine.PhotoStyle
import kotlin.math.min

/**
 * 照片类息屏渲染：把绿幕帧当作内容窗口，R 通道铺主色、B 通道铺白、G 通道露出壁纸。
 * 帧按方形网格摆放，模型矩阵为 平移·缩放·旋转，内容按内容缩放取样壁纸。
 */
class PhotoSceneRenderer(context: Context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val program: RuntimeShader? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { RuntimeShader(SHADER) }.getOrNull()
        } else {
            null
        }

    private var sourceKey: Bitmap? = null
    private var sourceAspect = 0f
    private var sourceBlur = -1f
    private var cover: Bitmap? = null
    private var soft: Bitmap? = null
    private var heavy: Bitmap? = null
    private var coverShader: BitmapShader? = null
    private var softShader: BitmapShader? = null
    private var heavyShader: BitmapShader? = null
    private var frameKey: Bitmap? = null
    private var frameShader: BitmapShader? = null
    private var pairKey: Bitmap? = null
    private var pairShader: BitmapShader? = null
    private var revealKey: Bitmap? = null
    private var revealShader: BitmapShader? = null

    /** 换壁纸/换样式/预览比例变化时重建内容纹理。 */
    fun setSource(bitmap: Bitmap?, aspect: Float, blurPx: Float) {
        if (bitmap == null) {
            sourceKey = null
            cover = null
            soft = null
            heavy = null
            coverShader = null
            softShader = null
            heavyShader = null
            return
        }
        if (bitmap === sourceKey &&
            kotlin.math.abs(aspect - sourceAspect) < 1e-4f &&
            kotlin.math.abs(blurPx - sourceBlur) < 1e-3f
        ) {
            return
        }
        sourceKey = bitmap
        sourceAspect = aspect
        sourceBlur = blurPx
        val built = runCatching { AodBitmap.cover(bitmap, aspect) }.getOrNull() ?: return
        cover = built
        coverShader = BitmapShader(built, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        if (blurPx >= 1f) {
            soft = runCatching { AodBitmap.blur(built, 1, 12, passes = 3) }.getOrNull()
            heavy = runCatching { AodBitmap.blur(built, 8, 12, passes = 3) }.getOrNull()
        } else {
            soft = null
            heavy = null
        }
        softShader = soft?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        heavyShader = heavy?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
    }

    /**
     * 画一帧照片特效，覆盖整屏（网格外为黑）。
     * [values] 为 PhotoStyleMotion 的八通道：模糊/旋转/缩放/内容/锚点 x/锚点 y/微秒/不透明度。
     */
    fun draw(
        canvas: Canvas,
        width: Float,
        height: Float,
        values: FloatArray,
        frame: Bitmap?,
        accent: Int,
        dim: Float,
        blurAmount: Float,
        zoom: Float,
        pairPhoto: Bitmap? = null,
        cropX: Float = 0.5f,
        cropY: Float = 0.5f,
        cropSize: Float = 1f,
        cropAngle: Float = 0f,
        revealPhoto: Bitmap? = null,
    ) {
        if (width <= 0f || height <= 0f) return
        val shader = program ?: return
        val content = coverShader ?: return
        val mask = frame ?: return
        val side = min(width, height) * values[PhotoStyle.SLOT_SCALE]
        if (side <= 1f) return
        val maskShader = shaderOf(mask) ?: return
        val contentBitmap = cover ?: return
        val pair = pairPhoto != null && pairPhoto.width > 0 && pairPhoto.height > 0
        val pairSh = if (pair) pairShaderOf(pairPhoto!!) else null
        val revealSh = revealPhoto?.let { revealShaderOf(it) }
        val photoAlpha = values[PhotoStyle.SLOT_PHOTO_ALPHA].coerceIn(0f, 1f)
        runCatching {
            shader.setInputBuffer("uFrame", maskShader)
            shader.setInputBuffer("uContent", content)
            shader.setInputBuffer("uContentSoft", softShader ?: content)
            shader.setInputBuffer("uContentHeavy", heavyShader ?: content)
            shader.setInputBuffer("uReveal", revealSh ?: content)
            shader.setFloatUniform("uPhotoAlpha", photoAlpha)
            shader.setFloatUniform("uSize", width, height)
            shader.setFloatUniform(
                "uRevealSize",
                (revealPhoto?.width ?: contentBitmap.width).toFloat(),
                (revealPhoto?.height ?: contentBitmap.height).toFloat(),
            )
            shader.setFloatUniform(
                "uCenter",
                width * (values[PhotoStyle.SLOT_X] + 0.5f),
                height * (0.5f - values[PhotoStyle.SLOT_Y]),
            )
            shader.setFloatUniform("uSide", side)
            shader.setFloatUniform("uAngle", values[PhotoStyle.SLOT_ROTATION])
            shader.setFloatUniform("uContentAspect", height / width)
            shader.setFloatUniform("uContentZoom", (values[PhotoStyle.SLOT_CONTENT] * zoom).coerceAtLeast(0.01f))
            shader.setFloatUniform("uBlurMix", (blurAmount / 12f).coerceIn(0f, 1f))
            shader.setFloatUniform("uBlurHeavy", ((blurAmount - 12f) / 84f).coerceIn(0f, 1f))
            shader.setFloatUniform("uDim", dim.coerceIn(0f, 1f))
            shader.setFloatUniform(
                "uAccent",
                ((accent shr 16) and 0xFF) / 255f,
                ((accent shr 8) and 0xFF) / 255f,
                (accent and 0xFF) / 255f,
            )
            shader.setFloatUniform("uFrameSize", mask.width.toFloat(), mask.height.toFloat())
            shader.setFloatUniform(
                "uContentSize",
                contentBitmap.width.toFloat(),
                contentBitmap.height.toFloat(),
            )
            shader.setFloatUniform("uPair", if (pair && pairSh != null) 1f else 0f)
            if (pair && pairSh != null) {
                shader.setInputBuffer("uPairPhoto", pairSh)
                val pw = pairPhoto!!.width
                val ph = pairPhoto.height
                val crop = com.coloros16.liquidglass.engine.FrameCropMath.Crop().apply {
                    set(cropX, cropY, cropSize, cropAngle)
                }
                val front = com.coloros16.liquidglass.engine.FrameCropMath.cropMatrix(crop, pw, ph)
                val rear = com.coloros16.liquidglass.engine.FrameCropMath.rearMatrix(
                    crop, pw, ph, values[PhotoStyle.SLOT_VIDEO],
                )
                shader.setFloatUniform("uFront0", front[0], front[3], front[6])
                shader.setFloatUniform("uFront1", front[1], front[4], front[7])
                shader.setFloatUniform("uRear0", rear[0], rear[3], rear[6])
                shader.setFloatUniform("uRear1", rear[1], rear[4], rear[7])
                shader.setFloatUniform("uPairSize", pw.toFloat(), ph.toFloat())
            }
            paint.shader = shader
            canvas.drawRect(0f, 0f, width, height, paint)
            paint.shader = null
        }.onFailure { paint.shader = null }
    }

    private fun revealShaderOf(bitmap: Bitmap): BitmapShader? {
        if (bitmap === revealKey) return revealShader
        revealKey = bitmap
        revealShader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        return revealShader
    }

    private fun pairShaderOf(bitmap: Bitmap): BitmapShader? {
        if (bitmap === pairKey) return pairShader
        pairKey = bitmap
        pairShader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        return pairShader
    }

    private fun shaderOf(bitmap: Bitmap): BitmapShader? {
        if (bitmap === frameKey) return frameShader
        frameKey = bitmap
        frameShader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        return frameShader
    }

    private companion object {
        const val SHADER = """
uniform shader uFrame;
uniform shader uContent;
uniform shader uContentSoft;
uniform shader uContentHeavy;
uniform shader uPairPhoto;
uniform shader uReveal;
uniform float uPhotoAlpha;
uniform vec2 uSize;
uniform vec2 uRevealSize;
uniform float uSide;
uniform float uAngle;
uniform float uContentAspect;
uniform float uContentZoom;
uniform float uBlurMix;
uniform float uBlurHeavy;
uniform float uDim;
uniform float uPair;
uniform vec3 uAccent;
uniform vec2 uCenter;
uniform vec2 uFrameSize;
uniform vec2 uContentSize;
uniform vec2 uPairSize;
uniform vec3 uFront0;
uniform vec3 uFront1;
uniform vec3 uRear0;
uniform vec3 uRear1;

vec2 spin(vec2 p, float deg) {
    float a = radians(deg);
    float c = cos(a);
    float s = sin(a);
    return vec2(c * p.x - s * p.y, s * p.x + c * p.y);
}

vec2 cropUv(vec2 uv) {
    vec2 d = uv - vec2(0.5, 348.5 / 720.0);
    return vec2(
        uFront0.x * d.x + uFront0.y * d.y + uFront0.z,
        uFront1.x * d.x + uFront1.y * d.y + uFront1.z
    );
}

vec2 rearUv(vec2 uv) {
    return vec2(
        uRear0.x * uv.x + uRear0.y * uv.y + uRear0.z,
        uRear1.x * uv.x + uRear1.y * uv.y + uRear1.z
    );
}

vec4 main(vec2 coord) {
    vec2 local = spin(coord - uCenter, -uAngle) / uSide + vec2(0.5);
    if (local.x < 0.0 || local.x > 1.0 || local.y < 0.0 || local.y > 1.0) {
        return vec4(0.0, 0.0, 0.0, 1.0);
    }
    vec3 mask = uFrame.eval(local * uFrameSize).rgb;
    vec2 wall = vec2(0.5) + (local - vec2(0.5)) *
        vec2(uContentAspect / uContentZoom, 1.0 / uContentZoom);
    vec3 content = uContent.eval(wall * uContentSize).rgb;
    content = mix(content, uContentSoft.eval(wall * uContentSize).rgb, uBlurMix);
    content = mix(content, uContentHeavy.eval(wall * uContentSize).rgb, uBlurHeavy);
    content = mix(content, vec3(0.0078, 0.0157, 0.0392), uDim);
    if (uPhotoAlpha < 1.0) {
        vec3 revealColor = uReveal.eval(coord / uSize * uRevealSize).rgb;
        content = mix(revealColor, content, uPhotoAlpha);
    }
    vec3 rearColor = uAccent;
    if (uPair > 0.5) {
        content = uPairPhoto.eval(cropUv(local) * uPairSize).rgb;
        if (mask.r > 0.0) {
            rearColor = uPairPhoto.eval(rearUv(local) * uPairSize).rgb;
        }
    }
    vec3 color = vec3(0.0);
    color = mix(color, rearColor, clamp(mask.r, 0.0, 1.0));
    color = mix(color, vec3(1.0), clamp(mask.b, 0.0, 1.0));
    color = mix(color, content, clamp(mask.g, 0.0, 1.0));
    return vec4(color, 1.0);
}
"""
    }
}
