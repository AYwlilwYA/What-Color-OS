package com.coloros16.liquidglass.engine

/**
 * 系统三态动效真值：壁纸缩放 / 息屏层 / 锁屏层 / 桌面层 / 压暗。
 * 播放按系统实测时长与曲线求值；拖动按线性 1:1 求值。
 */
object SystemSceneMotion {

    const val AOD_TO_LOCK_MS = 600L
    const val LOCK_TO_AOD_MS = 500L
    const val LOCK_TO_HOME_MS = 800L
    const val HOME_TO_LOCK_MS = 600L
    const val AOD_TO_HOME_MS = 700L
    const val HOME_TO_AOD_MS = 500L

    const val LOCK_EXIT_MS = 250f
    const val AOD_CLOCK_IN_MS = 1000f

    const val WALLPAPER_SCALE_AOD = 1.0f
    const val WALLPAPER_SCALE_LOCK = 1.1f
    const val WALLPAPER_SCALE_HOME = 1.0f

    const val DIM_AOD = 0.62f
    const val DIM_LOCK = 0.30f
    const val DIM_HOME = 0.10f

    /** 息屏蒙版与内容退场曲线：cubic-bezier(0.33, 0, 0.67, 1)。 */
    val MASK = CubicBezierEasing(0.33f, 0f, 0.67f, 1f)

    /** 息屏时钟进场曲线：cubic-bezier(0.3, 0, 0.1, 1)。 */
    val AOD_CLOCK = CubicBezierEasing(0.3f, 0f, 0.1f, 1f)

    /** 壁纸缩放曲线：平台 AccelerateDecelerate。 */
    val WALLPAPER = Easing.ACCELERATE_DECELERATE

    private val alphaSpring600 = SpringEasing(61.69f, 1f, AOD_TO_LOCK_MS.toFloat())
    private val alphaSpring800 = SpringEasing(61.69f, 1f, LOCK_TO_HOME_MS.toFloat())
    private val alphaSpring500 = SpringEasing(61.69f, 1f, LOCK_TO_AOD_MS.toFloat())
    private val alphaSpring700 = SpringEasing(61.69f, 1f, AOD_TO_HOME_MS.toFloat())

    private val wallpaperSpring600 = SpringEasing(100f, 1f, AOD_TO_LOCK_MS.toFloat())
    private val wallpaperSpring800 = SpringEasing(100f, 1f, LOCK_TO_HOME_MS.toFloat())
    private val wallpaperSpring500 = SpringEasing(100f, 1f, LOCK_TO_AOD_MS.toFloat())

    /** 分层输出包；复用实例，逐帧不分配。 */
    class Layers {
        var wallpaperScale = WALLPAPER_SCALE_AOD
        var aodAlpha = 1f
        var aodClockScale = 1f
        var lockAlpha = 0f
        var lockScale = 1f
        var homeAlpha = 0f
        var homeScale = 1f
        var dim = DIM_AOD

        fun reset() {
            wallpaperScale = WALLPAPER_SCALE_AOD
            aodAlpha = 0f
            aodClockScale = 1f
            lockAlpha = 0f
            lockScale = 1f
            homeAlpha = 0f
            homeScale = 1f
            dim = DIM_AOD
        }
    }

    fun durationOf(edge: TransitionEdge): Long = when (edge) {
        TransitionEdge.AOD_TO_LOCK -> AOD_TO_LOCK_MS
        TransitionEdge.LOCK_TO_AOD -> LOCK_TO_AOD_MS
        TransitionEdge.LOCK_TO_HOME -> LOCK_TO_HOME_MS
        TransitionEdge.HOME_TO_LOCK -> HOME_TO_LOCK_MS
        TransitionEdge.AOD_TO_HOME -> AOD_TO_HOME_MS
        TransitionEdge.HOME_TO_AOD -> HOME_TO_AOD_MS
    }

    /** 时钟位移曲线：息屏段同蒙版，解锁段同壁纸缩放。 */
    fun clockCurve(edge: TransitionEdge): Easing = when (edge) {
        TransitionEdge.LOCK_TO_HOME, TransitionEdge.HOME_TO_LOCK -> WALLPAPER
        else -> MASK
    }

    fun sampleStatic(state: SceneState, out: Layers) {
        out.reset()
        when (state) {
            SceneState.AOD -> {
                out.aodAlpha = 1f
                out.dim = DIM_AOD
            }

            SceneState.LOCK -> {
                out.aodAlpha = 0f
                out.lockAlpha = 1f
                out.wallpaperScale = WALLPAPER_SCALE_LOCK
                out.dim = DIM_LOCK
            }

            SceneState.HOME -> {
                out.aodAlpha = 0f
                out.homeAlpha = 1f
                out.dim = DIM_HOME
            }
        }
    }

    fun sample(edge: TransitionEdge, t: Float, out: Layers, direct: Boolean = false) {
        out.reset()
        val p = t.coerceIn(0f, 1f)
        when (edge) {
            TransitionEdge.AOD_TO_LOCK -> {
                val mask = curve(MASK, p, direct)
                val spring = curve(alphaSpring600, p, direct).coerceIn(0f, 1f)
                val wallpaper = curve(wallpaperSpring600, p, direct)
                out.aodAlpha = 1f - mask
                out.lockAlpha = spring
                out.lockScale = 0.90f + 0.10f * spring
                out.wallpaperScale = lerp(WALLPAPER_SCALE_AOD, WALLPAPER_SCALE_LOCK, wallpaper)
                out.dim = lerp(DIM_AOD, DIM_LOCK, mask)
            }

            TransitionEdge.LOCK_TO_AOD -> {
                val mask = curve(MASK, p, direct)
                val exit = curve(MASK, exitProgress(p, LOCK_EXIT_MS, LOCK_TO_AOD_MS, direct), direct)
                val wallpaper = curve(wallpaperSpring500, p, direct)
                out.lockAlpha = 1f - exit
                out.aodAlpha = mask
                out.aodClockScale = 0.90f + 0.10f * curve(AOD_CLOCK, p, direct).coerceIn(0f, 1f)
                out.wallpaperScale = lerp(WALLPAPER_SCALE_LOCK, WALLPAPER_SCALE_AOD, wallpaper)
                out.dim = lerp(DIM_LOCK, DIM_AOD, mask)
            }

            TransitionEdge.LOCK_TO_HOME -> {
                val exit = curve(MASK, exitProgress(p, LOCK_EXIT_MS, LOCK_TO_HOME_MS, direct), direct)
                val spring = curve(alphaSpring800, p, direct).coerceIn(0f, 1f)
                val wallpaper = curve(wallpaperSpring800, p, direct)
                out.lockAlpha = 1f - exit
                out.lockScale = 1f + 0.04f * exit
                out.homeAlpha = spring
                out.homeScale = 0.96f + 0.04f * spring
                out.wallpaperScale = lerp(WALLPAPER_SCALE_LOCK, WALLPAPER_SCALE_HOME, wallpaper)
                out.dim = lerp(DIM_LOCK, DIM_HOME, wallpaper)
            }

            TransitionEdge.HOME_TO_LOCK -> {
                val exit = curve(MASK, exitProgress(p, LOCK_EXIT_MS, HOME_TO_LOCK_MS, direct), direct)
                val spring = curve(alphaSpring600, p, direct).coerceIn(0f, 1f)
                val wallpaper = curve(wallpaperSpring600, p, direct)
                out.homeAlpha = 1f - exit
                out.lockAlpha = spring
                out.lockScale = 0.90f + 0.10f * spring
                out.wallpaperScale = lerp(WALLPAPER_SCALE_HOME, WALLPAPER_SCALE_LOCK, wallpaper)
                out.dim = lerp(DIM_HOME, DIM_LOCK, wallpaper)
            }

            TransitionEdge.AOD_TO_HOME -> {
                val mask = curve(MASK, p, direct)
                val spring = curve(alphaSpring700, p, direct).coerceIn(0f, 1f)
                out.aodAlpha = 1f - mask
                out.homeAlpha = spring
                out.homeScale = 0.96f + 0.04f * spring
                out.wallpaperScale = WALLPAPER_SCALE_AOD
                out.dim = lerp(DIM_AOD, DIM_HOME, mask)
            }

            TransitionEdge.HOME_TO_AOD -> {
                val mask = curve(MASK, p, direct)
                out.homeAlpha = 1f - mask
                out.aodAlpha = mask
                out.aodClockScale = 0.90f + 0.10f * curve(AOD_CLOCK, p, direct).coerceIn(0f, 1f)
                out.wallpaperScale = WALLPAPER_SCALE_AOD
                out.dim = lerp(DIM_HOME, DIM_AOD, mask)
            }
        }
    }

    /** 整轨拖动：u=0 息屏 / 0.5 锁屏 / 1 桌面，各层线性跟随手指。 */
    fun sampleTimeline(u: Float, out: Layers) {
        val q = u.coerceIn(0f, 1f)
        sample(SceneTimeline.edgeAt(q), SceneTimeline.localT(q), out, direct = true)
    }

    /** 短时段在整段里的归一化终点，用于退场类动画先走完。 */
    private fun exitProgress(p: Float, exitMs: Float, totalMs: Long, direct: Boolean): Float =
        (p / (exitMs / totalMs.toFloat())).coerceIn(0f, 1f)

    private fun curve(easing: Easing, p: Float, direct: Boolean): Float =
        if (direct) p else easing.map(p)

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
