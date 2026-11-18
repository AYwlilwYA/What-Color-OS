package com.coloros16.liquidglass.engine

/** 一个笔画采样点：坐标 + 该步颜色（浮点 RGBA，低透明度多次叠加成色）。 */
data class HandPaintPoint(
    val x: Float,
    val y: Float,
    val r: Float,
    val g: Float,
    val b: Float,
    val a: Float,
)

/** 一条丝线的对称与画笔参数（渲染期复制用，不参与帧间变换）。 */
data class HandPaintSilk(
    val id: Int,
    val enabled: Boolean,
    val mirror: Boolean,
    val rotates: Int,
    val spirals: Int,
    val brushLineWidth: Float,
    val centerX: Float,
    val centerY: Float,
    val points: List<HandPaintPoint>,
)

/**
 * 手绘笔画求值：把厂商滑动窗口数据还原成完整轨迹，并剔除停笔时间。
 *
 * 厂商每帧只保留固定容量的点窗口（旧点出队新点入队），播完贴完成图；
 * 本件按采集原序去重得到全量轨迹，再把原地重复点折叠掉，
 * 使播放进度按有效笔画推进而不是按原始帧号。
 */
object HandPaintMotion {

    /** 原地重复判定阈值（坐标空间单位）；小于此视为停笔未移动。 */
    const val STILL_EPSILON = 0.01f

    /** 厂商窗口容量（点/帧），实测九款恒为该值。 */
    const val WINDOW_CAPACITY = 150

    /** 画笔线宽缺省（部分预设未写该字段）。 */
    const val DEFAULT_BRUSH_WIDTH = 2f

    /**
     * 按采集原序去重：同坐标连续重复只留第一个。
     *
     * 只折叠**相邻**重复，不折叠全局重复 —— 对称复制会让不同笔画落到同一位置，
     * 全局去重会丢笔画。
     */
    fun dedupe(points: List<HandPaintPoint>): List<HandPaintPoint> {
        if (points.isEmpty()) return emptyList()
        val out = ArrayList<HandPaintPoint>(points.size)
        var prev: HandPaintPoint? = null
        for (p in points) {
            val last = prev
            if (last != null && still(last, p)) continue
            out.add(p)
            prev = p
        }
        return out
    }

    /** 两点是否视为原地未动。 */
    fun still(a: HandPaintPoint, b: HandPaintPoint): Boolean {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return dx * dx + dy * dy < STILL_EPSILON * STILL_EPSILON
    }

    /**
     * 完整重放进度 → 已画到的有效点下标（含端点）。
     *
     * [progress] 0..1 线性映射到有效点数；空轨迹返回 0。
     */
    fun drawnCount(progress: Float, total: Int): Int {
        if (total <= 0) return 0
        val p = progress.coerceIn(0f, 1f)
        return (p * total).toInt().coerceIn(0, total)
    }

    /** 该进度是否已完整画完。 */
    fun finished(progress: Float, total: Int): Boolean =
        total <= 0 || drawnCount(progress, total) >= total

    /**
     * 对称复制份数：旋转份数 × 镜像倍数 × 螺旋层数。
     *
     * 旋转份数非正时按 1 处理（不复制）；螺旋层数非正同理。
     */
    fun copiesOf(silk: HandPaintSilk): Int {
        val rot = if (silk.rotates > 0) silk.rotates else 1
        val mir = if (silk.mirror) 2 else 1
        val spir = if (silk.spirals > 0) silk.spirals else 1
        return rot * mir * spir
    }

    /** 第 k 份旋转副本的角度（度）。 */
    fun copyAngleDeg(silk: HandPaintSilk, rotateIndex: Int): Float {
        val rot = if (silk.rotates > 0) silk.rotates else 1
        return 360f * rotateIndex / rot
    }

    /**
     * 有效轨迹长度占原始采集点数的比例：停笔越多该值越小。
     *
     * 用于诊断「剔除了多少停顿」，空输入返回 0。
     */
    fun keptRatio(raw: Int, kept: Int): Float =
        if (raw <= 0) 0f else kept.toFloat() / raw
}