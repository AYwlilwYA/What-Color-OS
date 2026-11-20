package com.coloros16.liquidglass.engine

import kotlin.math.abs
import kotlin.math.min

/**
 * 启航镜片动效真值：圆形镜片半径、桶形畸变、模糊、装饰层与照片旋转。
 * 预览与 hook 共用同一份参数与曲线，渲染层各自实现。
 */
object SailLensMotion {

    /** 镜片半径（宽高的宽向归一化值）。 */
    const val RADIUS = 0.22f

    /** 桶形畸变强度（未除以半径的原始值）。 */
    const val DISTORTION = 9f

    /** 息屏镜片模糊半径（参考宽度下的像素值）。 */
    const val BLUR = 22f

    /** 装饰层强度。 */
    const val DECOR_ALPHA = 1f

    /** 照片自转角速度：度/秒。 */
    const val SPIN_DEG_PER_SEC = 40f

    /** 展开后装饰层转到的角度；照片角度停在同一圈的等价位置。 */
    const val DECOR_ANGLE_DEG = -149f
    const val LOCK_ANGLE_DEG = -360f
    const val HOME_ANGLE_DEG = -360f

    /** 照片旋转轴距镜片中心的像素偏移（参考宽度 1080）。 */
    const val PIVOT_PX = 200f
    const val REFERENCE_WIDTH = 1080f

    private val EASE = CubicBezierEasing(0.33f, 0f, 0.67f, 1f)
    private val FADE = CubicBezierEasing(0.32f, 0f, 0.68f, 1f)
    private val EXPAND = CubicBezierEasing(0.13f, 0.04f, 0f, 1f)
    private val COLLAPSE = CubicBezierEasing(0.37f, 0.04f, 0.04f, 1f)
    private val DISTORT = CubicBezierEasing(0f, 0.5f, 0f, 1f)
    private val BLUR_OUT = CubicBezierEasing(0.17f, 0f, 0.83f, 1f)
    private val EASE_IN = CubicBezierEasing(0f, 0f, 0f, 1f)
    private val DECOR = CubicBezierEasing(0.37f, 0.02f, 0.04f, 1f)
    private val ROTATE = CubicBezierEasing(0.17f, 0f, 0.67f, 1f)

    /** 未指定通道的缺省曲线：平台先加速后减速。 */
    private val DEFAULT = Easing.ACCELERATE_DECELERATE

    /** 息屏→锁屏整组时长：装饰角轨走缺省 1000ms，比位移轨 950ms 更长。 */
    const val AOD_TO_LOCK_MS = 1000f

    /** 息屏→锁屏的位移/涟漪轨时长，短于整组。 */
    const val AOD_TO_LOCK_MOVE_MS = 950f

    const val AOD_TO_HOME_MS = 1000f
    const val EXIT_MS = 650f

    /** 锁屏→桌面最长轨：模糊慢焦点拉伸，内容层早已落位它还在爬。 */
    const val LOCK_TO_HOME_MS = 3840f

    /** 锁屏→桌面的内容位移轨时长。 */
    const val LOCK_TO_HOME_MOVE_MS = 650f

    /** 锁屏→桌面的装饰角轨时长。 */
    const val LOCK_TO_HOME_ANGLE_MS = 24f

    /** 镜片交叠时长按息屏/锁屏/桌面样式分档取最大值。 */
    fun blendDurationMs(edge: TransitionEdge, aodKind: Int, lockKind: Int): Long {
        val a = when (aodKind) {
            AOD_KIND_LENS -> longArrayOf(950, 650, 300, 650, 650, 300)
            AOD_KIND_FULL -> longArrayOf(250, 1200, 350, 350, 300, 300)
            else -> longArrayOf(400, 400, 400, 400, 400, 400)
        }
        val l = when {
            lockKind == LOCK_KIND_NONE -> longArrayOf(250, 250, 250, 250, 400, 400)
            lockKind >= LOCK_KIND_PLAIN4 -> longArrayOf(250, 250, 250, 250, 500, 500)
            lockKind == LOCK_KIND_GROUND -> longArrayOf(1500, 200, 200, 200, 200, 1500)
            else -> longArrayOf(850, 200, 200, 200, 500, 850)
        }
        val h = longArrayOf(250, 250, 250, 250, 500, 500)
        val tr = transitionOf(edge)
        return when {
            tr < 2 -> maxOf(a[tr], l[tr])
            tr < 4 -> maxOf(a[tr], h[tr])
            else -> maxOf(l[tr], h[tr])
        }
    }

    const val AOD_KIND_LENS = 0
    const val AOD_KIND_FULL = 101
    const val LOCK_KIND_NONE = 0
    const val LOCK_KIND_GLASS = 1
    const val LOCK_KIND_GLASS_ALT = 2
    const val LOCK_KIND_GROUND = 3
    const val LOCK_KIND_PLAIN4 = 4

    /** 边 → 转场编号（与照片类同一套编号）。 */
    fun transitionOf(edge: TransitionEdge): Int = when (edge) {
        TransitionEdge.AOD_TO_LOCK -> 0
        TransitionEdge.LOCK_TO_AOD -> 1
        TransitionEdge.AOD_TO_HOME -> 2
        TransitionEdge.HOME_TO_AOD -> 3
        TransitionEdge.LOCK_TO_HOME -> 4
        TransitionEdge.HOME_TO_LOCK -> 5
    }

    /**
     * 该边用镜片自身时长播放，避免展开到一半被系统时长截断。
     *
     * 时长取该边**最长轨**（原版整组动画时长 = 最长轨），不是系统内容层时长。
     */
    fun durationMs(edge: TransitionEdge): Long = when (edge) {
        TransitionEdge.AOD_TO_LOCK -> AOD_TO_LOCK_MS.toLong()
        TransitionEdge.AOD_TO_HOME -> AOD_TO_HOME_MS.toLong()
        TransitionEdge.LOCK_TO_AOD, TransitionEdge.HOME_TO_AOD -> EXIT_MS.toLong()
        TransitionEdge.LOCK_TO_HOME -> LOCK_TO_HOME_MS.toLong()
        TransitionEdge.HOME_TO_LOCK -> SystemSceneMotion.HOME_TO_LOCK_MS
    }

    /** 交叠真值：息屏层与目标态内容的透明度，播放期按曲线求值。 */
    class Fade {
        var aodAlpha = 1f
        var aodClockAlpha = 1f
        var contentGate = 1f

        fun reset() {
            aodAlpha = 1f
            aodClockAlpha = 1f
            contentGate = 1f
        }
    }

    /** 启航入/出场交叠：息屏层与目标态内容在时间窗内互相溶解，全部走三次贝塞尔。 */
    fun fade(edge: TransitionEdge?, t: Float, out: Fade) {
        out.reset()
        if (edge == null) return
        val p = t.coerceIn(0f, 1f)
        when (edge) {
            TransitionEdge.AOD_TO_LOCK, TransitionEdge.AOD_TO_HOME -> {
                out.aodAlpha = 1f - FADE.map(win(p, 0.55f, 0.90f))
                out.aodClockAlpha = 1f - FADE.map(win(p, 0.55f, 0.90f))
                out.contentGate = FADE.map(win(p, 0.55f, 0.92f))
            }

            TransitionEdge.LOCK_TO_AOD, TransitionEdge.HOME_TO_AOD -> {
                out.aodAlpha = FADE.map(win(p, 0.12f, 0.72f))
                out.aodClockAlpha = FADE.map(win(p, 0.12f, 0.72f))
                out.contentGate = 1f - FADE.map(win(p, 0.02f, 0.45f))
            }

            else -> Unit
        }
    }

    /** 官方镜片取景缩放：只由镜片半径决定，与壁纸缩放无关。 */
    fun lensZoom(scale: Float): Float = 1f / (min(scale, 0.5f).coerceAtLeast(1e-3f) * 4f)

    /** 锁屏↔桌面只做整屏轻微「模糊转清晰」，无位移无缩放。 */
    fun blurClearAlpha(edge: TransitionEdge?, t: Float): Float = when (edge) {
        TransitionEdge.LOCK_TO_HOME -> 1f - FADE.map(win(t, 0.02f, 0.55f))
        TransitionEdge.HOME_TO_LOCK -> FADE.map(win(t, 0.45f, 0.95f))
        else -> 0f
    }

    private fun win(p: Float, from: Float, to: Float): Float =
        ((p - from) / (to - from).coerceAtLeast(1e-4f)).coerceIn(0f, 1f)

    /** 镜片逐帧参数；复用实例，渲染层直接读。 */
    class Pose {
        var scale = RADIUS
        var distortion = DISTORTION
        var blur = BLUR
        var decorAlpha = DECOR_ALPHA
        var centerX = 0.5f
        var centerY = 0.5f
        var angleDeg = 0f
        var decorAngleDeg = 0f
        var expandedScale = LOCK_SCALE

        /** 涟漪强度与延迟门：息屏为 0，展开后为 1。 */
        var ripple = 0f

        /** 照片自转速度门：息屏 1、其余 0。 */
        var spinSpeed = 1f

        fun reset() {
            scale = RADIUS
            distortion = DISTORTION
            blur = BLUR
            decorAlpha = DECOR_ALPHA
            centerX = 0.5f
            centerY = 0.5f
            angleDeg = 0f
            decorAngleDeg = 0f
            ripple = 0f
            spinSpeed = 1f
        }
    }

    private const val LOCK_SCALE = 1.24f

    /** 展开比例：参考实现按高宽比 2.33 取 1.3，其余取 1.24。 */
    fun expandedScale(heightOverWidth: Float): Float =
        if (abs(heightOverWidth - 2.33f) < 0.005f) 1.3f else LOCK_SCALE

    /** 照片自转：返回累计角度（度）；speed=0 时停转（锁屏/桌面不转）。 */
    fun spin(angleDeg: Float, deltaSeconds: Float, speed: Float = 1f): Float {
        if (speed <= 0f) return angleDeg
        val next = angleDeg - deltaSeconds * SPIN_DEG_PER_SEC * speed
        if (next <= -360f) return next + 360f
        return next
    }

    /**
     * 照片自转的绝对角：按单调时钟一次求值，符号与 [spin] 一致（负方向）。
     *
     * 供不能攒帧的调用方使用（注入系统进程禁轮询、热重载会丢累计状态）；
     * 结果归一到 (-360, 0]，与逐帧积分等价。
     */
    fun spinAt(seconds: Float, speed: Float = 1f): Float {
        if (speed <= 0f || seconds <= 0f) return 0f
        val total = seconds * SPIN_DEG_PER_SEC * speed
        val mod = total % 360f
        return if (mod == 0f) 0f else -mod
    }

    /** 官方入场角度：24ms 内转到与当前角最近的 -360 等价位。 */
    fun nearestRotation(current: Float, target: Float): Float {
        val distance = Math.floorMod((target - current).toInt(), 360)
        return target + (if (distance >= 180) 360 - distance else -distance)
    }

    fun sampleStatic(
        state: SceneState,
        aodX: Float,
        aodY: Float,
        expanded: Float,
        out: Pose,
    ) {
        out.reset()
        out.expandedScale = expanded
        out.centerX = aodX
        out.centerY = aodY
        when (state) {
            SceneState.AOD -> Unit

            SceneState.LOCK -> {
                out.scale = expanded
                out.distortion = 0f
                out.blur = 0f
                out.decorAlpha = 0f
                out.centerX = 0.5f
                out.centerY = 0.5f
                out.angleDeg = LOCK_ANGLE_DEG
                out.decorAngleDeg = DECOR_ANGLE_DEG
                out.ripple = 1.24f
                out.spinSpeed = 0f
            }

            SceneState.HOME -> {
                out.scale = expanded
                out.distortion = 0f
                out.blur = 16f
                out.decorAlpha = 0f
                out.centerX = 0.5f
                out.centerY = 0.5f
                out.angleDeg = HOME_ANGLE_DEG
                out.decorAngleDeg = DECOR_ANGLE_DEG
                out.ripple = 1.24f
                out.spinSpeed = 0f
            }
        }
    }

    fun sample(
        edge: TransitionEdge,
        t: Float,
        aodX: Float,
        aodY: Float,
        expanded: Float,
        out: Pose,
        direct: Boolean = false,
        spinDeg: Float = 0f,
    ) {
        val p = t.coerceIn(0f, 1f)
        out.reset()
        out.expandedScale = expanded
        out.centerX = aodX
        out.centerY = aodY
        when (edge) {
            TransitionEdge.AOD_TO_LOCK -> {
                val total = AOD_TO_LOCK_MS
                val moveFraction = AOD_TO_LOCK_MOVE_MS / total
                val move = f(EXPAND, p, moveFraction, direct)
                out.scale = lerp(RADIUS, expanded, move)
                out.centerX = lerp(aodX, 0.5f, move)
                out.centerY = lerp(aodY, 0.5f, move)
                out.distortion = lerp(DISTORTION, 0f, f(DISTORT, p, 800f / total, direct))
                out.blur = lerp(BLUR, 0f, f(EASE, p, 183f / total, direct))
                out.decorAlpha = lerp(DECOR_ALPHA, 0f, f(EASE, p, 33f / total, direct))
                out.decorAngleDeg = lerp(0f, DECOR_ANGLE_DEG, f(DEFAULT, p, 1f, direct))
                out.angleDeg = spinDeg
                out.ripple = lerp(0f, 1.24f, f(EASE, p, moveFraction, direct))
                out.spinSpeed = 1f
            }

            TransitionEdge.AOD_TO_HOME -> {
                val total = AOD_TO_HOME_MS
                val move = f(EASE_IN, p, 1f, direct)
                out.scale = lerp(RADIUS, expanded, move)
                out.centerX = lerp(aodX, 0.5f, move)
                out.centerY = lerp(aodY, 0.5f, move)
                out.distortion = lerp(DISTORTION, 0f, f(DISTORT, p, 900f / total, direct))
                out.blur = lerp(BLUR, 0f, f(EASE, p, 183f / total, direct))
                out.decorAlpha = lerp(DECOR_ALPHA, 0f, f(EASE, p, 33f / total, direct))
                out.decorAngleDeg = lerp(0f, DECOR_ANGLE_DEG, f(DEFAULT, p, 1f, direct))
                out.angleDeg = spinDeg
                out.ripple = lerp(0f, 1.24f, f(EASE, p, 1f, direct))
                out.spinSpeed = 1f
            }

            TransitionEdge.LOCK_TO_AOD -> {
                out.angleDeg = spinDeg
                out.scale = lerp(expanded, RADIUS, f(COLLAPSE, p, 1f, direct))
                out.centerX = lerp(0.5f, aodX, f(COLLAPSE, p, 1f, direct))
                out.centerY = lerp(0.5f, aodY, f(COLLAPSE, p, 1f, direct))
                out.distortion = lerp(0f, DISTORTION, f(EASE, p, 1f, direct))
                out.blur = lerp(0f, BLUR, f(BLUR_OUT, p, 1f, direct))
                out.decorAlpha = lerp(0f, DECOR_ALPHA, f(EASE, p, 1f, direct))
                out.decorAngleDeg = lerp(DECOR_ANGLE_DEG, 0f, f(DECOR, p, 1f, direct))
                out.ripple = lerp(1.24f, 0f, f(EASE, p, 1f, direct, delayFraction = 1f))
                out.spinSpeed = 0f
            }

            TransitionEdge.HOME_TO_AOD -> {
                out.angleDeg = spinDeg
                out.scale = lerp(expanded, RADIUS, f(COLLAPSE, p, 1f, direct))
                out.centerX = lerp(0.5f, aodX, f(COLLAPSE, p, 1f, direct))
                out.centerY = lerp(0.5f, aodY, f(COLLAPSE, p, 1f, direct))
                out.distortion = lerp(0f, DISTORTION, f(EASE, p, 1f, direct))
                out.blur = lerp(16f, BLUR, f(BLUR_OUT, p, 1f, direct))
                out.decorAlpha = lerp(0f, DECOR_ALPHA, f(EASE, p, 1f, direct))
                out.decorAngleDeg = lerp(DECOR_ANGLE_DEG, 0f, f(DECOR, p, 1f, direct))
                out.ripple = lerp(1.24f, 0f, f(EASE, p, 1f, direct, delayFraction = 1f))
                out.spinSpeed = 0f
            }

            TransitionEdge.LOCK_TO_HOME -> {
                val move = f(EXPAND, p, LOCK_TO_HOME_MOVE_MS / LOCK_TO_HOME_MS, direct)
                out.scale = expanded
                out.centerX = 0.5f
                out.centerY = 0.5f
                out.distortion = lerp(0f, 0f, move)
                out.decorAlpha = 0f
                out.blur = lerp(0f, 16f, f(EASE, p, 1f, direct))
                out.angleDeg = spinDeg
                out.decorAngleDeg = DECOR_ANGLE_DEG
                out.ripple = lerp(1.24f, 1.24f, move)
                out.spinSpeed = 0f
            }

            TransitionEdge.HOME_TO_LOCK -> {
                out.scale = expanded
                out.centerX = 0.5f
                out.centerY = 0.5f
                out.distortion = 0f
                out.decorAlpha = 0f
                out.blur = lerp(16f, 0f, f(EASE, p, 0f, direct))
                out.angleDeg = spinDeg
                out.decorAngleDeg = DECOR_ANGLE_DEG
                out.ripple = 1.24f
                out.spinSpeed = 0f
            }
        }
    }

    /** 整轨拖动：u=0 息屏 / 0.5 锁屏 / 1 桌面，镜片按手指位置线性求值。 */
    fun sampleTimeline(u: Float, aodX: Float, aodY: Float, expanded: Float, out: Pose, spinDeg: Float = 0f) {
        val q = u.coerceIn(0f, 1f)
        sample(SceneTimeline.edgeAt(q), SceneTimeline.localT(q), aodX, aodY, expanded, out, direct = true, spinDeg = spinDeg)
    }

    private fun f(
        curve: Easing,
        p: Float,
        fraction: Float,
        direct: Boolean,
        delayFraction: Float = 0f,
    ): Float {
        if (fraction <= 0f) return 1f
        val start = delayFraction.coerceIn(0f, 0.999f)
        val span = (fraction - start).coerceAtLeast(1e-4f)
        val q = ((p - start) / span).coerceIn(0f, 1f)
        return if (direct) q else curve.map(q)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
