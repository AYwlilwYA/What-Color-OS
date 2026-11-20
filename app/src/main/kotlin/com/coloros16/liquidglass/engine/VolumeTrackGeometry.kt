package com.coloros16.liquidglass.engine

/**
 * 音量条轨道矩形求值：镜像系统 seekbar 的背景矩形公式。
 * 系统「粗细条」是内部尺寸动画（cur 尺寸/形变值），View bounds 恒定；
 * 玻璃要贴轨道就必须按同一公式取几何。纯 JVM，可单测。
 */
object VolumeTrackGeometry {

    /** 轨道几何输入（全部 px；形变值 = 拖拽拉伸的上下/宽度偏移）。 */
    data class Input(
        val viewWidth: Int,
        val viewHeight: Int,
        val paddingStart: Int,
        val paddingEnd: Int,
        val paddingTop: Int,
        val paddingBottom: Int,
        val curPaddingVertical: Float,
        val curBackgroundRadius: Float,
        val curBackgroundWidth: Float,
        val widthDeformed: Float,
        val heightTopDeformedUp: Float,
        val heightTopDeformedDown: Float,
        val heightBottomDeformedUp: Float,
        val heightBottomDeformedDown: Float,
    )

    /** 轨道矩形（View 局部坐标）与圆角。 */
    data class Track(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val cornerRadius: Float,
    ) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        fun isValid(): Boolean = width > 0f && height > 0f
    }

    /** 水平中心：与系统 getSeekBarCenterX 同式。 */
    fun centerX(i: Input): Float =
        i.paddingStart + ((i.viewWidth - i.paddingStart - i.paddingEnd) shr 1).toFloat()

    /** 背景矩形：与系统 setBackgroundRect 同式（top/bottom 含形变与圆角外扩）。 */
    fun track(i: Input): Track {
        val cx = centerX(i)
        val half = i.curBackgroundWidth / 2f - i.widthDeformed
        val top = (i.paddingTop + i.curPaddingVertical - i.curBackgroundRadius) -
            i.heightTopDeformedUp + i.heightTopDeformedDown
        val bottom = (i.viewHeight - i.paddingBottom - i.curPaddingVertical + i.curBackgroundRadius) -
            i.heightBottomDeformedUp + i.heightBottomDeformedDown
        return Track(cx - half, top, cx + half, bottom, i.curBackgroundRadius)
    }
}
