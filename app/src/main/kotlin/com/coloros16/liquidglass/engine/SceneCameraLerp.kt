package com.coloros16.liquidglass.engine

/**
 * 场景相机的指数逼近插值：每步按剩余差值的固定比例逼近目标。
 *
 * 与关键帧曲线不同，本模型无固定时长、理论上永不精确到达，靠阈值收敛；
 * 三态切换只换目标值，当前值连续演化，故任意两态互切都不会跳变。
 * 纯 JVM，预览与 hook 共用同一份求值。
 */
object SceneCameraLerp {

    /** 每秒逼近系数：剩余差值按 dt×此值 的比例消去。 */
    const val SPEED = 2.5f

    /** 过渡累计超过此时长即视为收敛，可降帧率（秒）。 */
    const val SETTLE_SECONDS = 1.2f

    /** 帧间隔异常上限（秒）：超过即回落到桌面滑动基准间隔。 */
    const val DT_ABNORMAL_LIMIT = 1f

    /** 帧间隔异常时的回落值（秒）。 */
    const val DT_FALLBACK = 0.0666f

    /** 相机七通道：位置 xyz、注视点 xyz、暗态、旋转量、状态量、目标高度。 */
    class Pose {
        var px = 0f
        var py = 0f
        var pz = 0f
        var lx = 0f
        var ly = 0f
        var lz = 0f
        var dark = 0f
        var rot = 0f
        var state = 0f
        var height = 0f
        var elapsed = 0f

        fun copyFrom(o: Pose) {
            px = o.px; py = o.py; pz = o.pz
            lx = o.lx; ly = o.ly; lz = o.lz
            dark = o.dark; rot = o.rot; state = o.state; height = o.height
            elapsed = o.elapsed
        }
    }

    /** 帧间隔守卫：异常值回落基准，负值归零。 */
    fun guardDt(dt: Float): Float = when {
        dt.isNaN() -> DT_FALLBACK
        dt > DT_ABNORMAL_LIMIT -> DT_FALLBACK
        dt < 0f -> 0f
        else -> dt
    }

    /** 单通道一步逼近。 */
    fun step(current: Float, target: Float, dt: Float): Float =
        current + (target - current) * guardDt(dt) * SPEED

    /**
     * 整位姿一步逼近：七通道各按剩余差值比例演化，累计过渡时长。
     *
     * [snap] 为真时直赋目标并清零累计（强制动画与息屏偏移走此路）。
     */
    fun advance(current: Pose, target: Pose, dt: Float, snap: Boolean = false) {
        if (snap) {
            current.copyFrom(target)
            current.elapsed = 0f
            return
        }
        val d = guardDt(dt)
        current.px = step(current.px, target.px, d)
        current.py = step(current.py, target.py, d)
        current.pz = step(current.pz, target.pz, d)
        current.lx = step(current.lx, target.lx, d)
        current.ly = step(current.ly, target.ly, d)
        current.lz = step(current.lz, target.lz, d)
        current.dark = step(current.dark, target.dark, d)
        current.rot = step(current.rot, target.rot, d)
        current.state = step(current.state, target.state, d)
        current.height = step(current.height, target.height, d)
        current.elapsed += d
    }

    /** 是否已过渡足够久（可降帧率）。 */
    fun settled(current: Pose): Boolean = current.elapsed >= SETTLE_SECONDS

    /**
     * 连续极限下的解析解：`target + (start − target)·e^(−SPEED·t)`。
     *
     * 供契约测核对逐帧迭代与理论值一致，不参与渲染。
     */
    fun analytical(start: Float, target: Float, seconds: Float): Float {
        if (seconds <= 0f) return start
        return target + (start - target) * kotlin.math.exp(-SPEED * seconds)
    }

    /** 剩余差值比例：用于判定「还差多少」而不是「播了百分之几」。 */
    fun remainingRatio(current: Float, target: Float, start: Float): Float {
        val span = start - target
        if (kotlin.math.abs(span) < 1e-6f) return 0f
        return ((current - target) / span).coerceIn(0f, 1f)
    }
}