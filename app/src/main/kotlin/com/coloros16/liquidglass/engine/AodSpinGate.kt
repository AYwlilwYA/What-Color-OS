package com.coloros16.liquidglass.engine

/**
 * 镜片壁纸自转门：只有启航款、且息屏静止态或从息屏出发的展开边才转。
 *
 * 预览与 hook 共用同一判定，避免两侧各写一套导致真机在不该转的态上转。
 */
object AodSpinGate {

    /** 自转是否该推进。[isLens] 为当前样式是否镜片款，[edge] 为空表示静止态。 */
    fun active(allowDynamic: Boolean, isLens: Boolean, state: SceneState, edge: TransitionEdge?): Boolean {
        if (!allowDynamic || !isLens) return false
        if (edge == null) return state == SceneState.AOD
        return edge == TransitionEdge.AOD_TO_LOCK || edge == TransitionEdge.AOD_TO_HOME
    }

    /**
     * 息屏静止态帧链是否该逐帧：自转门开，或混色/照片倾斜幅度非零。
     *
     * 总闸开但三参数全零时画面不动，不该武装任何回调。
     */
    fun stillAnimating(
        allowDynamic: Boolean,
        isLens: Boolean,
        rotateSpeed: Float,
        colorMix: Float,
        photoTilt: Float,
    ): Boolean {
        if (!allowDynamic) return false
        if (active(allowDynamic, isLens, SceneState.AOD, null) && rotateSpeed > 0f) return true
        if (isLens && colorMix > 0f) return true
        return photoTilt > 0f
    }
}
