package com.coloros16.liquidglass.engine

import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 相框裁切：把用户裁切参数换成 shader 用的 mat3，前层与背层共用同一裁切。
 *
 * 前层 = 相框内照片，采样原点先减固定偏移；背层 = 纸后照片，先做自身开窗反投影再套裁切。
 * 矩阵按列主序 9 元，与 GLSL mat3 上传口径一致。纯 JVM，预览与 hook 共用。
 */
object FrameCropMath {

    /** 方形网格边长基准（原版素材坐标系）。 */
    const val MESH_SIDE = 720f

    /** 前层照片在方形网格里占的宽度。 */
    const val PHOTO_WIDTH = 320f

    /** 前层采样原点相对网格的纵向偏移。 */
    const val FRONT_ORIGIN_Y = 348.5f / MESH_SIDE

    const val MIN_SIZE = 0.05f
    const val MAX_SIZE = 1f
    const val MAX_ANGLE_DEG = 45f

    val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    /** 用户裁切参数：归一化中心 + 尺寸倍数 + 角度。 */
    class Crop {
        var centerX = 0.5f
        var centerY = 0.5f
        var size = 1f
        var angleDeg = 0f

        fun set(x: Float, y: Float, s: Float, angle: Float) {
            centerX = x.coerceIn(0f, 1f)
            centerY = y.coerceIn(0f, 1f)
            size = s.coerceIn(MIN_SIZE, MAX_SIZE)
            angleDeg = angle.coerceIn(-MAX_ANGLE_DEG, MAX_ANGLE_DEG)
        }
    }

    /** 采样边长（原图像素）：min(图宽,图高) × size × 720/320，保持图像比例。 */
    fun samplePixels(crop: Crop, imageWidth: Int, imageHeight: Int): Float {
        if (imageWidth <= 0 || imageHeight <= 0) return 0f
        return min(imageWidth, imageHeight).toFloat() * crop.size * MESH_SIDE / PHOTO_WIDTH
    }

    /** 前层裁切矩阵：按原图宽高分别折算采样跨度。 */
    fun cropMatrix(crop: Crop, imageWidth: Int, imageHeight: Int): FloatArray {
        if (imageWidth <= 0 || imageHeight <= 0) return IDENTITY.copyOf()
        val pixels = samplePixels(crop, imageWidth, imageHeight)
        val x = pixels / imageWidth
        val y = pixels / imageHeight
        val rad = Math.toRadians(crop.angleDeg.toDouble())
        val s = sin(rad).toFloat()
        val c = cos(rad).toFloat()
        return floatArrayOf(
            c * x, -s * y, 0f,
            s * x, c * y, 0f,
            crop.centerX, crop.centerY, 1f,
        )
    }

    /** 前层采样原点偏移：UV 先减它再乘裁切矩阵。 */
    fun frontOrigin(): FloatArray = floatArrayOf(0.5f, FRONT_ORIGIN_Y)

    /**
     * 背层复合矩阵 = 裁切 × 背纸反投影。
     *
     * 背纸自己在动，必须先撤销它的旋转平移，再套用户裁切，否则裁切会跟着纸转。
     */
    fun rearMatrix(crop: Crop, imageWidth: Int, imageHeight: Int, micros: Float): FloatArray =
        mul3(cropMatrix(crop, imageWidth, imageHeight), FrameRearPoses.matrix(micros))

    /** 列主序 3×3 乘法：out = a × b。 */
    fun mul3(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(9)
        for (col in 0..2) {
            for (row in 0..2) {
                var sum = 0f
                for (k in 0..2) sum += a[k * 3 + row] * b[col * 3 + k]
                out[col * 3 + row] = sum
            }
        }
        return out
    }

    /** 用矩阵变换一个 UV 点（齐次 w 恒 1）。 */
    fun transform(m: FloatArray, u: Float, v: Float): FloatArray = floatArrayOf(
        m[0] * u + m[3] * v + m[6],
        m[1] * u + m[4] * v + m[7],
    )

    /**
     * 双照片是否生效：只有相框样式（aodKind=1）且开了配对且有第二张照片。
     *
     * 与原版 `pairedFrame()` 同口径，避免其它样式误采样第二张图。
     */
    fun pairActive(aodKind: Int, pairEnabled: Boolean, hasFramePhoto: Boolean): Boolean =
        aodKind == AOD_KIND_FRAME && pairEnabled && hasFramePhoto

    /** 相框样式的 aod 档位号。 */
    const val AOD_KIND_FRAME = 1

    /**
     * 双照片时前层照片目标的选择：相框样式 + 有第二张 + 锁屏比率>0 时用锁屏层，否则用桌面层。
     *
     * 原版 endpoint = if aod==1 && images[5]!=0 && ratio[1]>0 {1} else {3}。
     */
    fun frontSourceIndex(pairActive: Boolean, lockRatio: Float): Int =
        if (pairActive && lockRatio > 0f) 1 else 3
}