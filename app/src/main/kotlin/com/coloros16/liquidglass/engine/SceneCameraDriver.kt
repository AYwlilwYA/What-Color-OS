package com.coloros16.liquidglass.engine

/**
 * 三态场景相机的逐帧驱动状态：持有当前位姿与目标位姿，按事件换目标、按帧逼近。
 *
 * 自身不起线程也不排定时器，帧由调用方的帧回调驱动；收敛后调用方据 [needsFrame] 停帧。
 */
class SceneCameraDriver(initial: SceneState = SceneState.AOD) {

    val current = SceneCameraLerp.Pose()
    val target = SceneCameraLerp.Pose()
    private val renderPose = CameraPose()

    var state: SceneState = initial
        private set

    /** 目标态是否与当前已宣告态不同（用于只换一次目标）。 */
    fun retarget(next: SceneState, snap: Boolean): Boolean {
        val changed = next != state
        state = next
        SnowMountainCamera.target(next, target)
        if (snap) {
            current.copyFrom(target)
            current.elapsed = 0f
        }
        return changed
    }

    /** 首次建立：直接把当前位姿摆到该态，不走过渡。 */
    fun reset(to: SceneState) {
        state = to
        SnowMountainCamera.target(to, target)
        current.copyFrom(target)
        current.elapsed = 0f
    }

    /** 推进一帧；[deltaMs] 为距上一帧的毫秒差。 */
    fun advance(deltaMs: Long) {
        SceneCameraLerp.advance(current, target, SceneModeResolver.deltaSeconds(deltaMs))
    }

    fun needsFrame(): Boolean = SceneModeResolver.needsFrame(current, target)

    /** 取渲染器位姿：位置直取、朝向由注视点反解。 */
    fun pose(): CameraPose {
        SnowMountainCamera.toCameraPose(current, renderPose)
        return renderPose
    }

    /** 该场景所需远裁剪面。 */
    fun farPlane(): Float = SnowMountainCamera.requiredFarPlane()

    /** 当前压暗量（世界层材质用，息屏为 1）。 */
    fun dark(): Float = current.dark

    /** 桌面滑动旋转量（山体自转用）。 */
    fun rot(): Float = current.rot
}
