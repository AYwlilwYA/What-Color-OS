package com.coloros16.liquidglass.engine

/**
 * 壁纸引擎的三态判定：把框架给的息屏标志与锁屏状态折成一个场景态。
 *
 * 框架只区分「息屏 / 非息屏」，锁屏与桌面靠锁屏状态再分一次；
 * 息屏优先于锁屏，因为息屏时锁屏状态仍为已锁。
 */
object SceneModeResolver {

    /** 场景态序号：息屏 0、锁屏 1、桌面 2；越界值一律视为无效。 */
    const val MODE_AOD = 0
    const val MODE_LOCK = 1
    const val MODE_HOME = 2

    /** 息屏动画时长缺省值（毫秒）：框架给 0 表示立即切换。 */
    const val SNAP_DURATION_MS = 0L

    fun resolve(ambient: Boolean, keyguardLocked: Boolean): SceneState = when {
        ambient -> SceneState.AOD
        keyguardLocked -> SceneState.LOCK
        else -> SceneState.HOME
    }

    fun modeOf(state: SceneState): Int = when (state) {
        SceneState.AOD -> MODE_AOD
        SceneState.LOCK -> MODE_LOCK
        SceneState.HOME -> MODE_HOME
    }

    fun validMode(mode: Int): Boolean = mode in MODE_AOD..MODE_HOME

    fun stateOfMode(mode: Int): SceneState? = when (mode) {
        MODE_AOD -> SceneState.AOD
        MODE_LOCK -> SceneState.LOCK
        MODE_HOME -> SceneState.HOME
        else -> null
    }

    /**
     * 亮屏后是否要等一拍再认锁屏态。
     *
     * 触摸唤醒可能早于指纹认证完成，立刻认锁屏会把「息屏直接进桌面」掰成经过锁屏；
     * 只有从息屏出来且当前报已锁时才等，等完再读一次锁屏状态。
     */
    fun needsLockSettle(previous: SceneState, next: SceneState, keyguardLocked: Boolean): Boolean =
        previous == SceneState.AOD && next == SceneState.LOCK && keyguardLocked

    /** 等待时长（毫秒）：与厂商播放器同值。 */
    const val LOCK_SETTLE_MS = 100L

    /**
     * 等待结束后是否仍认锁屏：期间已解锁则放弃这次锁屏，交由后续事件推桌面。
     */
    fun lockStillHolds(keyguardLockedNow: Boolean): Boolean = keyguardLockedNow

    /** 帧间隔（秒）由毫秒差换算，负值归零。 */
    fun deltaSeconds(deltaMs: Long): Float =
        if (deltaMs <= 0L) 0f else deltaMs / 1000f

    /**
     * 该帧是否需要重绘：只看位姿是否已贴合目标。
     *
     * 指数模型永不精确到达，靠容差判贴合；过渡时长只用于降帧率，不能当停帧判据
     * （时长到而位姿还差一截时停帧会留下可见空档）。
     */
    fun needsFrame(current: SceneCameraLerp.Pose, target: SceneCameraLerp.Pose): Boolean =
        !atTarget(current, target)

    /** 位姿是否已贴合目标（位置与注视点各分量都在容差内）。 */
    fun atTarget(current: SceneCameraLerp.Pose, target: SceneCameraLerp.Pose): Boolean {
        val eps = TARGET_EPS
        return near(current.px, target.px, eps) &&
            near(current.py, target.py, eps) &&
            near(current.pz, target.pz, eps) &&
            near(current.lx, target.lx, eps) &&
            near(current.ly, target.ly, eps) &&
            near(current.lz, target.lz, eps) &&
            near(current.dark, target.dark, eps) &&
            near(current.rot, target.rot, eps) &&
            near(current.state, target.state, eps) &&
            near(current.height, target.height, eps)
    }

    /** 世界尺度下的贴合容差：相机距离约二百，取千分之一。 */
    const val TARGET_EPS = 0.01f

    private fun near(a: Float, b: Float, eps: Float): Boolean = kotlin.math.abs(a - b) <= eps
}
