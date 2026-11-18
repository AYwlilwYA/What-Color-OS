package com.coloros16.liquidglass.engine

/**
 * 分键 TTL 缓存的到期判定：每个键各自计时，互不刷新。
 *
 * 纯求值，时间由调用方传入；存取与读键由调用方负责。
 */
object KeyedTtlCache {

    /** 该键是否仍新鲜；无条目一律视为过期。 */
    fun fresh(readAtMs: Long?, nowMs: Long, ttlMs: Long): Boolean {
        if (readAtMs == null) return false
        if (ttlMs <= 0L) return false
        return nowMs - readAtMs < ttlMs
    }

    /**
     * 共享时间戳是否会让别的键被误判为新鲜。
     *
     * 传该键自己的读取时刻与全局最后一次读取时刻，两者判定不一致即为误判。
     */
    fun sharedStampMisjudges(
        ownReadAtMs: Long?,
        sharedReadAtMs: Long?,
        nowMs: Long,
        ttlMs: Long,
    ): Boolean = fresh(ownReadAtMs, nowMs, ttlMs) != fresh(sharedReadAtMs, nowMs, ttlMs)
}