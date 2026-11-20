package com.coloros16.liquidglass.engine

/**
 * 时钟跨进程交接：桌面那颗钟本体在 Launcher 进程，SystemUI 只能把锁屏/息屏那颗推到桌面坐标。
 * 本件只回答「谁淡出、谁淡入、什么时候交」，求值纯 JVM，预览与 hook 共用。
 *
 * 交接基准跟本模块的内部时间轴（与遮罩/内容层同一个 t），不另起第二条时间线：
 * 系统解锁进度只在两端含息屏的三条边上有中间帧，桌面边只发状态，
 * 换用系统进度会让同一颗钟在不同边上走两套时钟。
 */
object ClockHandoffPolicy {

    /** 系统自己做这段平移的宿主：桌面内置灵感时钟，模块不叠第二套变换。 */
    const val CLASS_NATIVE_CLOCK = "com.android.launcher3.inspirationclock.InspirationClock"

    /** 系统平移的宣告方法：探到即认定宿主自带，让位。 */
    const val METHOD_NATIVE_UNLOCK_PARAMS = "setUnlockParams"

    /** 交接期两颗钟的可见度分工。 */
    enum class Side { SYSTEM_CLOCK, DESKTOP_WIDGET }

    /**
     * 本模块是否该补这段平移。
     *
     * [nativeClockPresent] = 宿主是否自带灵感时钟（静态点表 + 运行时探针取或）。
     * 自带即让位，避免双重动画。
     */
    fun moduleTranslates(nativeClockPresent: Boolean): Boolean = !nativeClockPresent

    /**
     * 该边是否涉及交接：只有终点或起点为桌面的四条边需要两颗钟换手。
     */
    fun handsOff(edge: TransitionEdge): Boolean =
        edge.from == SceneState.HOME || edge.to == SceneState.HOME

    /**
     * 交接进度：向桌面走取边进度本身，从桌面回来取反向进度。
     *
     * 0 = 完全由系统那颗钟承载，1 = 完全由桌面小部件承载；非交接边恒 0。
     */
    fun progress(edge: TransitionEdge, t: Float): Float {
        if (!handsOff(edge)) return 0f
        val q = t.coerceIn(0f, 1f)
        return if (edge.to == SceneState.HOME) q else 1f - q
    }

    /**
     * 某一侧此刻的可见度系数。
     *
     * 系统那颗钟随交接淡出、桌面小部件随交接淡入，两者相加恒 1（不出现双亮或双灭）。
     */
    fun alphaOf(side: Side, progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        return if (side == Side.SYSTEM_CLOCK) 1f - p else p
    }

    /**
     * 该不该把交接进度推给桌面进程。
     *
     * 值没变、使能位也没变就不推：桌面进程收到后写小部件 alpha，
     * 逐帧同值重复广播等于自己造一条跨进程轮询。
     * 静止态也要能推端值（桌面态 1 / 其余 0），否则转场结束后桌面那颗钟停在中间值。
     */
    fun shouldPublish(
        progress: Float,
        lastProgress: Float,
        enabled: Boolean,
        lastEnabled: Boolean,
    ): Boolean {
        if (enabled != lastEnabled) return true
        if (progress.isNaN() || lastProgress.isNaN()) return true
        return progress != lastProgress
    }

    /**
     * 桌面小部件在交接期是否该被本模块写 alpha。
     *
     * 让位档（宿主自带平移）一律不写；没有桌面时钟小部件时也不写。
     */
    fun writesWidget(nativeClockPresent: Boolean, widgetVisible: Boolean): Boolean =
        moduleTranslates(nativeClockPresent) && widgetVisible
}
