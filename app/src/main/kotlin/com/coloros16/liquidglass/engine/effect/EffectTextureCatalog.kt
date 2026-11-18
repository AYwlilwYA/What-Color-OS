package com.coloros16.liquidglass.engine.effect

/** 贴图环绕方式。 */
enum class TextureWrap { CROP_OR_STRETCH, REPEAT, STRETCH }

/**
 * 一种壁纸效果的原版贴图与玻璃/光照/模糊参数。
 *
 * 数值取自系统壁纸应用的效果配置，400×840 竖屏档；贴图是法线/高度图，
 * R/G 编码法线 XY、A 编码高度，不是彩色纹理。
 */
data class EffectTextureSpec(
    val effectId: String,
    /** 法线贴图在 assets 下的相对路径。 */
    val texturePath: String,
    /** 贴图在布局上的缩放倍数。 */
    val layoutScale: Float,
    /** 采样区域 left/top/right/bottom；全 0 表示整图。 */
    val region: IntArray,
    val wrapX: TextureWrap,
    val wrapY: TextureWrap,
    /** 玻璃染色（AARRGGBB 文本）。 */
    val glassColor: String,
    val thickness: Float,
    val refractivity: Float,
    val specular: Float,
    /** 叠加背景模糊后的厚度/折射/高光。 */
    val blurThickness: Float,
    val blurRefractivity: Float,
    val blurSpecular: Float,
    /** 方向光水平角（度）。 */
    val lightAngleDeg: Float,
    val lightStrength: Float,
    val lightColor: String,
    /** 降采样步长与模糊半径；-1 表示该档不模糊。 */
    val blurStep: Int,
    val blurRadius: Int,
    val blurredStep: Int,
    val blurredRadius: Int,
) {
    val hasRegion: Boolean get() = region.any { it != 0 }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EffectTextureSpec) return false
        return effectId == other.effectId && texturePath == other.texturePath &&
            layoutScale == other.layoutScale && region.contentEquals(other.region) &&
            wrapX == other.wrapX && wrapY == other.wrapY && glassColor == other.glassColor &&
            thickness == other.thickness && refractivity == other.refractivity &&
            specular == other.specular && blurThickness == other.blurThickness &&
            blurRefractivity == other.blurRefractivity && blurSpecular == other.blurSpecular &&
            lightAngleDeg == other.lightAngleDeg && lightStrength == other.lightStrength &&
            lightColor == other.lightColor && blurStep == other.blurStep &&
            blurRadius == other.blurRadius && blurredStep == other.blurredStep &&
            blurredRadius == other.blurredRadius
    }

    override fun hashCode(): Int {
        var result = effectId.hashCode()
        result = 31 * result + texturePath.hashCode()
        result = 31 * result + layoutScale.hashCode()
        result = 31 * result + region.contentHashCode()
        result = 31 * result + wrapX.hashCode()
        result = 31 * result + wrapY.hashCode()
        result = 31 * result + glassColor.hashCode()
        result = 31 * result + thickness.hashCode()
        result = 31 * result + refractivity.hashCode()
        result = 31 * result + specular.hashCode()
        result = 31 * result + blurThickness.hashCode()
        result = 31 * result + blurRefractivity.hashCode()
        result = 31 * result + blurSpecular.hashCode()
        result = 31 * result + lightAngleDeg.hashCode()
        result = 31 * result + lightStrength.hashCode()
        result = 31 * result + lightColor.hashCode()
        result = 31 * result + blurStep
        result = 31 * result + blurRadius
        result = 31 * result + blurredStep
        result = 31 * result + blurredRadius
        return result
    }
}

/** 效果 id → 原版贴图与参数表。 */
object EffectTextureCatalog {

    private const val DIR = "effect/lock/"

    private val specs: List<EffectTextureSpec> = listOf(
        EffectTextureSpec(
            EffectCatalog.LOCK_WP_STRIPES_FINE, DIR + "tight_stripes.webp",
            1f, intArrayOf(0, 0, 0, 0), TextureWrap.CROP_OR_STRETCH, TextureWrap.CROP_OR_STRETCH,
            "#05131F4E", 3.6f, 1.18f, 8.2f, 5f, 1.36f, 8.2f,
            -22.293f, 0.1f, "#FFDEDEDE", 1, 6, 4, 40,
        ),
        EffectTextureSpec(
            EffectCatalog.LOCK_WP_STRIPES_COARSE, DIR + "alt_stripes.webp",
            2.93f, intArrayOf(0, 0, 0, 0), TextureWrap.CROP_OR_STRETCH, TextureWrap.CROP_OR_STRETCH,
            "#0A101323", 1.06f, 1.04f, 1.6f, 1.6f, 1.06f, 1.2f,
            -1f, 0.04f, "#FFC9C9C9", -1, -1, 5, 40,
        ),
        EffectTextureSpec(
            EffectCatalog.LOCK_WP_LIGHT_STRIPES, DIR + "sparse_stripes.png",
            0.99f, intArrayOf(0, 0, 0, 0), TextureWrap.CROP_OR_STRETCH, TextureWrap.CROP_OR_STRETCH,
            "#0A101323", 1.6f, 1.12f, 43f, 1.8f, 1.2f, 43f,
            -13.16f, 0.07f, "#FFC7C7C7", -1, -1, 3, 60,
        ),
        EffectTextureSpec(
            EffectCatalog.LOCK_WP_GRID, DIR + "rhombus_grid.webp",
            8.04f, intArrayOf(0, 0, 482, 482), TextureWrap.CROP_OR_STRETCH, TextureWrap.CROP_OR_STRETCH,
            "#05131F4E", 0.42f, 1.06f, 8f, 0.42f, 1.06f, 8f,
            0f, 0f, "#FFFFFFFF", -1, -1, 1, 40,
        ),
        EffectTextureSpec(
            EffectCatalog.LOCK_WP_WATER, DIR + "water_ripples.webp",
            2.88f, intArrayOf(0, 0, 0, 0), TextureWrap.REPEAT, TextureWrap.CROP_OR_STRETCH,
            "#05131F4E", 2.98f, 1.04f, 1.4f, 3.68f, 1.04f, 1.4f,
            32.619f, 0.04f, "#FFEDEDED", 1, 5, 3, 60,
        ),
        EffectTextureSpec(
            EffectCatalog.LOCK_WP_RIPPLE, DIR + "spiral_grain.webp",
            0.75f, intArrayOf(285, 0, 555, 600), TextureWrap.STRETCH, TextureWrap.STRETCH,
            "#05131F4E", 0.86f, 1.04f, 0.8f, 0.4f, 1.04f, 0.8f,
            -30.929f, 0.03f, "#FFEDEDED", 1, 10, 3, 80,
        ),
    )

    private val byId: Map<String, EffectTextureSpec> = specs.associateBy { it.effectId }

    val all: List<EffectTextureSpec> get() = specs

    fun find(effectId: String?): EffectTextureSpec? = if (effectId == null) null else byId[effectId]

    fun hasTexture(effectId: String?): Boolean = find(effectId) != null

    /** 该档是否需要背景模糊（step/radius 均为正）。 */
    fun needsBlur(spec: EffectTextureSpec, blurred: Boolean): Boolean =
        if (blurred) spec.blurredStep > 0 && spec.blurredRadius > 0
        else spec.blurStep > 0 && spec.blurRadius > 0

    /** 按是否叠加模糊取厚度。 */
    fun thickness(spec: EffectTextureSpec, blurred: Boolean): Float =
        if (blurred) spec.blurThickness else spec.thickness

    /** 按是否叠加模糊取折射率。 */
    fun refractivity(spec: EffectTextureSpec, blurred: Boolean): Float =
        if (blurred) spec.blurRefractivity else spec.refractivity

    /** 按是否叠加模糊取高光强度。 */
    fun specular(spec: EffectTextureSpec, blurred: Boolean): Float =
        if (blurred) spec.blurSpecular else spec.specular
}

/** 景深（移轴模糊）的原版参数。 */
object TiltShiftSpec {
    const val LEVEL = 1
    const val RADIUS = 300
    const val HEIGHT_PERCENT = 0.45f
    const val CENTER_OFFSET = 1f
    const val SMOOTH_PERCENT = 1.36f

    /** 模糊带高度（px）。 */
    fun bandHeightPx(layoutHeightPx: Float): Float = layoutHeightPx * HEIGHT_PERCENT

    /** 模糊带中心 Y（px）：centerOffset=1 表示贴布局底部。 */
    fun bandCenterPx(layoutHeightPx: Float): Float = layoutHeightPx * CENTER_OFFSET

    /** 羽化过渡区高度（px）。 */
    fun featherPx(layoutHeightPx: Float): Float = bandHeightPx(layoutHeightPx) * SMOOTH_PERCENT
}