package com.coloros16.liquidglass.engine.effect

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 法线/高度贴图的采样与着色数学，纯 JVM，预览与单测共用。
 *
 * R/G 编码法线 XY，A 编码高度；输出 AARRGGBB。
 */
object EffectTextureMath {

    /** 把归一化坐标映射到贴图像素下标。 */
    fun sampleCoord(v: Float, span: Int, wrap: TextureWrap): Int {
        if (span <= 1) return 0
        return when (wrap) {
            TextureWrap.REPEAT -> {
                val m = v % span
                (if (m < 0f) m + span else m).toInt().coerceIn(0, span - 1)
            }
            TextureWrap.STRETCH -> (v / span * (span - 1)).toInt().coerceIn(0, span - 1)
            TextureWrap.CROP_OR_STRETCH -> v.toInt().coerceIn(0, span - 1)
        }
    }

    fun normalX(argb: Int): Float = ((argb shr 16 and 0xFF) - 128) / 127f

    fun normalY(argb: Int): Float = ((argb shr 8 and 0xFF) - 128) / 127f

    fun height(argb: Int): Float = (argb and 0xFF) / 255f

    fun normalZ(nx: Float, ny: Float): Float = sqrt(max(0f, 1f - nx * nx - ny * ny))

    /** "#AARRGGBB" / "#RRGGBB" → AARRGGBB。 */
    fun parseColor(text: String): Int {
        val hex = text.removePrefix("#")
        val v = hex.toLongOrNull(16) ?: return 0
        return if (hex.length <= 6) (0xFF000000.toInt() or v.toInt()) else v.toInt()
    }

    /** 单像素着色：折射亮度 + 方向光高光。 */
    fun shadePixel(
        texel: Int,
        spec: EffectTextureSpec,
        blurred: Boolean,
    ): Int {
        val nx = normalX(texel)
        val ny = normalY(texel)
        val h = height(texel)
        val nz = normalZ(nx, ny)
        val thickness = EffectTextureCatalog.thickness(spec, blurred)
        val refractivity = EffectTextureCatalog.refractivity(spec, blurred)
        val specular = EffectTextureCatalog.specular(spec, blurred)
        val rad = Math.toRadians(spec.lightAngleDeg.toDouble())
        val lx = cos(rad).toFloat()
        val ly = sin(rad).toFloat()
        val glass = parseColor(spec.glassColor)
        val light = parseColor(spec.lightColor)
        val bend = thickness * (refractivity - 1f) * 0.06f
        val shade = 1f + bend * (nx * 0.6f + ny * 0.4f)
        val facing = max(0f, nx * lx + ny * ly)
        val half = max(0f, facing * 0.5f + nz * 0.5f)
        val h2 = half * half
        val specTerm = if (specular <= 0f || spec.lightStrength <= 0f) 0f else specular * h2 * h2 * 0.02f
        val alpha = ((glass ushr 24 and 0xFF) * (0.55f + 0.45f * h)).toInt().coerceIn(0, 255)
        val lift = (specTerm * 255f).toInt().coerceIn(0, 255)
        val r = ((glass shr 16 and 0xFF) * shade + (light shr 16 and 0xFF) * specTerm).toInt().coerceIn(0, 255)
        val g = ((glass shr 8 and 0xFF) * shade + (light shr 8 and 0xFF) * specTerm).toInt().coerceIn(0, 255)
        val b = ((glass and 0xFF) * shade + (light and 0xFF) * specTerm).toInt().coerceIn(0, 255)
        return (max(alpha, lift) shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * 折射偏移强度（像素）：按短边折算，与分辨率无关。
     *
     * 系数 = 厚度 × (折射率 − 1) × 短边 × 0.02。
     */
    fun refractStrength(spec: EffectTextureSpec, blurred: Boolean, shortSidePx: Float): Float =
        EffectTextureCatalog.thickness(spec, blurred) *
            (EffectTextureCatalog.refractivity(spec, blurred) - 1f) * shortSidePx * 0.02f

    /**
     * 按环绕模式把贴图铺满目标尺寸的采样表。
     *
     * [pixels] 为源图 AARRGGBB 行优先数组；输出每格存原样 texel，未着色。
     */
    fun sampleGrid(
        pixels: IntArray,
        sourceWidth: Int,
        sourceHeight: Int,
        spec: EffectTextureSpec,
        widthPx: Int,
        heightPx: Int,
    ): IntArray {
        val out = IntArray(max(0, widthPx) * max(0, heightPx))
        if (out.isEmpty() || sourceWidth <= 0 || sourceHeight <= 0) return out
        val region = region(spec, sourceWidth, sourceHeight)
        val rw = region[2]
        val rh = region[3]
        val scale = max(0.01f, spec.layoutScale)
        var i = 0
        for (y in 0 until heightPx) {
            val v = y.toFloat() / heightPx
            val sy = sampleCoord(v * rh * scale, rh, spec.wrapY)
            val rowBase = (region[1] + sy) * sourceWidth + region[0]
            for (x in 0 until widthPx) {
                val u = x.toFloat() / widthPx
                val sx = sampleCoord(u * rw * scale, rw, spec.wrapX)
                val idx = rowBase + sx
                out[i++] = if (idx in pixels.indices) pixels[idx] else 0
            }
        }
        return out
    }

    /** 采样 + 逐像素着色，输出可直接建位图的 AARRGGBB 数组。 */
    fun shadeGrid(
        pixels: IntArray,
        sourceWidth: Int,
        sourceHeight: Int,
        spec: EffectTextureSpec,
        widthPx: Int,
        heightPx: Int,
        blurred: Boolean,
    ): IntArray {
        val grid = sampleGrid(pixels, sourceWidth, sourceHeight, spec, widthPx, heightPx)
        for (i in grid.indices) grid[i] = shadePixel(grid[i], spec, blurred)
        return grid
    }

    /**
     * 按法线偏移场重采样壁纸：法线 XY 当位移，强度按短边折算。
     *
     * [wallpaper] 为壁纸 AARRGGBB 行优先数组，[grid] 为已采样贴图（就地改写为折射结果）。
     * 目标尺寸或壁纸尺寸非正时原样返回，不抛。
     */
    fun refractGrid(
        wallpaper: IntArray,
        wallpaperWidth: Int,
        wallpaperHeight: Int,
        grid: IntArray,
        spec: EffectTextureSpec,
        widthPx: Int,
        heightPx: Int,
        blurred: Boolean,
    ): IntArray {
        if (widthPx <= 0 || heightPx <= 0) return grid
        if (wallpaperWidth <= 0 || wallpaperHeight <= 0) return grid
        if (grid.size < widthPx * heightPx) return grid
        val strength = refractStrength(spec, blurred, minOf(widthPx, heightPx).toFloat())
        var i = 0
        for (y in 0 until heightPx) {
            val v = y.toFloat() / heightPx
            val wy = (v * wallpaperHeight).toInt().coerceIn(0, wallpaperHeight - 1)
            for (x in 0 until widthPx) {
                val u = x.toFloat() / widthPx
                val px = grid[i]
                val ox = (u * wallpaperWidth + normalX(px) * strength)
                    .toInt().coerceIn(0, wallpaperWidth - 1)
                val oy = (wy + normalY(px) * strength)
                    .toInt().coerceIn(0, wallpaperHeight - 1)
                val idx = oy * wallpaperWidth + ox
                grid[i] = if (idx in wallpaper.indices) wallpaper[idx] else 0
                i++
            }
        }
        return grid
    }

    /** 浮雕明暗：法线对光向的投影当亮度，高度当不透明度，输出灰度 AARRGGBB（就地改写）。 */
    fun reliefGrid(
        pixels: IntArray,
        sourceWidth: Int,
        sourceHeight: Int,
        spec: EffectTextureSpec,
        widthPx: Int,
        heightPx: Int,
        oversample: Int = RELIEF_OVERSAMPLE,
    ): IntArray {
        val factor = if (oversample < 1) 1 else oversample
        val grid = sampleGrid(pixels, sourceWidth, sourceHeight, spec, widthPx * factor, heightPx * factor)
        if (grid.isEmpty()) return grid
        val rad = Math.toRadians(spec.lightAngleDeg.toDouble())
        val lx = cos(rad).toFloat()
        val ly = sin(rad).toFloat()
        val gain = 2.2f + spec.thickness * 0.8f
        for (i in grid.indices) {
            val px = grid[i]
            val facing = normalX(px) * lx + normalY(px) * ly
            val lum = (128f + facing * gain * 127f).toInt().coerceIn(0, 255)
            val alpha = (150f + height(px) * 90f).toInt().coerceIn(0, 255)
            grid[i] = (alpha shl 24) or (lum shl 16) or (lum shl 8) or lum
        }
        return grid
    }

    /** 浮雕层的超采样倍率。 */
    const val RELIEF_OVERSAMPLE = 4

    /** 采样区域的左上与宽高。 */
    fun region(spec: EffectTextureSpec, sourceWidth: Int, sourceHeight: Int): IntArray {
        val left = if (spec.hasRegion) spec.region[0] else 0
        val top = if (spec.hasRegion) spec.region[1] else 0
        val right = if (spec.hasRegion) spec.region[2] else sourceWidth
        val bottom = if (spec.hasRegion) spec.region[3] else sourceHeight
        return intArrayOf(left, top, max(1, right - left), max(1, bottom - top))
    }

    /** 移轴景深：相对高度处的模糊权重，0=清晰、1=最模糊。 */
    fun tiltShiftWeight(yFraction: Float, layoutHeightPx: Float): Float {
        if (layoutHeightPx <= 0f) return 0f
        val band = TiltShiftSpec.bandHeightPx(layoutHeightPx)
        val center = TiltShiftSpec.bandCenterPx(layoutHeightPx)
        val feather = max(1f, TiltShiftSpec.featherPx(layoutHeightPx))
        val dist = abs(yFraction * layoutHeightPx - center)
        val half = band / 2f
        if (dist <= half) return 0f
        return ((dist - half) / feather).coerceIn(0f, 1f)
    }

    /** 移轴清晰带上下边界（相对高度）。 */
    fun tiltShiftBand(layoutHeightPx: Float): FloatArray {
        if (layoutHeightPx <= 0f) return floatArrayOf(0f, 0f)
        val band = TiltShiftSpec.bandHeightPx(layoutHeightPx)
        val center = TiltShiftSpec.bandCenterPx(layoutHeightPx)
        return floatArrayOf(
            max(0f, (center - band / 2f) / layoutHeightPx),
            minOf(1f, (center + band / 2f) / layoutHeightPx),
        )
    }
}
