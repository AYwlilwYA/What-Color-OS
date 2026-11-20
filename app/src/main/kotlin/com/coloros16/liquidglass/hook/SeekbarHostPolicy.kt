package com.coloros16.liquidglass.hook

/**
 * 竖向滑杆玻璃的宿主归属判定（纯逻辑，无 Android 依赖）。
 *
 * 父类 `COUIVerticalSeekBar.draw` 的 hook 会命中全部子类，必须挑出目标：
 * - 音量条：恒走自绘腿（玻璃盖轨道后重绘进度）
 * - QS 亮度/音量滑杆：**仅当进度层工厂不可用**时才纳入自绘腿；
 *   工厂可用时它已有 AutoBlurDrawable 进度层走既有漏斗，两条腿同时画会叠双份玻璃。
 */
object SeekbarHostPolicy {

    const val SUFFIX_VOLUME = "OplusVolumeSeekBar"
    const val SUFFIX_QS_VERTICAL = "OplusQsVerticalSeekBar"

    /** 该宿主类是否走自绘玻璃腿。[qsFactoryAvailable] = 进度层工厂挂载成功与否。 */
    fun isSelfDrawHost(className: String, qsFactoryAvailable: Boolean): Boolean {
        if (className.endsWith(SUFFIX_VOLUME)) return true
        return className.endsWith(SUFFIX_QS_VERTICAL) && !qsFactoryAvailable
    }
}
