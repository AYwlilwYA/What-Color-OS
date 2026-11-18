package com.coloros16.liquidglass.engine

/**
 * 内容层变换分配：把求值器的 src/dst 通道按边的两端映射到具体态。
 *
 * src = 边的起点态，dst = 边的终点态；第三个态不参与本次转场，一律保持原样。
 * 纯 JVM，hook 与预览共用同一份映射，避免两边各自 if。
 */
object ContentLayerMapper {

    /** 一个态在本次转场里的变换量；identity=true 表示不参与，调用方不得写任何属性。 */
    class Pose {
        var alpha = 1f
        var scale = 1f
        var dx = 0f
        var dy = 0f
        var identity = true

        fun setIdentity() {
            alpha = 1f
            scale = 1f
            dx = 0f
            dy = 0f
            identity = true
        }

        fun set(a: Float, s: Float, x: Float, y: Float) {
            alpha = a.coerceIn(0f, 1f)
            scale = s
            dx = x
            dy = y
            identity = false
        }
    }

    /** 三态输出包；复用实例，逐帧不分配。 */
    class Poses {
        val aod = Pose()
        val lock = Pose()
        val home = Pose()

        fun of(state: SceneState): Pose = when (state) {
            SceneState.AOD -> aod
            SceneState.LOCK -> lock
            SceneState.HOME -> home
        }
    }

    /**
     * 按边把 params 的 src/dst 分派到三态。
     *
     * edge 为空或未激活时三态全 identity（调用方据此完全放手）。
     */
    fun map(params: TransitionParams, out: Poses) {
        out.aod.setIdentity()
        out.lock.setIdentity()
        out.home.setIdentity()
        val edge = params.edge
        if (edge == null || !params.active) return
        out.of(edge.from).set(params.srcAlpha, params.srcScale, params.srcDx, params.srcDy)
        out.of(edge.to).set(params.dstAlpha, params.dstScale, params.dstDx, params.dstDy)
    }

    /**
     * 该变换是否与「系统原样」等价（可跳过写入，省一次属性设置与重绘）。
     *
     * 阈值取亚像素与 8bit alpha 的量化下限。
     */
    fun isNeutral(pose: Pose): Boolean =
        kotlin.math.abs(pose.alpha - 1f) < ALPHA_EPS &&
            kotlin.math.abs(pose.scale - 1f) < SCALE_EPS &&
            kotlin.math.abs(pose.dx) < PX_EPS &&
            kotlin.math.abs(pose.dy) < PX_EPS

    private const val ALPHA_EPS = 0.004f
    private const val SCALE_EPS = 0.0005f
    private const val PX_EPS = 0.5f
}