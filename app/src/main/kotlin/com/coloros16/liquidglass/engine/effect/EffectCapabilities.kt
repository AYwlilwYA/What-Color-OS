package com.coloros16.liquidglass.engine.effect

/**
 * 设备能力快照：效果门槛的三项能力输入，由调用方一次探测后复用。
 *
 * 独立成类而不是给 [EffectResolver.resolve] 留默认参数：
 * 带默认值的能力参让调用方漏传也能编译过，而漏传等于宣称「设备没这能力」。
 */
data class EffectCapabilities(
    val hasGyro: Boolean,
    val panoramaAodActive: Boolean,
    val photoSceneAvailable: Boolean,
) {
    companion object {
        /** 三项能力全不可用；只作显式降级值，不作隐式缺省。 */
        val NONE = EffectCapabilities(hasGyro = false, panoramaAodActive = false, photoSceneAvailable = false)
    }
}

/**
 * 全景息屏门槛判定：当前息屏样式属全景家族即为真。
 *
 * 家族归属走 [StyleFamilyMap]（引擎内唯一一份样式→家族表），不另写字面量；
 * 三侧（hook / 设置页 / 预览）共用这一份，避免各写一遍导致口径漂移。
 */
fun panoramaAodActiveOf(aodStyleId: String?): Boolean =
    StyleFamilyMap.familyOf(aodStyleId) == EffectFamily.PANORAMA
