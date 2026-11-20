package com.coloros16.liquidglass.engine

/**
 * 局部背景亮度网格：把整屏像素归约成 GRID×GRID 均值，按坐标取所在格。
 *
 * 整屏均值判不出「暗壁纸上的浅色面板」，故前景取色须用局部值。
 * 纯 JVM，预览与 hook 共用；采样在快照期做一次，查询期 O(1)。
 */
object BrightnessGrid {

    /** 网格边长（格数 = GRID×GRID）。 */
    const val GRID = 12

    /** 采样步进：与整屏均值同口径，隔 4 像素取一点。 */
    const val STEP = 4

    /** 相对亮度（0..255）：ITU-R BT.601 加权，输入 ARGB。 */
    fun luminance(argb: Int): Int {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    /** 坐标 → 格下标（行优先）；越界夹到边缘格。 */
    fun cellIndex(x: Int, y: Int, w: Int, h: Int): Int {
        if (w <= 0 || h <= 0) return -1
        val gx = (x.toLong() * GRID / w).toInt().coerceIn(0, GRID - 1)
        val gy = (y.toLong() * GRID / h).toInt().coerceIn(0, GRID - 1)
        return gy * GRID + gx
    }

    /**
     * 归约成 GRID×GRID 均值（行优先，-1=该格无采样点）。
     * 尺寸非正返回 null，调用方据此回落整屏均值。
     */
    fun build(pixels: IntArray, w: Int, h: Int): IntArray? {
        if (w <= 0 || h <= 0 || pixels.size < w * h) return null
        val n = GRID * GRID
        val sums = LongArray(n)
        val counts = IntArray(n)
        var y = 0
        while (y < h) {
            val gy = (y.toLong() * GRID / h).toInt().coerceIn(0, GRID - 1)
            var x = 0
            while (x < w) {
                val gx = (x.toLong() * GRID / w).toInt().coerceIn(0, GRID - 1)
                val k = gy * GRID + gx
                sums[k] += luminance(pixels[y * w + x])
                counts[k]++
                x += STEP
            }
            y += STEP
        }
        return IntArray(n) { if (counts[it] > 0) (sums[it] / counts[it]).toInt() else -1 }
    }

    /** 取坐标所在格的均值；无网格/尺寸非法/该格无数据 → -1。 */
    fun at(grid: IntArray?, x: Int, y: Int, w: Int, h: Int): Int {
        val g = grid ?: return -1
        val k = cellIndex(x, y, w, h)
        if (k < 0 || k >= g.size) return -1
        return g[k]
    }
}