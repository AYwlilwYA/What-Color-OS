package com.coloros16.liquidglass.ui.aod

import android.graphics.Bitmap
import kotlin.math.max

/** 预览共用的位图工具：统一取景裁切与可分离盒式模糊。 */
internal object AodBitmap {

    /** 按目标宽高比居中裁切并缩放到指定高度。 */
    fun cover(source: Bitmap, aspect: Float, targetHeight: Int = 1280): Bitmap {
        val targetW = (targetHeight * aspect).toInt().coerceAtLeast(1)
        val sourceAspect = source.width.toFloat() / source.height
        val cropW: Int
        val cropH: Int
        if (sourceAspect > aspect) {
            cropH = source.height
            cropW = (source.height * aspect).toInt().coerceAtLeast(1)
        } else {
            cropW = source.width
            cropH = (source.width / aspect).toInt().coerceAtLeast(1)
        }
        val left = ((source.width - cropW) / 2).coerceAtLeast(0)
        val top = ((source.height - cropH) / 2).coerceAtLeast(0)
        val crop = Bitmap.createBitmap(source, left, top, cropW, cropH)
        val fitted = Bitmap.createScaledBitmap(crop, targetW, targetHeight, true)
        if (crop !== fitted && crop !== source) crop.recycle()
        return fitted
    }

    /** 降采样后做多趟盒式模糊，再放大回原尺寸。 */
    fun blur(source: Bitmap, downscale: Int, radius: Int, passes: Int = 1): Bitmap {
        val factor = downscale.coerceAtLeast(1)
        val smallW = max(1, source.width / factor)
        val smallH = max(1, source.height / factor)
        val small = Bitmap.createScaledBitmap(source, smallW, smallH, true)
        val pixels = IntArray(smallW * smallH)
        small.getPixels(pixels, 0, smallW, 0, 0, smallW, smallH)
        repeat(passes.coerceAtLeast(1)) { boxBlur(pixels, smallW, smallH, radius) }
        small.setPixels(pixels, 0, smallW, 0, 0, smallW, smallH)
        val soft = Bitmap.createScaledBitmap(small, source.width, source.height, true)
        small.recycle()
        return soft
    }

    /** 单趟半径 r 的可分离盒式模糊。 */
    fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int) {
        if (radius <= 0) return
        val temp = IntArray(pixels.size)
        val span = radius * 2 + 1
        for (y in 0 until height) {
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            for (i in -radius..radius) {
                val x = i.coerceIn(0, width - 1)
                val c = pixels[y * width + x]
                a += c ushr 24 and 0xFF
                r += c ushr 16 and 0xFF
                g += c ushr 8 and 0xFF
                b += c and 0xFF
            }
            for (x in 0 until width) {
                temp[y * width + x] = (a / span shl 24) or (r / span shl 16) or (g / span shl 8) or (b / span)
                val outX = (x - radius).coerceIn(0, width - 1)
                val inX = (x + radius + 1).coerceIn(0, width - 1)
                val out = pixels[y * width + outX]
                val inc = pixels[y * width + inX]
                a += (inc ushr 24 and 0xFF) - (out ushr 24 and 0xFF)
                r += (inc ushr 16 and 0xFF) - (out ushr 16 and 0xFF)
                g += (inc ushr 8 and 0xFF) - (out ushr 8 and 0xFF)
                b += (inc and 0xFF) - (out and 0xFF)
            }
        }
        for (x in 0 until width) {
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            for (i in -radius..radius) {
                val y = i.coerceIn(0, height - 1)
                val c = temp[y * width + x]
                a += c ushr 24 and 0xFF
                r += c ushr 16 and 0xFF
                g += c ushr 8 and 0xFF
                b += c and 0xFF
            }
            for (y in 0 until height) {
                pixels[y * width + x] = (a / span shl 24) or (r / span shl 16) or (g / span shl 8) or (b / span)
                val outY = (y - radius).coerceIn(0, height - 1)
                val inY = (y + radius + 1).coerceIn(0, height - 1)
                val out = temp[outY * width + x]
                val inc = temp[inY * width + x]
                a += (inc ushr 24 and 0xFF) - (out ushr 24 and 0xFF)
                r += (inc ushr 16 and 0xFF) - (out ushr 16 and 0xFF)
                g += (inc ushr 8 and 0xFF) - (out ushr 8 and 0xFF)
                b += (inc and 0xFF) - (out and 0xFF)
            }
        }
    }
}
