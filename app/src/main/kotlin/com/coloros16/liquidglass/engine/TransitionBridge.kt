package com.coloros16.liquidglass.engine

/**
 * 转场桥：把进度源（系统泵 / 内部时间轴 / 预览 / 手动）接到同一个求值器。
 * 消费方只需「喂进度」或「推进时间」，再调 [evaluateNow] 取参数包；自身不起线程。
 */
class TransitionBridge(
    private val screen: ScreenMetrics,
    val clock: TransitionClock = TransitionClock(),
    val evaluator: TransitionEvaluator = TransitionEvaluator(),
) {

    val params: TransitionParams = TransitionParams()
    val sceneSample: SceneSample = SceneSample()
    val segmented: SegmentedProgress = SegmentedProgress()
    private val segmentSample = SegmentSample()

    var srcCapabilities: StyleCapabilities = StyleCapabilities()
    var dstCapabilities: StyleCapabilities = StyleCapabilities()

    /** L1 需要可复用的采样缓冲；关闭时省掉元素遍历。 */
    var sceneSamplingEnabled: Boolean = true

    fun setCapabilities(src: StyleCapabilities, dst: StyleCapabilities) {
        srcCapabilities = src
        dstCapabilities = dst
    }

    /** 系统进度泵写入（息屏↔锁屏等有原生进度的边）。 */
    fun onSystemProgress(edge: TransitionEdge, t: Float) {
        clock.setT(edge, t, ClockSource.SYSTEM_PUMP)
    }

    /** 宿主只给分段进度时：把直连边的总进度拆成两段，供只认识分段边的渲染方使用。 */
    fun onSegmentedProgress(edge: TransitionEdge, t: Float): SegmentSample =
        segmented.sample(edge, t, segmentSample)

    private var lastAdvanceAtMs: Long = 0L

    /** 无原生进度的边：起播内部时间轴，由调用方按帧 [advance]。 */
    fun startSelfTimed(edge: TransitionEdge, durationMs: Long, nowMs: Long) {
        clock.play(edge, durationMs, 0f, ClockSource.INTERNAL_TIMELINE)
        lastAdvanceAtMs = nowMs
    }

    /** 推进内部时间轴；返回是否仍在播放。 */
    fun advance(nowMs: Long): Boolean {
        lastAdvanceAtMs = nowMs
        return clock.advance(nowMs)
    }

    fun lastAdvanceAt(): Long = lastAdvanceAtMs

    /** 按当前进度求值，返回同一份参数包。 */
    fun evaluateNow(): TransitionParams {
        val state = clock.state()
        val edge = state.edge
        if (edge == null) {
            params.reset()
            return params
        }
        evaluator.evaluate(
            edge = edge,
            t = state.t,
            screen = screen,
            srcCapabilities = srcCapabilities,
            dstCapabilities = dstCapabilities,
            out = params,
            sceneOut = if (sceneSamplingEnabled) sceneSample else null,
        )
        return params
    }

    fun reset() {
        clock.reset()
        params.reset()
        sceneSample.reset()
    }
}
