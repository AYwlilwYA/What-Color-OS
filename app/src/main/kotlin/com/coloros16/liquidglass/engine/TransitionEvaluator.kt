package com.coloros16.liquidglass.engine

/** 一次求值的输出参数包；复用实例，逐帧不分配。 */
class TransitionParams {
    var active: Boolean = false
    var edge: TransitionEdge? = null
    var t: Float = 0f

    var cameraX: Float = 0.5f
    var cameraY: Float = 0.5f
    var cameraScale: Float = 1f
    var cameraRotationDeg: Float = 0f
    var light: Float = 1f

    var maskEnabled: Boolean = false
    var maskShape: MaskShape = MaskShape.CIRCLE
    var maskCenterX: Float = 0f
    var maskCenterY: Float = 0f
    var maskRadius: Float = 0f
    var maskFeather: Float = 0f

    var srcAlpha: Float = 1f
    var dstAlpha: Float = 0f
    var srcScale: Float = 1f
    var dstScale: Float = 1f
    var srcDx: Float = 0f
    var srcDy: Float = 0f
    var dstDx: Float = 0f
    var dstDy: Float = 0f

    fun reset() {
        active = false
        edge = null
        t = 0f
        cameraX = 0.5f
        cameraY = 0.5f
        cameraScale = 1f
        cameraRotationDeg = 0f
        light = 1f
        maskEnabled = false
        maskShape = MaskShape.CIRCLE
        maskCenterX = 0f
        maskCenterY = 0f
        maskRadius = 0f
        maskFeather = 0f
        srcAlpha = 1f
        dstAlpha = 0f
        srcScale = 1f
        dstScale = 1f
        srcDx = 0f
        srcDy = 0f
        dstDx = 0f
        dstDy = 0f
    }

    fun copyFrom(other: TransitionParams) {
        active = other.active
        edge = other.edge
        t = other.t
        cameraX = other.cameraX
        cameraY = other.cameraY
        cameraScale = other.cameraScale
        cameraRotationDeg = other.cameraRotationDeg
        light = other.light
        maskEnabled = other.maskEnabled
        maskShape = other.maskShape
        maskCenterX = other.maskCenterX
        maskCenterY = other.maskCenterY
        maskRadius = other.maskRadius
        maskFeather = other.maskFeather
        srcAlpha = other.srcAlpha
        dstAlpha = other.dstAlpha
        srcScale = other.srcScale
        dstScale = other.dstScale
        srcDx = other.srcDx
        srcDy = other.srcDy
        dstDx = other.dstDx
        dstDy = other.dstDy
    }
}

/**
 * 转场求值器：输入 边 + t + 两侧样式能力 + 屏幕尺寸，输出参数包。
 * 三层各自由能力位门控；没有可用层时走硬切兜底。
 */
class TransitionEvaluator(
    private val profiles: MutableMap<TransitionEdge, TransitionProfile> = HashMap(),
    private val defaultProfile: TransitionProfile = TransitionProfile(),
) {

    fun profileOf(edge: TransitionEdge): TransitionProfile = profiles[edge] ?: defaultProfile

    fun setProfile(edge: TransitionEdge, profile: TransitionProfile) {
        profiles[edge] = profile
    }

    /** 同态直接返回不活跃，不做任何计算。 */
    fun evaluate(
        edge: TransitionEdge,
        t: Float,
        screen: ScreenMetrics,
        srcCapabilities: StyleCapabilities,
        dstCapabilities: StyleCapabilities,
        out: TransitionParams,
        sceneOut: SceneSample? = null,
    ) {
        out.reset()
        if (edge.from == edge.to) return

        val progress = if (t.isNaN()) 0f else t.coerceIn(0f, 1f)
        out.active = true
        out.edge = edge
        out.t = progress

        val profile = profileOf(edge)
        var applied = applyContent(profile.content, progress, screen, srcCapabilities, dstCapabilities, out)
        applied = applyMask(profile.mask, progress, screen, srcCapabilities, dstCapabilities, out) || applied
        applied = applyScene(profile.scene, progress, srcCapabilities, dstCapabilities, out, sceneOut) || applied

        if (!applied) hardCut(progress, out)
    }

    private fun applyContent(
        config: ContentLayerConfig,
        t: Float,
        screen: ScreenMetrics,
        srcCapabilities: StyleCapabilities,
        dstCapabilities: StyleCapabilities,
        out: TransitionParams,
    ): Boolean {
        if (!config.enabled || !srcCapabilities.crossfade || !dstCapabilities.crossfade) return false
        val e = config.easing.map(t)
        out.srcAlpha = 1f - e
        out.dstAlpha = e
        val slideX: Float
        val slideY: Float
        when (config.direction) {
            SlideDirection.LEFT -> { slideX = -config.slideFraction * screen.widthPx; slideY = 0f }
            SlideDirection.RIGHT -> { slideX = config.slideFraction * screen.widthPx; slideY = 0f }
            SlideDirection.UP -> { slideX = 0f; slideY = -config.slideFraction * screen.heightPx }
            SlideDirection.DOWN -> { slideX = 0f; slideY = config.slideFraction * screen.heightPx }
            SlideDirection.NONE -> { slideX = 0f; slideY = 0f }
        }
        out.srcDx = slideX * e
        out.srcDy = slideY * e
        out.dstDx = slideX * (1f - e)
        out.dstDy = slideY * (1f - e)
        return true
    }

    private fun applyMask(
        config: MaskLayerConfig,
        t: Float,
        screen: ScreenMetrics,
        srcCapabilities: StyleCapabilities,
        dstCapabilities: StyleCapabilities,
        out: TransitionParams,
    ): Boolean {
        if (!config.enabled || !srcCapabilities.mask || !dstCapabilities.mask) return false
        val e = config.easing.map(t)
        val cx = config.centerXFraction.coerceIn(0f, 1f) * screen.widthPx
        val cy = config.centerYFraction.coerceIn(0f, 1f) * screen.heightPx
        val endRadius = screen.maxCornerDistance(cx, cy) * config.endRadiusScale
        val feather = (screen.shortSide * config.featherFraction)
            .coerceIn(config.featherMinPx, config.featherMaxPx)

        out.maskEnabled = true
        out.maskShape = config.shape
        out.maskCenterX = cx
        out.maskCenterY = cy
        out.maskRadius = endRadius * e
        out.maskFeather = feather

        val fadeStart = config.outerFadeStart.coerceIn(0f, 0.999f)
        val outerAlpha = if (e <= fadeStart) 1f else {
            val local = (e - fadeStart) / (1f - fadeStart)
            1f - smoothStep(local)
        }
        out.srcAlpha *= outerAlpha
        out.srcScale = 1f + (config.outerScaleTo - 1f) * e
        return true
    }

    private fun applyScene(
        config: SceneLayerConfig,
        t: Float,
        srcCapabilities: StyleCapabilities,
        dstCapabilities: StyleCapabilities,
        out: TransitionParams,
        sceneOut: SceneSample?,
    ): Boolean {
        if (!config.enabled || !srcCapabilities.sceneContinuous || !dstCapabilities.sceneContinuous) return false
        val contract = config.contract ?: return false
        val sample = sceneOut ?: return false
        sample.sample(contract, config.easing.map(t))
        out.cameraX = sample.cameraX
        out.cameraY = sample.cameraY
        out.cameraScale = sample.cameraScale
        out.cameraRotationDeg = sample.cameraRotationDeg
        out.light = sample.light
        return true
    }

    private fun hardCut(t: Float, out: TransitionParams) {
        if (t >= 1f) {
            out.srcAlpha = 0f
            out.dstAlpha = 1f
        } else {
            out.srcAlpha = 1f
            out.dstAlpha = 0f
        }
    }

    private fun smoothStep(x: Float): Float {
        val v = x.coerceIn(0f, 1f)
        return v * v * (3f - 2f * v)
    }
}
