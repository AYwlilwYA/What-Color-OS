package com.coloros16.liquidglass.engine

/**
 * 色键帧的三通道语义与揭开权重口径。
 *
 * 色键帧只有 RGB 三通道（无 alpha）：R 主色权重、G 壁纸/照片权重、B 白权重。
 * 揭开遮罩的羽化就是 G 的连续梯度，不是任何可调数值。
 * 纯 JVM，预览与 hook 共用。
 */
object ChromakeyMask {

    const val CHANNEL_ACCENT = 0
    const val CHANNEL_REVEAL = 1
    const val CHANNEL_WHITE = 2

    /** 揭开权重：G 通道归一化到 0..1 并钳位。 */
    fun revealWeight(g: Int): Float = (g.coerceIn(0, 255)) / 255f

    /** 主色权重：R 通道。 */
    fun accentWeight(r: Int): Float = (r.coerceIn(0, 255)) / 255f

    /** 白权重：B 通道。 */
    fun whiteWeight(b: Int): Float = (b.coerceIn(0, 255)) / 255f

    /**
     * 三通道混合，与色键 shader 同序：黑底 → 主色 → 白 → 壁纸。
     *
     * [out] 长度 3，写入 0..1 的 RGB。顺序不可换：后写的覆盖前写的。
     */
    fun blend(
        r: Int,
        g: Int,
        b: Int,
        accent: FloatArray,
        content: FloatArray,
        out: FloatArray,
    ) {
        val wa = accentWeight(r)
        val ww = whiteWeight(b)
        val wr = revealWeight(g)
        for (i in 0..2) {
            var c = accent[i] * wa
            c += (1f - c) * ww
            c += (content[i] - c) * wr
            out[i] = c.coerceIn(0f, 1f)
        }
    }

    /**
     * 该风格是否由色键帧承担揭开（照片类五款）。
     *
     * 非照片类没有色键帧，揭开退回几何遮罩。
     */
    fun revealsByFrame(photoStyleId: Int): Boolean = photoStyleId in 1..5

    /** 揭开是否已实质完成（权重达阈值即视为整屏可见）。 */
    fun fullyRevealed(weight: Float): Boolean = weight >= 1f - EPS

    /** 揭开是否尚未开始。 */
    fun notRevealed(weight: Float): Boolean = weight <= EPS

    const val EPS = 1e-6f
}