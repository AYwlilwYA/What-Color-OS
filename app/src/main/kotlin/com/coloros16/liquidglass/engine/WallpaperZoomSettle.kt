package com.coloros16.liquidglass.engine

/**
 * 壁纸缩放的落位补间：边内口径与静止态口径不同时，交接那一帧补一段过渡。
 *
 * 边内 LOCK↔HOME 恒 1.1（参考实现恒等口径），静止桌面 1.0（系统真值），
 * 两个口径各自都对，合起来在落位/起手那一帧有缝。本件只补交接，不改任一口径。
 * 纯 JVM，不持时钟：调用方传入已经过毫秒。
 */
object WallpaperZoomSettle {

    /** 补间时长：落位那 9.1% 缩水分到约 10 帧，每帧上沿移约 12px。 */
    const val SETTLE_MS = 160L

    /** 差值小于此值视为同口径，不起补间（避免浮点噪声触发无意义动画）。 */
    const val EPS = 1e-3f

    /** 该次落位是否需要补间：两端缩放不同才补。 */
    fun needed(fromZoom: Float, toZoom: Float): Boolean =
        kotlin.math.abs(fromZoom - toZoom) > EPS

    /** 补间进度 0..1。 */
    fun progress(elapsedMs: Long): Float {
        if (SETTLE_MS <= 0L) return 1f
        return (elapsedMs.coerceAtLeast(0L).toFloat() / SETTLE_MS).coerceIn(0f, 1f)
    }

    /** 补间是否已走完。 */
    fun done(elapsedMs: Long): Boolean = elapsedMs >= SETTLE_MS

    /**
     * 当前缩放：起点接边末值保证不跳变，按壁纸曲线收敛到静止值。
     *
     * 曲线与转场期壁纸缩放同一条，落位段与边内段观感连续。
     */
    fun zoomAt(fromZoom: Float, toZoom: Float, elapsedMs: Long): Float {
        if (!needed(fromZoom, toZoom)) return toZoom
        val e = SystemSceneMotion.WALLPAPER.map(progress(elapsedMs))
        return fromZoom + (toZoom - fromZoom) * e
    }
}
