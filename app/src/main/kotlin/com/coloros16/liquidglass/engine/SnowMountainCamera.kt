package com.coloros16.liquidglass.engine

/**
 * 雪山场景相机真值：三态目标位姿与场景常量。
 *
 * 数值取自官方场景脚本明文，逐值抄录不作推算；
 * 朝向由注视点减位置求前向向量决定，欧拉角字段仅供参考不参与朝向计算。
 */
object SnowMountainCamera {

    /** 三态位置（世界坐标）。 */
    val AOD_POS = floatArrayOf(79.3f, -36.95f, 180.0f)
    val LOCK_POS = floatArrayOf(-3.02f, -9.44f, 64.5f)
    val HOME_POS = floatArrayOf(-31.6f, -10.6f, 66.8f)

    /** 三态注视点。 */
    val AOD_LOOK = floatArrayOf(2.18f, -9.7f, 0.671f)
    val LOCK_LOOK = floatArrayOf(1.076f, -2.55f, 0.67f)
    val HOME_LOOK = floatArrayOf(-0.34f, -4.12f, 0.67f)

    /** 三态暗态：息屏压暗，锁屏与桌面不压。 */
    const val AOD_DARK = 1.0f
    const val LOCK_DARK = 0.0f
    const val HOME_DARK = 0.0f

    /** 三态旋转量：只有桌面为 1。 */
    const val AOD_ROT = 0.0f
    const val LOCK_ROT = 0.0f
    const val HOME_ROT = 1.0f

    /** 三态目标高度：息屏 0，锁屏与桌面 1。 */
    const val AOD_HEIGHT = 0.0f
    const val LOCK_HEIGHT = 1.0f
    const val HOME_HEIGHT = 1.0f

    /** 山体自转欧拉角与最大旋转限制。 */
    val MODEL_ROT = floatArrayOf(0.0f, 9.21f, 0.0f)
    val MAX_ROT = floatArrayOf(23f, 3f)
    const val MAX_YAW = -10f

    /** 日出与日落时刻（秒，本地时间）。 */
    const val SUNRISE_SECONDS = 21600
    const val SUNSET_SECONDS = 64800

    /** 桌面滑动基准帧间隔（秒）。 */
    const val DESK_SLIDE_DT = 0.0666f

    /** 目标峰值帧率。 */
    const val TARGET_FPS = 120

    /** 官方六款共用的锁屏时钟锚点（归一化）。 */
    const val CLOCK_X = 0.34f
    const val CLOCK_Y = 0.12f
    const val DUAL_CLOCK_X_RIGHT = 0.65f
    const val DUAL_CLOCK_Y = 0.6f

    /** 按态填目标位姿；状态量取场景时间轴锚点 0/0.5/1。 */
    fun target(state: SceneState, out: SceneCameraLerp.Pose) {
        val pos: FloatArray
        val look: FloatArray
        val dark: Float
        val rot: Float
        val height: Float
        when (state) {
            SceneState.AOD -> {
                pos = AOD_POS; look = AOD_LOOK
                dark = AOD_DARK; rot = AOD_ROT; height = AOD_HEIGHT
            }
            SceneState.LOCK -> {
                pos = LOCK_POS; look = LOCK_LOOK
                dark = LOCK_DARK; rot = LOCK_ROT; height = LOCK_HEIGHT
            }
            SceneState.HOME -> {
                pos = HOME_POS; look = HOME_LOOK
                dark = HOME_DARK; rot = HOME_ROT; height = HOME_HEIGHT
            }
        }
        out.px = pos[0]; out.py = pos[1]; out.pz = pos[2]
        out.lx = look[0]; out.ly = look[1]; out.lz = look[2]
        out.dark = dark
        out.rot = rot
        out.height = height
        out.state = SceneTimeline.anchor(state)
        out.elapsed = 0f
    }

    /**
     * 前向向量 = 注视点 − 位置（未归一化）。
     *
     * 官方朝向走 `FromForwardToQuat(lookAt − pos)`，故欧拉角字段不是最终朝向。
     */
    fun forward(pose: SceneCameraLerp.Pose, out: FloatArray) {
        out[0] = pose.lx - pose.px
        out[1] = pose.ly - pose.py
        out[2] = pose.lz - pose.pz
    }

    /** 息屏到锁屏的推近倍率（位置 z 之比）。 */
    fun aodToLockZoom(): Float = AOD_POS[2] / LOCK_POS[2]

    /**
     * 由前向向量反解偏航与俯仰（度），供只有欧拉角通道的渲染器消费。
     *
     * 与 `CameraMatrices.buildView` 的前向约定互逆：
     * `fx=sin(yaw)cos(pitch)`、`fy=sin(pitch)`、`fz=-cos(yaw)cos(pitch)`。
     * 写入 [out] 的 yawDeg/pitchDeg，位置与 fov 由调用方另给。
     */
    fun anglesFromForward(fx: Float, fy: Float, fz: Float, out: CameraPose) {
        val len = kotlin.math.sqrt(fx * fx + fy * fy + fz * fz)
        if (len < 1e-6f) {
            out.yawDeg = 0f
            out.pitchDeg = 0f
            return
        }
        val nx = fx / len
        val ny = fy / len
        val nz = fz / len
        out.pitchDeg = CameraMatrices.clampPitch(Math.toDegrees(kotlin.math.asin(ny.toDouble()).toDouble()).toFloat())
        out.yawDeg = Math.toDegrees(kotlin.math.atan2(nx.toDouble(), -nz.toDouble())).toFloat()
    }

    /**
     * 把场景位姿折算成渲染器位姿：位置直取、朝向由注视点反解、视场角沿用默认。
     *
     * 官方朝向走前向向量，故不能直接抄欧拉角字段。
     */
    fun toCameraPose(pose: SceneCameraLerp.Pose, out: CameraPose) {
        out.x = pose.px
        out.y = pose.py
        out.z = pose.pz
        forward(pose, scratchForward)
        anglesFromForward(scratchForward[0], scratchForward[1], scratchForward[2], out)
        out.rollDeg = 0f
    }

    private val scratchForward = FloatArray(3)

    /**
     * 该场景所需的远裁剪面：必须大于最远相机距离，否则整幅被裁掉。
     *
     * 取三态位置模长最大值再留一倍余量。
     */
    fun requiredFarPlane(): Float {
        var max = 0f
        for (p in listOf(AOD_POS, LOCK_POS, HOME_POS)) {
            val d = kotlin.math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2])
            if (d > max) max = d
        }
        return max * 2f
    }

    /** 该秒数是否处于白昼（日出到日落之间）。 */
    fun daylight(secondOfDay: Int): Boolean =
        secondOfDay in SUNRISE_SECONDS until SUNSET_SECONDS
}