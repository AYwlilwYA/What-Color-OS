package com.coloros16.liquidglass.engine

/** 遮罩形状。 */
enum class MaskShape {
    CIRCLE,
    ROUNDED_RECT,
}

/** 内容层位移方向。 */
enum class SlideDirection {
    NONE,
    LEFT,
    RIGHT,
    UP,
    DOWN,
}

/** 样式能力标签：决定某层能不能用。 */
data class StyleCapabilities(
    val sceneContinuous: Boolean = false,
    val mask: Boolean = false,
    val crossfade: Boolean = true,
    val frameReveal: Boolean = false,
)

/** L1 场景连续层配置。 */
class SceneLayerConfig(
    var enabled: Boolean = false,
    var contract: SceneContract? = null,
    var easing: Easing = Easing.STANDARD,
)

/** L2 圆形揭开层配置。 */
class MaskLayerConfig(
    var enabled: Boolean = false,
    var shape: MaskShape = MaskShape.CIRCLE,
    var centerXFraction: Float = 0.5f,
    var centerYFraction: Float = 0.5f,
    var endRadiusScale: Float = 1.05f,
    var featherFraction: Float = 0.08f,
    var featherMinPx: Float = 24f,
    var featherMaxPx: Float = 160f,
    var outerFadeStart: Float = 0.35f,
    var outerScaleTo: Float = 1.06f,
    var easing: Easing = Easing.STANDARD,
)

/** L3 内容交叠层配置。 */
class ContentLayerConfig(
    var enabled: Boolean = true,
    var easing: Easing = Easing.STANDARD,
    var direction: SlideDirection = SlideDirection.NONE,
    var slideFraction: Float = 0f,
)

/** 一条边的三层配方；三层各自开/关，共用一个进度 t。 */
class TransitionProfile(
    val scene: SceneLayerConfig = SceneLayerConfig(),
    val mask: MaskLayerConfig = MaskLayerConfig(),
    val content: ContentLayerConfig = ContentLayerConfig(),
) {

    /** 是否至少有一层启用（否则走硬切兜底）。 */
    fun hasAnyLayer(): Boolean = scene.enabled || mask.enabled || content.enabled
}
