package com.coloros16.liquidglass.engine

/**
 * 非 SCENE 样式的接缝配方：揭开边源层不淡出，其余边整屏纵向推移。
 *
 * 与 SCENE 样式（镜片/照片/手绘/天空）的交叠溶解是两套口径，
 * 本件只给「没有世界层」的样式用，避免两套语义混在一份配方里。
 */
object SeamProfilePolicy {

    /** 推移行程：屏高的该比例。 */
    const val SLIDE_FRACTION = 0.22f

    /** 源层不淡出的起点：取 1 表示整程恒不透明，直到被揭开圆覆盖。 */
    const val NO_OUTER_FADE = 1f

    /** 源层不额外放大。 */
    const val NO_OUTER_SCALE = 1f

    /** 该边是否走几何揭开（两端含息屏且终点是锁屏）。 */
    fun revealEdge(edge: TransitionEdge): Boolean =
        edge == TransitionEdge.AOD_TO_LOCK || edge == TransitionEdge.LOCK_TO_AOD

    /**
     * 该边是否走整屏推移。
     *
     * 入桌面与桌面回锁屏都推移；揭开两条边不推移（由圆覆盖）。
     */
    fun slideEdge(edge: TransitionEdge): Boolean = !revealEdge(edge)

    /**
     * 推移方向：向更外层切换时内容整体向上走，回到更内层时向下走。
     *
     * 三态由内到外 = 息屏 → 锁屏 → 桌面。
     * 求值器里 UP 的位移是负 y，且 src 走 `slideY*e`、dst 走 `slideY*(1-e)`
     * ⇒ 两层同向：UP 时目标从上方滑入、源继续向上移出。
     */
    fun slideDirection(edge: TransitionEdge): SlideDirection =
        if (rank(edge.to) > rank(edge.from)) SlideDirection.UP else SlideDirection.DOWN

    private fun rank(state: SceneState): Int = when (state) {
        SceneState.AOD -> 0
        SceneState.LOCK -> 1
        SceneState.HOME -> 2
    }

    /**
     * 把接缝口径写进一份配方。
     *
     * 揭开边：只开遮罩，源层恒不透明不放大（圆外不淡出）。
     * 推移边：只开内容层并给方向与行程，遮罩关掉（无圆）。
     */
    fun apply(edge: TransitionEdge, profile: TransitionProfile) {
        if (revealEdge(edge)) {
            profile.mask.enabled = true
            profile.mask.outerFadeStart = NO_OUTER_FADE
            profile.mask.outerScaleTo = NO_OUTER_SCALE
            profile.content.enabled = false
            profile.content.direction = SlideDirection.NONE
            profile.content.slideFraction = 0f
        } else {
            profile.mask.enabled = false
            profile.content.enabled = true
            profile.content.direction = slideDirection(edge)
            profile.content.slideFraction = SLIDE_FRACTION
        }
    }

    /**
     * 推移期的两端不透明度：目标层恒不透明滑入盖住源层，源层恒不透明。
     *
     * 求值器的内容层默认给 crossfade（srcAlpha=1-e / dstAlpha=e），
     * 推移口径要的是「盖住」不是「溶解」，故在求值后按本策略改写。
     * 只在推移边生效，揭开边与无方向配方一律不动。
     */
    fun opaqueSlide(config: ContentLayerConfig, out: TransitionParams) {
        if (!config.enabled || config.direction == SlideDirection.NONE) return
        if (config.slideFraction <= 0f) return
        out.srcAlpha = 1f
        out.dstAlpha = 1f
        out.srcScale = 1f
        out.dstScale = 1f
    }
}
