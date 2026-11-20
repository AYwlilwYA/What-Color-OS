package com.coloros16.liquidglass.engine.effect

/** 效果被拦住的原因；渲染侧据此回退并给用户提示。 */
enum class EffectBlockReason {
    NONE,
    UNKNOWN_ID,
    UNIMPLEMENTED,
    NEEDS_AOD_DYNAMIC,
    NEEDS_PANORAMA_AOD,
    NEEDS_GYRO,
    NEEDS_PHOTO_SCENE,
}

/** 一个槽位的解析结果。 */
data class ResolvedEffect(
    val requestedId: String,
    val effectiveId: String,
    val reason: EffectBlockReason,
) {
    /** 门槛全部通过（不代表一定会画东西，见 [noop]）。 */
    val applied: Boolean get() = reason == EffectBlockReason.NONE

    /** 有效 id 是「无效果」本身。 */
    val noop: Boolean
        get() = effectiveId == EffectCatalog.LOCK_WP_NONE ||
            effectiveId == EffectCatalog.LOCK_SP_NONE ||
            effectiveId == EffectCatalog.HOME_TRANSITION_NONE
}

/** 三态解析后的实际生效配置。 */
class ResolvedEffects(
    val aodStyleId: String,
    val lockStyleId: String,
    val lockWallpaper: ResolvedEffect,
    val lockSpecial: ResolvedEffect,
    val homeTransition: ResolvedEffect,
    val homeIcon: ResolvedEffect,
    val homeAodStyleId: String,
    val homeUsesAodStyle: Boolean,
)

/**
 * 能力门槛解析：把用户选择 + 设备能力 → 实际生效效果 + 被拦原因。
 *
 * 判定顺序固定：未知 id → 未实现 → 需要 AOD 动态 → 需要全景息屏 → 需要陀螺仪 → 需要图片场景。
 * 先报最根本的阻塞原因，UI 可直接按此灰显并提示。
 */
object EffectResolver {

    /**
     * 三态解析：能力参必传，漏传编译不过。
     *
     * 曾给三个能力参留 `= false` 默认值，hook 侧只传 settings 就编译过，
     * 等于静默宣称「本机没陀螺仪、不是全景息屏」，与设置页真值判定矛盾。
     */
    fun resolve(settings: EffectSettings, capabilities: EffectCapabilities): ResolvedEffects {
        val dynamicAllowed = settings.allowAodDynamic
        val panoramaAodActive = capabilities.panoramaAodActive
        val hasGyro = capabilities.hasGyro
        val photoSceneAvailable = capabilities.photoSceneAvailable
        val lockWallpaper = resolveOne(
            settings.lockWallpaperEffect,
            EffectKind.LOCK_WALLPAPER,
            EffectCatalog.LOCK_WP_NONE,
            dynamicAllowed, panoramaAodActive, hasGyro, photoSceneAvailable,
        )
        val lockSpecial = resolveOne(
            settings.lockSpecialEffect,
            EffectKind.LOCK_SPECIAL,
            EffectCatalog.LOCK_SP_NONE,
            dynamicAllowed, panoramaAodActive, hasGyro, photoSceneAvailable,
        )
        val homeTransition = resolveOne(
            settings.homeTransition,
            EffectKind.HOME_TRANSITION,
            EffectCatalog.HOME_TRANSITION_NONE,
            dynamicAllowed, panoramaAodActive, hasGyro, photoSceneAvailable,
        )
        val homeIcon = resolveOne(
            settings.homeIconEffect,
            EffectKind.HOME_ICON,
            EffectCatalog.HOME_ICON_CROSSFADE,
            dynamicAllowed, panoramaAodActive, hasGyro, photoSceneAvailable,
        )
        return ResolvedEffects(
            aodStyleId = settings.aodStyleId,
            lockStyleId = settings.lockStyleId,
            lockWallpaper = lockWallpaper,
            lockSpecial = lockSpecial,
            homeTransition = homeTransition,
            homeIcon = homeIcon,
            homeAodStyleId = if (settings.homeAodIndependent) settings.homeAodStyleId else settings.aodStyleId,
            homeUsesAodStyle = !settings.homeAodIndependent,
        )
    }

    /** 单个槽位解析；未知 id 或不属于该类别时按未知处理。 */
    fun resolveOne(
        requestedId: String,
        kind: EffectKind,
        fallbackId: String,
        aodDynamicAllowed: Boolean,
        panoramaAodActive: Boolean,
        hasGyro: Boolean,
        photoSceneAvailable: Boolean,
    ): ResolvedEffect {
        val def = EffectCatalog.find(requestedId)
        if (def == null || def.kind != kind) {
            return ResolvedEffect(requestedId, fallbackId, EffectBlockReason.UNKNOWN_ID)
        }
        if (!def.implemented) {
            return ResolvedEffect(requestedId, fallbackId, EffectBlockReason.UNIMPLEMENTED)
        }
        if (def.requiresAodDynamic && !aodDynamicAllowed) {
            return ResolvedEffect(requestedId, fallbackId, EffectBlockReason.NEEDS_AOD_DYNAMIC)
        }
        if (def.requiresPanoramaAod && !panoramaAodActive) {
            return ResolvedEffect(requestedId, fallbackId, EffectBlockReason.NEEDS_PANORAMA_AOD)
        }
        if (def.requiresGyro && !hasGyro) {
            return ResolvedEffect(requestedId, fallbackId, EffectBlockReason.NEEDS_GYRO)
        }
        if (def.requiresPhotoScene && !photoSceneAvailable) {
            return ResolvedEffect(requestedId, fallbackId, EffectBlockReason.NEEDS_PHOTO_SCENE)
        }
        return ResolvedEffect(requestedId, requestedId, EffectBlockReason.NONE)
    }
}
