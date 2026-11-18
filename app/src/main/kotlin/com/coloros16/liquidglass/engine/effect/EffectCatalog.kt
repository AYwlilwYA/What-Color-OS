package com.coloros16.liquidglass.engine.effect

/** 效果所属界面。 */
enum class EffectState { AOD, LOCK, HOME }

/** 效果来源家族，供「样式」页分组。 */
enum class EffectFamily { PANORAMA, MEIZU, OPLUS, SCENE_3D, STANDARD }

/** 效果类别。 */
enum class EffectKind {
    AOD_STYLE,
    LOCK_WALLPAPER,
    LOCK_SPECIAL,
    HOME_TRANSITION,
    HOME_ICON,
}

/**
 * 一条效果定义。
 *
 * 门槛字段互相独立，判定顺序见 [EffectResolver]：
 * 未实现 → 需要 AOD 动态 → 需要全景息屏 → 需要陀螺仪 → 需要图片场景。
 */
data class EffectDef(
    val id: String,
    val kind: EffectKind,
    val family: EffectFamily,
    val nameKey: String,
    val implemented: Boolean = true,
    val requiresAodDynamic: Boolean = false,
    val requiresPanoramaAod: Boolean = false,
    val requiresGyro: Boolean = false,
    val requiresPhotoScene: Boolean = false,
    /** true 表示以预处理静态贴图为主，求值只给贴图位移/缩放/强度。 */
    val textureBased: Boolean = false,
)

/** 三态效果清单：id 稳定，不再改名。 */
object EffectCatalog {

    // ---------- 锁屏壁纸效果 ----------
    const val LOCK_WP_NONE = "lock.wp.none"
    const val LOCK_WP_RAINBOW = "lock.wp.meizu.rainbow"
    const val LOCK_WP_WAVE = "lock.wp.meizu.wave"
    const val LOCK_WP_FOG = "lock.wp.meizu.fog"
    const val LOCK_WP_BLUR = "lock.wp.oplus.blur"
    const val LOCK_WP_STRIPES_FINE = "lock.wp.oplus.stripes_fine"
    const val LOCK_WP_STRIPES_COARSE = "lock.wp.oplus.stripes_coarse"
    const val LOCK_WP_LIGHT_STRIPES = "lock.wp.oplus.light_stripes"
    const val LOCK_WP_GRID = "lock.wp.oplus.grid"
    const val LOCK_WP_WATER = "lock.wp.oplus.water"
    const val LOCK_WP_RIPPLE = "lock.wp.oplus.ripple"

    // ---------- 锁屏特殊效果 ----------
    const val LOCK_SP_NONE = "lock.sp.none"
    const val LOCK_SP_LIQUID = "lock.sp.liquid"
    const val LOCK_SP_LENTICULAR = "lock.sp.lenticular"
    const val LOCK_SP_FLASH_CARD = "lock.sp.flash_card"
    const val LOCK_SP_DEPTH_3D = "lock.sp.oplus.depth3d"
    const val LOCK_SP_BOKEH = "lock.sp.oplus.bokeh"

    // ---------- 桌面过渡 ----------
    const val HOME_TRANSITION_NONE = "home.transition.none"
    const val HOME_TRANSITION_CROSSFADE = "home.transition.crossfade"

    // ---------- 桌面图标入场 ----------
    const val HOME_ICON_FOREGROUND_FLY = "home.icon.foreground_fly"
    const val HOME_ICON_BACKGROUND_FLY = "home.icon.background_fly"
    const val HOME_ICON_ROTATE_FLY = "home.icon.rotate_fly"
    const val HOME_ICON_CROSSFADE = "home.icon.crossfade"

    private val lockWallpaper: List<EffectDef> = listOf(
        EffectDef(LOCK_WP_NONE, EffectKind.LOCK_WALLPAPER, EffectFamily.STANDARD, "effect_lock_wp_none"),
        EffectDef(
            LOCK_WP_RAINBOW, EffectKind.LOCK_WALLPAPER, EffectFamily.MEIZU, "effect_lock_wp_rainbow",
            textureBased = true,
        ),
        EffectDef(
            LOCK_WP_WAVE, EffectKind.LOCK_WALLPAPER, EffectFamily.MEIZU, "effect_lock_wp_wave",
            textureBased = true,
        ),
        EffectDef(
            LOCK_WP_FOG, EffectKind.LOCK_WALLPAPER, EffectFamily.MEIZU, "effect_lock_wp_fog",
            textureBased = true,
        ),
        EffectDef(LOCK_WP_BLUR, EffectKind.LOCK_WALLPAPER, EffectFamily.OPLUS, "effect_lock_wp_blur"),
        EffectDef(
            LOCK_WP_STRIPES_FINE, EffectKind.LOCK_WALLPAPER, EffectFamily.OPLUS, "effect_lock_wp_stripes_fine",
            textureBased = true,
        ),
        EffectDef(
            LOCK_WP_STRIPES_COARSE, EffectKind.LOCK_WALLPAPER, EffectFamily.OPLUS, "effect_lock_wp_stripes_coarse",
            textureBased = true,
        ),
        EffectDef(
            LOCK_WP_LIGHT_STRIPES, EffectKind.LOCK_WALLPAPER, EffectFamily.OPLUS, "effect_lock_wp_light_stripes",
            textureBased = true,
        ),
        EffectDef(
            LOCK_WP_GRID, EffectKind.LOCK_WALLPAPER, EffectFamily.OPLUS, "effect_lock_wp_grid",
            textureBased = true,
        ),
        EffectDef(
            LOCK_WP_WATER, EffectKind.LOCK_WALLPAPER, EffectFamily.OPLUS, "effect_lock_wp_water",
            textureBased = true,
        ),
        EffectDef(
            LOCK_WP_RIPPLE, EffectKind.LOCK_WALLPAPER, EffectFamily.OPLUS, "effect_lock_wp_ripple",
            textureBased = true,
        ),
    )

    private val lockSpecial: List<EffectDef> = listOf(
        EffectDef(LOCK_SP_NONE, EffectKind.LOCK_SPECIAL, EffectFamily.STANDARD, "effect_lock_sp_none"),
        EffectDef(
            LOCK_SP_LIQUID, EffectKind.LOCK_SPECIAL, EffectFamily.STANDARD, "effect_lock_sp_liquid",
            requiresAodDynamic = true, requiresPanoramaAod = true,
        ),
        EffectDef(
            LOCK_SP_LENTICULAR, EffectKind.LOCK_SPECIAL, EffectFamily.STANDARD, "effect_lock_sp_lenticular",
            requiresAodDynamic = true, requiresPanoramaAod = true, requiresGyro = true,
        ),
        EffectDef(
            LOCK_SP_FLASH_CARD, EffectKind.LOCK_SPECIAL, EffectFamily.STANDARD, "effect_lock_sp_flash_card",
            requiresAodDynamic = true, requiresPanoramaAod = true, requiresGyro = true,
        ),
        EffectDef(
            LOCK_SP_DEPTH_3D, EffectKind.LOCK_SPECIAL, EffectFamily.OPLUS, "effect_lock_sp_depth3d",
            implemented = false, requiresPhotoScene = true,
        ),
        EffectDef(
            LOCK_SP_BOKEH, EffectKind.LOCK_SPECIAL, EffectFamily.OPLUS, "effect_lock_sp_bokeh",
            requiresAodDynamic = true,
        ),
    )

    private val homeTransition: List<EffectDef> = listOf(
        EffectDef(HOME_TRANSITION_NONE, EffectKind.HOME_TRANSITION, EffectFamily.STANDARD, "effect_home_transition_none"),
        EffectDef(
            HOME_TRANSITION_CROSSFADE, EffectKind.HOME_TRANSITION, EffectFamily.STANDARD,
            "effect_home_transition_crossfade",
        ),
    )

    private val homeIcon: List<EffectDef> = listOf(
        EffectDef(HOME_ICON_FOREGROUND_FLY, EffectKind.HOME_ICON, EffectFamily.OPLUS, "effect_home_icon_foreground"),
        EffectDef(HOME_ICON_BACKGROUND_FLY, EffectKind.HOME_ICON, EffectFamily.OPLUS, "effect_home_icon_background"),
        EffectDef(HOME_ICON_ROTATE_FLY, EffectKind.HOME_ICON, EffectFamily.OPLUS, "effect_home_icon_rotate"),
        EffectDef(HOME_ICON_CROSSFADE, EffectKind.HOME_ICON, EffectFamily.STANDARD, "effect_home_icon_crossfade"),
    )

    val all: List<EffectDef> = lockWallpaper + lockSpecial + homeTransition + homeIcon

    private val byId: Map<String, EffectDef> = all.associateBy { it.id }

    fun find(id: String?): EffectDef? = if (id == null) null else byId[id]

    fun of(kind: EffectKind): List<EffectDef> = when (kind) {
        EffectKind.LOCK_WALLPAPER -> lockWallpaper
        EffectKind.LOCK_SPECIAL -> lockSpecial
        EffectKind.HOME_TRANSITION -> homeTransition
        EffectKind.HOME_ICON -> homeIcon
        EffectKind.AOD_STYLE -> emptyList()
    }

    fun isTextureBased(id: String?): Boolean = find(id)?.textureBased == true
}

/** 样式 id → 家族。现有息屏样式在此登记；新素材按前缀落族，无需改样式目录。 */
object StyleFamilyMap {

    private val known: Map<String, EffectFamily> = mapOf(
        "aod.fullscreen" to EffectFamily.PANORAMA,
        "aod.sail" to EffectFamily.MEIZU,
        "aod.starmoon" to EffectFamily.MEIZU,
        "aod.leave_light" to EffectFamily.MEIZU,
        "aod.door" to EffectFamily.MEIZU,
        "aod.mountain" to EffectFamily.MEIZU,
        "aod.galaxy" to EffectFamily.MEIZU,
        "aod.handpaint" to EffectFamily.OPLUS,
        "aod.text" to EffectFamily.OPLUS,
        "aod.portrait" to EffectFamily.OPLUS,
    )

    fun familyOf(styleId: String?): EffectFamily {
        if (styleId.isNullOrBlank()) return EffectFamily.STANDARD
        known[styleId]?.let { return it }
        val lower = styleId.lowercase()
        return when {
            lower.startsWith("scene3d.") || lower.startsWith("3d.") || lower.startsWith("scene.") ->
                EffectFamily.SCENE_3D
            lower.startsWith("oplus.") || lower.startsWith("op.") -> EffectFamily.OPLUS
            lower.startsWith("meizu.") || lower.startsWith("flyme.") -> EffectFamily.MEIZU
            else -> EffectFamily.STANDARD
        }
    }
}
