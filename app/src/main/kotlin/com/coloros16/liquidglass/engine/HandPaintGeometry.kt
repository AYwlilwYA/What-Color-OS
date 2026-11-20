package com.coloros16.liquidglass.engine

/**
 * 手绘笔画的画布坐标换算与累积推进：纯 JVM，预览与 hook 共用。
 *
 * 厂商数据是固定见方的画布坐标（中心点 = 边长一半），
 * 本件负责换算到目标矩形、按有效点数推进、以及对称副本的角度与镜像。
 */
object HandPaintGeometry {

    /** 厂商画布基准边长（中心点 510 ⇒ 1020；款 6 中心 680 ⇒ 1360）。 */
    const val BASE_CENTER_RATIO = 0.5f

    /**
     * 每千点耗时（毫秒）：完整重放时长的唯一系数。
     *
     * 厂商只给窗口动画帧数（约 2s），完整重放无系统真值 ⇒ 此系数属自拟，
     * 取每秒约两千点，使万点级轨迹落在数秒量级而不是一闪而过。
     */
    const val REPLAY_MS_PER_KILO_POINT = 500L

    /** 重放时长上下限（毫秒）：点数极少或极多时都钳住。 */
    const val REPLAY_MIN_MS = 2000L
    const val REPLAY_MAX_MS = 20000L

    /** 由中心点推画布边长（厂商中心恒为边长一半）。 */
    fun canvasSide(centerX: Float, centerY: Float): Float =
        maxOf(centerX, centerY) / BASE_CENTER_RATIO

    /**
     * 画布坐标 → 目标矩形内的像素坐标（等比 contain，居中）。
     *
     * 返回 `[scale, offsetX, offsetY]`；画布边长非正时退化为 1 倍无偏移。
     */
    fun fit(canvasSide: Float, targetW: Float, targetH: Float): FloatArray {
        if (canvasSide <= 0f || targetW <= 0f || targetH <= 0f) {
            return floatArrayOf(1f, 0f, 0f)
        }
        val scale = minOf(targetW, targetH) / canvasSide
        return floatArrayOf(
            scale,
            (targetW - canvasSide * scale) * 0.5f,
            (targetH - canvasSide * scale) * 0.5f,
        )
    }

    /**
     * 贴图矩形：按官方 dp 边长折算的见方小图，水平居中、中心落在指定屏高比例处。
     *
     * 返回 `[left, top, side]`；边长不超过目标短边（小图不铺满整屏）。
     */
    fun presentRect(canvasPx: Float, centerYFraction: Float, targetW: Float, targetH: Float): FloatArray {
        if (targetW <= 0f || targetH <= 0f) return floatArrayOf(0f, 0f, 0f)
        val side = canvasPx.coerceIn(0f, minOf(targetW, targetH))
        val left = (targetW - side) * 0.5f
        val top = targetH * centerYFraction - side * 0.5f
        return floatArrayOf(left, top, side)
    }

    /** 单点换算：画布坐标 → 像素。 */
    fun mapX(x: Float, fit: FloatArray): Float = x * fit[0] + fit[1]

    fun mapY(y: Float, fit: FloatArray): Float = y * fit[0] + fit[2]

    /**
     * 完整重放时长：按有效点数线性折算，钳到上下限。
     *
     * 停笔已在解析期折叠，故点数即有效笔画量，时长不含停顿。
     */
    fun replayDurationMs(effectivePoints: Int): Long {
        if (effectivePoints <= 0) return REPLAY_MIN_MS
        val ms = effectivePoints.toLong() * REPLAY_MS_PER_KILO_POINT / 1000L
        return ms.coerceIn(REPLAY_MIN_MS, REPLAY_MAX_MS)
    }

    /**
     * 镜像副本的变换：绕画布中心水平翻转。
     *
     * 返回 `[scaleX, pivotX]`；不镜像时 scaleX=1。
     */
    fun mirrorOf(mirror: Boolean, centerPx: Float): FloatArray =
        if (mirror) floatArrayOf(-1f, centerPx) else floatArrayOf(1f, centerPx)

    /**
     * 螺旋层的半径缩放：第 s 层（0 基）按等比递减。
     *
     * ⚠️ 层间半径比无系统真值，此处为自拟基线，标注可调。
     */
    const val SPIRAL_RADIUS_STEP = 0.72f

    fun spiralScale(spiralIndex: Int): Float {
        if (spiralIndex <= 0) return 1f
        var s = 1f
        repeat(spiralIndex) { s *= SPIRAL_RADIUS_STEP }
        return s
    }

    /** 螺旋层的角偏移（度）：层间错开半个旋转格，避免完全重叠。 */
    fun spiralAngleDeg(spiralIndex: Int, rotates: Int): Float {
        val rot = if (rotates > 0) rotates else 1
        return spiralIndex * (360f / rot) * 0.5f
    }

    /** 低透明度笔触的 alpha 字节：厂商 A ∈ [0, 0.0993]，多次叠加成色。 */
    fun strokeAlphaByte(a: Float): Int = (a.coerceIn(0f, 1f) * 255f).toInt()

    /**
     * 累积绘制增量：本帧要补画的点区间 `[from, to)`。
     *
     * [drawnBefore] 为已画到的点数，[progress] 为当前完整重放进度。
     * 进度回退（反向转场）时返回空区间，由调用方决定是否重画整幅。
     */
    fun incrementRange(drawnBefore: Int, progress: Float, total: Int): IntArray {
        val target = HandPaintMotion.drawnCount(progress, total)
        if (target <= drawnBefore) return intArrayOf(drawnBefore, drawnBefore)
        return intArrayOf(drawnBefore, target)
    }

    /** 该进度是否需要清空重画（反向播放或换样式）。 */
    fun needsReset(drawnBefore: Int, progress: Float, total: Int): Boolean =
        HandPaintMotion.drawnCount(progress, total) < drawnBefore
}