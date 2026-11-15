package com.coloros16.liquidglass.engine

/**
 * 安全熔断：按功能域累计窗口内失败次数，超阈值触发一次回原厂回调。
 * 计数由调用方在失败点上报，不轮询、不起定时器；窗口靠上报时的时间戳剔除。
 */
class SafetyFuse(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {

    data class Policy(
        val threshold: Int = 3,
        val windowMs: Long = 10 * 60_000L,
    )

    private class Domain(
        val policy: Policy,
        val onTrip: (FuseReport) -> Unit,
    ) {
        val stamps = ArrayDeque<Long>()
        var tripped: Boolean = false
        var lastFailureAtMs: Long = 0L
        var lastRollbackAtMs: Long = 0L
        var reason: String? = null
    }

    private val domains = LinkedHashMap<String, Domain>()

    @Synchronized
    fun register(domain: String, policy: Policy = Policy(), onTrip: (FuseReport) -> Unit) {
        domains[domain] = Domain(policy, onTrip)
    }

    /**
     * 上报一次失败；达到阈值时返回 true 并触发回原厂回调。
     * 已熔断的域不重复回调。
     */
    fun recordFailure(domain: String, reason: String? = null): Boolean {
        val report: FuseReport
        val callback: ((FuseReport) -> Unit)?
        synchronized(this) {
            val d = domains[domain] ?: return false
            val now = nowMs()
            d.lastFailureAtMs = now
            d.reason = reason
            prune(d, now)
            if (d.tripped) return false
            d.stamps.addLast(now)
            if (d.stamps.size < d.policy.threshold.coerceAtLeast(1)) return false
            d.tripped = true
            d.lastRollbackAtMs = now
            d.stamps.clear()
            report = snapshot(domain, d)
            callback = d.onTrip
        }
        callback?.invoke(report)
        return true
    }

    /** 上报一次成功：清空窗口内失败计数（已熔断状态不变，需显式 [reset]）。 */
    @Synchronized
    fun recordSuccess(domain: String) {
        domains[domain]?.stamps?.clear()
    }

    /** 人工重置：解除熔断并清计数。 */
    @Synchronized
    fun reset(domain: String) {
        val d = domains[domain] ?: return
        d.tripped = false
        d.stamps.clear()
        d.reason = null
    }

    @Synchronized
    fun isTripped(domain: String): Boolean = domains[domain]?.tripped == true

    @Synchronized
    fun report(domain: String): FuseReport? = domains[domain]?.let { snapshot(domain, it) }

    @Synchronized
    fun reports(): List<FuseReport> = domains.map { (name, d) -> snapshot(name, d) }

    private fun prune(d: Domain, now: Long) {
        val window = d.policy.windowMs.coerceAtLeast(0L)
        while (d.stamps.isNotEmpty() && now - d.stamps.first() > window) {
            d.stamps.removeFirst()
        }
    }

    private fun snapshot(domain: String, d: Domain): FuseReport = FuseReport(
        domain = domain,
        state = if (d.tripped) FuseState.TRIPPED else FuseState.CLOSED,
        failures = d.stamps.size,
        lastFailureAtMs = d.lastFailureAtMs,
        lastRollbackAtMs = d.lastRollbackAtMs,
        reason = d.reason,
    )
}
