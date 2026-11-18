package com.coloros16.liquidglass.engine

/**
 * 自绘元素的占位去重：同一区域已被占用时后来者让位，避免半透明底板叠成双倍深色。
 *
 * 纯几何判定；绘制与顺序由调用方决定。
 */
object PaintOccupancy {

    /** 矩形（左上宽高），与平台 Rect 解耦。 */
    class Box(val left: Float, val top: Float, val width: Float, val height: Float) {
        val right: Float get() = left + width
        val bottom: Float get() = top + height
        fun valid(): Boolean = width > 0f && height > 0f
    }

    /** 两矩形是否相交（边界相接不算）。 */
    fun intersects(a: Box, b: Box): Boolean =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    /**
     * 逐个筛选：与已接受者相交的丢弃。
     *
     * 返回被接受的下标，顺序保持输入顺序（配置顺序稳定）。
     */
    fun accept(boxes: List<Box>): IntArray {
        val kept = ArrayList<Box>(boxes.size)
        val idx = ArrayList<Int>(boxes.size)
        for (i in boxes.indices) {
            val b = boxes[i]
            if (!b.valid()) continue
            if (kept.any { intersects(it, b) }) continue
            kept.add(b)
            idx.add(i)
        }
        return idx.toIntArray()
    }

    /** 两次绘制同一区域后的 src-over 累计不透明度。 */
    fun stackedAlpha(alpha: Float): Float = 1f - (1f - alpha) * (1f - alpha)
}