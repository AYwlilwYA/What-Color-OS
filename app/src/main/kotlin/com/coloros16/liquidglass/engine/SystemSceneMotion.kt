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

    /** 锁屏内容退场时长：中高档动画（anim_level <= 2）。 */
    const val LOCK_EXIT_MS = 200f

    /** 锁屏内容退场时长：低档动画。 */
    const val LOCK_EXIT_LOW_MS = 300f

    const val AOD_CLOCK_IN_MS = 1000f

    /** 息屏时钟进场起始缩放。 */
    const val AOD_CLOCK_IN_SCALE = 0.85f

    /** 锁屏内容缩放区间：入场自下而上收到原样，灭屏反向退回。 */
    const val LOCK_SCALE_IN = 0.90f
    const val LOCK_SCALE_OUT = 1.0f

    /** 全景款解锁：锁屏内容自放大态收回。 */
    const val LOCK_SCALE_PANORAMIC_FROM = 1.1f
    const val LOCK_SCALE_PANORAMIC_TO = 0.95f

    /** 桌面内容入场缩放起点。 */
    const val HOME_SCALE_IN = 0.96f

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

    /** 壁纸缩放曲线：贝塞尔先缓后急再缓。 */
    val WALLPAPER = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

    /** 内容层透明度曲线：贝塞尔减速入场。 */
    private val CONTENT_IN = CubicBezierEasing(0.3f, 0.1f, 0.3f, 1f)

    /** 壁纸缩放曲线：贝塞尔，与内容层分离。 */
    private val WALLPAPER_MOVE = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

    /** 分层输出包；复用实例，逐帧不分配。 */
    class Layers {
        var wallpaperScale = WALLPAPER_SCALE_AOD
        var wallpaperRotationDeg = 0f
        var aodAlpha = 1f
        var aodClockScale = 1f
        var lockAlpha = 0f
        var lockScale = 1f
        var homeAlpha = 0f
        var homeScale = 1f
        var dim = DIM_AOD

        fun reset() {
            wallpaperScale = WALLPAPER_SCALE_AOD
            wallpaperRotationDeg = 0f
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

    fun sample(
        edge: TransitionEdge,
        t: Float,
        out: Layers,
        direct: Boolean = false,
        edgeMs: Long = durationOf(edge),
    ) {
        out.reset()
        val p = t.coerceIn(0f, 1f)
        when (edge) {
            TransitionEdge.AOD_TO_LOCK -> {
                val mask = curve(MASK, p, direct)
                val spring = curve(CONTENT_IN, p, direct).coerceIn(0f, 1f)
                val wallpaper = curve(WALLPAPER_MOVE, p, direct)
                out.aodAlpha = 1f - mask
                out.lockAlpha = spring
                out.lockScale = lerp(LOCK_SCALE_IN, LOCK_SCALE_OUT, spring)
                out.wallpaperScale = lerp(WALLPAPER_SCALE_AOD, WALLPAPER_SCALE_LOCK, wallpaper)
                out.dim = lerp(DIM_AOD, DIM_LOCK, mask)
            }

            TransitionEdge.LOCK_TO_AOD -> {
                val mask = curve(MASK, p, direct)
                val exit = curve(MASK, exitProgress(p, LOCK_EXIT_MS, edgeMs, direct), direct)
                val wallpaper = curve(WALLPAPER_MOVE, p, direct)
                out.lockAlpha = 1f - exit
                out.lockScale = lerp(LOCK_SCALE_OUT, LOCK_SCALE_IN, exit)
                out.aodAlpha = mask
                out.aodClockScale = AodClockEntry.scaleAt(
                    if (direct) p else AodClockEntry.progress(p, edgeMs),
                )
                out.wallpaperScale = lerp(WALLPAPER_SCALE_LOCK, WALLPAPER_SCALE_AOD, wallpaper)
                out.dim = lerp(DIM_LOCK, DIM_AOD, mask)
            }

            TransitionEdge.LOCK_TO_HOME -> {
                val exit = curve(MASK, exitProgress(p, LOCK_EXIT_MS, edgeMs, direct), direct)
                val spring = curve(CONTENT_IN, p, direct).coerceIn(0f, 1f)
                out.lockAlpha = 1f - exit
                out.lockScale = lerp(LOCK_SCALE_PANORAMIC_FROM, LOCK_SCALE_PANORAMIC_TO, exit)
                out.homeAlpha = spring
                out.homeScale = lerp(HOME_SCALE_IN, 1f, spring)
                out.wallpaperScale = WALLPAPER_SCALE_LOCK
                out.dim = lerp(DIM_LOCK, DIM_HOME, spring)
            }

            TransitionEdge.HOME_TO_LOCK -> {
                val exit = curve(MASK, exitProgress(p, LOCK_EXIT_MS, edgeMs, direct), direct)
                val spring = curve(CONTENT_IN, p, direct).coerceIn(0f, 1f)
                out.homeAlpha = 1f - exit
                out.homeScale = lerp(1f, HOME_SCALE_IN, exit)
                out.lockAlpha = spring
                out.lockScale = lerp(LOCK_SCALE_IN, LOCK_SCALE_OUT, spring)
                out.wallpaperScale = WALLPAPER_SCALE_LOCK
                out.dim = lerp(DIM_HOME, DIM_LOCK, spring)
            }

            TransitionEdge.AOD_TO_HOME -> {
                val mask = curve(MASK, p, direct)
                val spring = curve(CONTENT_IN, p, direct).coerceIn(0f, 1f)
                out.aodAlpha = 1f - mask
                out.homeAlpha = spring
                out.homeScale = lerp(HOME_SCALE_IN, 1f, spring)
                out.wallpaperScale = WALLPAPER_SCALE_AOD
                out.dim = lerp(DIM_AOD, DIM_HOME, mask)
            }

            TransitionEdge.HOME_TO_AOD -> {
                val mask = curve(MASK, p, direct)
                out.homeAlpha = 1f - mask
                out.homeScale = lerp(1f, HOME_SCALE_IN, mask)
                out.aodAlpha = mask
                out.aodClockScale = AodClockEntry.scaleAt(
                    if (direct) p else AodClockEntry.progress(p, edgeMs),
                )
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

    /** 内容元素错峰窗口数：天气行 / 通知卡 / 图标三行 / Dock。 */
    const val CONTENT_STAGGER_COUNT = 6

    private val STAGGER = CubicBezierEasing(0.22f, 0.61f, 0.36f, 1f)

    /** 单个内容元素的滑动进度与纵向位移（屏高归一化）；复用实例，逐帧不分配。 */
    class ContentPose {
        var progress = 0f
        var shift = 0f
    }

    /**
     * 内容元素错峰滑动：按层进度在时间窗内逐元素求值。
     * 入场自下而上、退场向上让位；进度 0=完全让位、1=落位，不做整层溶解。
     *
     * [index] 是元素序号，[count] 是该层元素总数；序号按比例折进六档窗口
     * （`index * 6 / count`），元素少于六时按原序号。
     */
    fun contentStagger(
        layerProgress: Float,
        index: Int,
        entering: Boolean,
        travel: Float,
        out: ContentPose,
        direct: Boolean = false,
        count: Int = CONTENT_STAGGER_COUNT,
    ) {
        val n = CONTENT_STAGGER_COUNT
        val total = count.coerceAtLeast(1)
        val slot = if (total <= n) index else index * n / total
        val i = slot.coerceIn(0, n - 1)
        val span = 0.62f
        val step = (1f - span) / (n - 1).toFloat()
        val order = if (entering) i else n - 1 - i
        val start = order * step
        val local = ((layerProgress - start) / span).coerceIn(0f, 1f)
        val e = if (direct) local else STAGGER.map(local)
        out.progress = e
        out.shift = if (entering) travel * (1f - e) else -travel * e
    }

    /** 短时段在整段里的归一化终点，用于退场类动画先走完。 */
    private fun exitProgress(p: Float, exitMs: Float, totalMs: Long, direct: Boolean): Float =
        (p / (exitMs / totalMs.toFloat())).coerceIn(0f, 1f)

    private fun curve(easing: Easing, p: Float, direct: Boolean): Float =
        if (direct) p else easing.map(p)

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
