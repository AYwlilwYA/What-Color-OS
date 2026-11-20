package com.coloros16.liquidglass.engine

/**
 * 过渡帧循环单飞登记（纯 JVM）：同一时刻只允许一个 morph 帧循环，
 * 新过渡取代旧循环（返回被取代的旧 id 供调用方 removeCallbacks），防多循环争写 morphU 抖动。
 */
class SeedlingTransitionGate {

    private var activeToken: Long = 0L
    private var nextToken: Long = 0L

    /** 当前是否有活跃过渡。 */
    fun isActive(): Boolean = activeToken != 0L

    /** 当前活跃过渡 id（0=无）。 */
    fun activeId(): Long = activeToken

    /**
     * 登记新过渡：返回被取代的旧 id（0=无旧循环）；新 id 单调递增。
     * 调用方拿到旧 id 后 removeCallbacks(旧帧循环)，再 post 新帧循环。
     */
    fun begin(): Long {
        val prev = activeToken
        nextToken += 1L
        activeToken = nextToken
        return prev
    }

    /** 过渡完成/撤窗清场：仅当 token 仍是当前活跃 id 才清（防旧循环完成回调误清新循环）。 */
    fun complete(token: Long): Boolean {
        if (activeToken != token) return false
        activeToken = 0L
        return true
    }

    /** 强制清场（撤窗/热重载）。 */
    fun reset() {
        activeToken = 0L
    }
}
