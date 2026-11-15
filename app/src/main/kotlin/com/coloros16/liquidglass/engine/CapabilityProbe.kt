package com.coloros16.liquidglass.engine

/**
 * 能力探测：按需自检 + 结果缓存，命中缓存不重复探测。
 * 不轮询、不起线程；结果过期只在下一次 [check] 时才重算。
 */
class CapabilityProbe(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {

    fun interface Probe {
        fun run(): CapabilityResult
    }

    private class Entry(
        val id: String,
        val probe: Probe,
        val okTtlMs: Long,
        val failTtlMs: Long,
    )

    private class Cached(
        val report: CapabilityReport,
        val atMs: Long,
        val ttlMs: Long,
    )

    private val entries = LinkedHashMap<String, Entry>()
    private val cache = HashMap<String, Cached>()

    /** 登记一个探测项；同 id 重复登记以最后一次为准。 */
    @Synchronized
    fun register(
        id: String,
        okTtlMs: Long = DEFAULT_OK_TTL_MS,
        failTtlMs: Long = DEFAULT_FAIL_TTL_MS,
        probe: Probe,
    ) {
        entries[id] = Entry(id, probe, okTtlMs, failTtlMs)
        cache.remove(id)
    }

    /** 取能力结果：缓存有效直接用，否则跑一次探测。 */
    @Synchronized
    fun check(id: String): CapabilityReport {
        val entry = entries[id]
            ?: return CapabilityReport(id, CapabilityState.UNSUPPORTED, REASON_NOT_REGISTERED, null)
        val cached = cache[id]
        val now = nowMs()
        if (cached != null && now - cached.atMs < cached.ttlMs) return cached.report

        val result = try {
            entry.probe.run()
        } catch (t: Throwable) {
            CapabilityResult(CapabilityState.DEGRADED, REASON_PROBE_ERROR, null)
        }
        val report = CapabilityReport(id, result.state, result.reasonCode, result.textKey)
        val ttl = if (result.state == CapabilityState.OK) entry.okTtlMs else entry.failTtlMs
        cache[id] = Cached(report, now, ttl)
        return report
    }

    /** 可用性判定：降配仍算可用，仅不可用返回 false。 */
    fun isUsable(id: String): Boolean = check(id).state != CapabilityState.UNSUPPORTED

    /** 只读已缓存结果，不触发探测。 */
    @Synchronized
    fun cachedReports(): List<CapabilityReport> = cache.values.map { it.report }

    /** 全量探测一次（仅入口处调用，不在热路径）。 */
    @Synchronized
    fun checkAll(): List<CapabilityReport> = entries.keys.map { check(it) }

    /** 失效缓存：传 id 只清该项，传 null 清全部。 */
    @Synchronized
    fun invalidate(id: String? = null) {
        if (id == null) cache.clear() else cache.remove(id)
    }

    companion object {
        const val DEFAULT_OK_TTL_MS = 60_000L
        const val DEFAULT_FAIL_TTL_MS = 3_000L
        const val REASON_NOT_REGISTERED = "not_registered"
        const val REASON_PROBE_ERROR = "probe_error"
    }
}
