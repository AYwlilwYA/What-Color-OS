package com.coloros16.liquidglass.engine

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 平台状态汇聚：对外只给「读快照」与「订阅变化」两条路，变化才推，不做轮询。
 * revision 只随结构性变化递增（边、来源、播放态、能力、熔断）；逐帧 t 走 [clock] 自己的 revision。
 */
class PlatformStateHub(
    val clock: TransitionClock = TransitionClock(),
    val capabilities: CapabilityProbe = CapabilityProbe(),
    val fuse: SafetyFuse = SafetyFuse(),
) {

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    private var revision: Long = 0L

    /** 跨进程变化出口（如 ContentResolver.notifyChange），可为空；只在结构性变化时调用。 */
    @Volatile
    var changeSignal: (() -> Unit)? = null

    private var lastEdge: TransitionEdge? = null
    private var lastSource: ClockSource = ClockSource.MANUAL
    private var lastPlaying: Boolean = false

    private val clockSubscription: () -> Unit = clock.subscribe { edge, source, _, playing, _ ->
        if (edge != lastEdge || source != lastSource || playing != lastPlaying) {
            lastEdge = edge
            lastSource = source
            lastPlaying = playing
            notifyChanged()
        }
    }

    fun snapshot(): PlatformSnapshot = PlatformSnapshot(
        revision = revision,
        clock = clock.state(),
        capabilities = capabilities.cachedReports(),
        fuses = fuse.reports(),
    )

    /** 订阅结构性变化；回调在触发线程执行，实现方需自行转投主线程。 */
    fun subscribe(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    /** 能力/熔断等外部状态变更后手动推一次。 */
    fun notifyChanged() {
        revision++
        for (listener in listeners) {
            listener()
        }
        changeSignal?.invoke()
    }

    fun close() {
        clockSubscription()
        listeners.clear()
    }
}
