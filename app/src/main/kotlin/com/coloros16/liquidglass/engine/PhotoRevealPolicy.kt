package com.coloros16.liquidglass.engine

/**
 * 照片类揭开的目标底图来源：photoAlpha<1 时内容要混向目标态画面。
 *
 * 目标态是息屏时不参与（息屏是起点不是揭开目标）；
 * 拿不到目标快照时用壁纸底兜底，保证 mix 不会混到黑。
 */
object PhotoRevealPolicy {

    /** photoAlpha 达到此值视为不需要揭开混合（与 shader 的 <1.0 判据留一点余量）。 */
    const val FULL_ALPHA = 0.999f

    /** 该帧是否需要揭开混合。 */
    fun needed(photoAlpha: Float, target: SceneState?): Boolean =
        photoAlpha < FULL_ALPHA && target != null && target != SceneState.AOD

    /** 揭开目标态：有边取边的终点，无边取当前态。 */
    fun targetOf(edge: TransitionEdge?, state: SceneState): SceneState = edge?.to ?: state

    /**
     * 快照缓存键是否仍然有效。
     *
     * 输入集 = 目标态 + 画布尺寸 + 壁纸身份；任一变化都要重建，
     * 否则换了壁纸还返回旧图（画面不动且无日志）。
     */
    fun keyValid(
        cachedKey: String?,
        target: SceneState,
        widthPx: Int,
        heightPx: Int,
        backdropId: Int,
    ): Boolean = cachedKey != null && cachedKey == keyOf(target, widthPx, heightPx, backdropId)

    /** 生成缓存键。 */
    fun keyOf(target: SceneState, widthPx: Int, heightPx: Int, backdropId: Int): String =
        "${target.name}:${widthPx}x$heightPx:$backdropId"

    /** 快照降采样倍率：半边长，够 mix 用且省内存。 */
    const val SNAPSHOT_DIVISOR = 2

    /** 快照边长（至少 2px，避免 0 尺寸建位图抛异常）。 */
    fun snapshotSide(sizePx: Float): Int =
        (sizePx / SNAPSHOT_DIVISOR).toInt().coerceAtLeast(2)
}
