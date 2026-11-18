package com.coloros16.liquidglass.engine

/** 桌面时钟小部件在屏幕上的像素矩形（左上/右下）。 */
data class WidgetRectPx(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f
}

/**
 * 桌面时钟小部件识别与 HOME 锚点换算：纯逻辑，无 android 依赖，可 JVM 单测。
 * 锚点坐标与 [ClockStateMachine] 一致：屏幕归一化中心点 + 高度比缩放。
 */
object ClockAnchorMapper {

    const val CLOCK_PKG = "com.coloros.alarmclock"

    private val EXACT_COMPONENTS = setOf(
        "com.coloros.alarmclock/com.coloros.widget.smallweather.OppoWeatherSingle",
        "com.coloros.alarmclock/com.coloros.widget.smallweather.OppoWeatherVertical",
    )

    const val SCALE_MIN = 0.05f
    const val SCALE_MAX = 3f

    /** 命中级别：精确组件 > 同包兜底 > 不是时钟。 */
    enum class Match { NONE, EXACT, PACKAGE }

    /** 按 provider 的包名/类名判定是否桌面时钟小部件。 */
    fun match(pkg: String?, cls: String?): Match {
        if (pkg != CLOCK_PKG) return Match.NONE
        if (cls == null) return Match.PACKAGE
        return if ("$pkg/$cls" in EXACT_COMPONENTS) Match.EXACT else Match.PACKAGE
    }

    /**
     * 桌面时钟小部件矩形 → HOME 锚点；矩形非法、屏幕尺寸未知或锁屏时钟高度无效时返回 null
     * （锁屏时钟高度无效只降级缩放为 1，仍返回锚点）。
     */
    fun fromWidgetRect(
        rect: WidgetRectPx,
        screenW: Int,
        screenH: Int,
        lockClockHeightPx: Float,
    ): ClockAnchor? = fromTargetRect(rect, screenW, screenH, lockClockHeightPx)

    /**
     * 任意时钟终点矩形（桌面时钟小部件 / 状态栏时钟）→ HOME 锚点。
     * [lockClockHeightPx] 为锁屏时钟高度（缩放基准）；未知传 0，缩放退化为 1。
     */
    fun fromTargetRect(
        rect: WidgetRectPx,
        screenW: Int,
        screenH: Int,
        lockClockHeightPx: Float,
    ): ClockAnchor? {
        if (screenW <= 0 || screenH <= 0) return null
        if (rect.width <= 0 || rect.height <= 0) return null
        if (rect.right <= 0 || rect.bottom <= 0 || rect.left >= screenW || rect.top >= screenH) return null
        // 相邻页的小部件可能只露一部分，中心不在屏内即视为当前不可见
        if (rect.centerX <= 0f || rect.centerX >= screenW || rect.centerY <= 0f || rect.centerY >= screenH) return null
        val scale = if (lockClockHeightPx > 0f) {
            (rect.height / lockClockHeightPx).coerceIn(SCALE_MIN, SCALE_MAX)
        } else {
            1f
        }
        return ClockAnchor(
            centerX = rect.centerX / screenW,
            centerY = rect.centerY / screenH,
            scale = scale,
            alpha = 1f,
        )
    }

    /** HOME 锚点优先级：桌面时钟小部件 > 状态栏时钟 > 隐藏。 */
    fun pickHomeAnchor(widget: ClockAnchor?, statusBar: ClockAnchor?): ClockAnchor =
        widget ?: statusBar ?: ClockAnchor(alpha = 0f)
}
