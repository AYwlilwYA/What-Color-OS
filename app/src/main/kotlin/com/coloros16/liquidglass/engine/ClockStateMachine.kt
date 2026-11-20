package com.coloros16.liquidglass.engine

/**
 * 时钟锚点：某一态下时钟应在的位置与形态。
 * 坐标用屏幕归一化值（0..1），中心点定位，方便预览与真机共用同一份数据。
 */
data class ClockAnchor(
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val scale: Float = 1f,
    val alpha: Float = 1f,
    val rotationDeg: Float = 0f,
    val stretchX: Float = 1f,
    val stretchY: Float = 1f,
)

/** 时钟当前形态；复用实例，逐帧不分配。 */
class ClockPose {
    var centerX: Float = 0.5f
    var centerY: Float = 0.5f
    var scale: Float = 1f
    var alpha: Float = 1f
    var rotationDeg: Float = 0f
    var stretchX: Float = 1f
    var stretchY: Float = 1f

    fun set(anchor: ClockAnchor) {
        centerX = anchor.centerX
        centerY = anchor.centerY
        scale = anchor.scale
        alpha = anchor.alpha
        rotationDeg = anchor.rotationDeg
        stretchX = anchor.stretchX
        stretchY = anchor.stretchY
    }

    fun copyFrom(other: ClockPose) {
        centerX = other.centerX
        centerY = other.centerY
        scale = other.scale
        alpha = other.alpha
        rotationDeg = other.rotationDeg
        stretchX = other.stretchX
        stretchY = other.stretchY
    }

    /** 可见性只由 alpha 推导，避免出现「透明但占位」的中间态。 */
    val visible: Boolean get() = alpha > 0.001f
}

/**
 * 时钟独立状态机：三态各有锚点，状态切换走补间；
 * 系统转场有进度时按 [onTransition] 的 t 直接插值，不叠加二次动画。
 * 布局/配置变化只改锚点，当前态会自动从「当前形态」补间到新锚点，不跳变。
 */
class ClockStateMachine(
    private val tuning: Tuning = Tuning(),
    private val easing: Easing = Easing.STANDARD,
) {

    class Tuning(
        val tweenMs: Long = 320L,
        val followMs: Long = 180L,
    )

    val pose: ClockPose = ClockPose()
    private val anchors = HashMap<SceneState, ClockAnchor>()
    private val written = HashSet<SceneState>()
    private val start = ClockPose()
    private var target: ClockAnchor? = null
    private var tweenStartMs: Long = 0L
    private var tweenDurationMs: Long = 0L

    var currentState: SceneState? = null
        private set
    var transitionEdge: TransitionEdge? = null
        private set

    init {
        anchors[SceneState.AOD] = ClockAnchor()
        anchors[SceneState.LOCK] = ClockAnchor()
        // 桌面默认隐藏；有真机终点时由调用方 setAnchor 覆盖为控件或状态栏位置
        anchors[SceneState.HOME] = ClockAnchorMapper.HOME_FALLBACK
    }

    fun anchorOf(state: SceneState): ClockAnchor = anchors[state] ?: ClockAnchor()

    /** 该态锚点是否被调用方写过；构造期的缺省位不算，[anchorOf] 那时返回的不是真值。 */
    fun hasAnchor(state: SceneState): Boolean = state in written

    fun setAnchor(state: SceneState, anchor: ClockAnchor, nowMs: Long, follow: Boolean = true) {
        written.add(state)
        anchors[state] = anchor
        if (follow && transitionEdge == null && currentState == state) {
            startTween(anchor, nowMs, tuning.followMs)
        }
    }

    /** 初始定位：不播放补间。 */
    fun snapTo(state: SceneState, nowMs: Long) {
        currentState = state
        transitionEdge = null
        target = null
        pose.set(anchorOf(state))
        tweenStartMs = nowMs
        tweenDurationMs = 0L
    }

    /** 普通状态切换：从当前形态补间到目标态锚点。 */
    fun setState(state: SceneState, nowMs: Long, animate: Boolean = true) {
        currentState = state
        transitionEdge = null
        val anchor = anchorOf(state)
        if (animate) {
            startTween(anchor, nowMs, tuning.tweenMs)
        } else {
            target = null
            pose.set(anchor)
        }
    }

    /** 有系统进度时：直接按边两端锚点插值，t 由调用方给（系统泵或内部时间轴）。 */
    fun onTransition(edge: TransitionEdge, t: Float) {
        onTransition(edge, t, easing)
    }

    /** 指定曲线求值：播放时用各边系统曲线，拖动时传 [Easing.LINEAR]。 */
    fun onTransition(edge: TransitionEdge, t: Float, curve: Easing) {
        transitionEdge = edge
        target = null
        val from = anchorOf(edge.from)
        val to = anchorOf(edge.to)
        val e = curve.map(t.coerceIn(0f, 1f))
        pose.centerX = lerp(from.centerX, to.centerX, e)
        pose.centerY = lerp(from.centerY, to.centerY, e)
        pose.scale = lerp(from.scale, to.scale, e)
        pose.alpha = lerp(from.alpha, to.alpha, e)
        pose.rotationDeg = lerp(from.rotationDeg, to.rotationDeg, e)
        pose.stretchX = lerp(from.stretchX, to.stretchX, e)
        pose.stretchY = lerp(from.stretchY, to.stretchY, e)
    }

    /** 整轨直控：u=0 息屏 / 0.5 锁屏 / 1 桌面，不走缓动，拖动时与手指 1:1。 */
    fun onTimeline(u: Float) {
        transitionEdge = null
        target = null
        val q = u.coerceIn(0f, 1f)
        if (q <= 0.5f) {
            blend(anchorOf(SceneState.AOD), anchorOf(SceneState.LOCK), q / 0.5f)
        } else {
            blend(anchorOf(SceneState.LOCK), anchorOf(SceneState.HOME), (q - 0.5f) / 0.5f)
        }
    }

    private fun blend(from: ClockAnchor, to: ClockAnchor, f: Float) {
        pose.centerX = lerp(from.centerX, to.centerX, f)
        pose.centerY = lerp(from.centerY, to.centerY, f)
        pose.scale = lerp(from.scale, to.scale, f)
        pose.alpha = lerp(from.alpha, to.alpha, f)
        pose.rotationDeg = lerp(from.rotationDeg, to.rotationDeg, f)
        pose.stretchX = lerp(from.stretchX, to.stretchX, f)
        pose.stretchY = lerp(from.stretchY, to.stretchY, f)
    }

    /** 转场结束：若当前态与进度终点不一致，补间收尾到当前态锚点。 */
    fun endTransition(nowMs: Long, animate: Boolean = true) {
        transitionEdge = null
        val state = currentState ?: return
        if (animate) startTween(anchorOf(state), nowMs, tuning.tweenMs) else pose.set(anchorOf(state))
    }

    /** 每帧推进；返回同一份 [pose]。 */
    fun tick(nowMs: Long): ClockPose {
        val t = target ?: return pose
        if (tweenDurationMs <= 0L) {
            pose.set(t)
            target = null
            return pose
        }
        val raw = ((nowMs - tweenStartMs).toFloat() / tweenDurationMs.toFloat()).coerceIn(0f, 1f)
        val e = easing.map(raw)
        pose.centerX = lerp(start.centerX, t.centerX, e)
        pose.centerY = lerp(start.centerY, t.centerY, e)
        pose.scale = lerp(start.scale, t.scale, e)
        pose.alpha = lerp(start.alpha, t.alpha, e)
        pose.rotationDeg = lerp(start.rotationDeg, t.rotationDeg, e)
        pose.stretchX = lerp(start.stretchX, t.stretchX, e)
        pose.stretchY = lerp(start.stretchY, t.stretchY, e)
        if (raw >= 1f) target = null
        return pose
    }

    val animating: Boolean get() = target != null

    fun cancel(nowMs: Long) {
        target = null
        tweenStartMs = nowMs
        tweenDurationMs = 0L
    }

    private fun startTween(anchor: ClockAnchor, nowMs: Long, durationMs: Long) {
        start.copyFrom(pose)
        target = anchor
        tweenStartMs = nowMs
        tweenDurationMs = durationMs
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
