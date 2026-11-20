package com.coloros16.liquidglass.engine

/**
 * 胶囊手势判定（纯 JVM）：横滑划掉 / 竖滑副岛轮换 / 展开卡上下滑。
 * 真值：速度阈值 86dp/s、位移>2×slop、主轴判定（参考 h1.java:56-58 / §4.2）。
 */
object SeedlingGesturePolicy {

    enum class Gesture { NONE, FLING_DISMISS, ROTATE_SATELLITE_LEFT, ROTATE_SATELLITE_RIGHT, PROMOTE_LEFT, PROMOTE_RIGHT, CARD_COLLAPSE, CARD_FLOATING_OPEN, CARD_CYCLE_LEFT, CARD_CYCLE_RIGHT }

    /**
     * 手势分类。
     * @param dx/dy 位移 px；vx/vy 速度 px/s；slop 系统触摸容差 px；density 屏幕密度
     * @param expanded 是否大卡态；hasSatellites 胶囊态是否有副岛
     * @param cardSwipeFloating 下滑小窗规则；launchableSource 来源可启动
     */
    fun classify(
        dx: Float,
        dy: Float,
        vx: Float,
        vy: Float,
        slop: Int,
        density: Float,
        expanded: Boolean,
        hasSatellites: Boolean,
        cardSwipeFloating: Boolean = true,
        launchableSource: Boolean = true,
        appleCycle: Boolean = false,
    ): Gesture {
        val threshold = 86f * density
        val horizontal = kotlin.math.abs(dx) > 2 * slop && kotlin.math.abs(vx) > threshold &&
            kotlin.math.abs(dx) > kotlin.math.abs(dy)
        if (horizontal) {
            // 苹果式大卡：横滑=左右换卡（不划掉）
            if (expanded && appleCycle) {
                return if (dx > 0) Gesture.CARD_CYCLE_RIGHT else Gesture.CARD_CYCLE_LEFT
            }
            // 有副岛：横滑=左右轮换副岛顶上；无副岛才划掉
            if (!expanded && hasSatellites) {
                return if (dx > 0) Gesture.ROTATE_SATELLITE_RIGHT else Gesture.ROTATE_SATELLITE_LEFT
            }
            return Gesture.FLING_DISMISS
        }
        val vertical = kotlin.math.abs(dy) > 2 * slop && kotlin.math.abs(vy) > threshold &&
            kotlin.math.abs(dy) > kotlin.math.abs(dx)
        if (!vertical) return Gesture.NONE
        if (expanded) {
            return when {
                dy < 0 -> Gesture.CARD_COLLAPSE
                cardSwipeFloating && launchableSource -> Gesture.CARD_FLOATING_OPEN
                else -> Gesture.NONE
            }
        }
        if (hasSatellites) {
            return if (dy < 0) Gesture.PROMOTE_LEFT else Gesture.PROMOTE_RIGHT
        }
        return Gesture.NONE
    }
}
