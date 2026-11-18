package com.coloros16.liquidglass.engine

/**
 * 素材通道失败账判定：失败只短路同一代接口，换代必须重试。
 *
 * 热重载会换框架接口与模块 APK 路径，旧失败账若连坐新一代，
 * 会让素材通道在整个进程生命周期内永久不可用。
 */
object AssetRetryPolicy {

    /** 是否该尝试构建：没失败过一律试；失败过只在接口换代时重试。 */
    fun shouldAttempt(failed: Boolean, sameApi: Boolean): Boolean = !failed || !sameApi

    /** 本次构建后的失败账状态。 */
    fun failureAfter(success: Boolean): Boolean = !success
}