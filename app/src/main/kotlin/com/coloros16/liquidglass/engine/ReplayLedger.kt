package com.coloros16.liquidglass.engine

/**
 * 转场记账的过期判定：区分「新转场顶掉上一次」与「同一次转场的后续帧」。
 *
 * 拿刚取到的代次去和当前代次自比恒相等，永远判不出过期；
 * 要比的是上一次 start 记下的代次与本次取到的代次。
 */
object ReplayLedger {

    /** 初始哨兵：还没有任何 start 记过账。 */
    const val NO_TOKEN = -1L

    /**
     * 上一次 start 是否已被本次顶掉。
     *
     * 首次（previous 为哨兵）不算顶掉；代次相同说明是同一次转场，也不算。
     */
    fun superseded(previous: Long, current: Long): Boolean =
        previous != NO_TOKEN && previous != current
}