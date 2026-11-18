package com.coloros16.liquidglass.ui.aod

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorManager
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.coloros16.liquidglass.R
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.engine.effect.EffectCatalog
import com.coloros16.liquidglass.engine.effect.EffectDef
import com.coloros16.liquidglass.engine.effect.EffectFamily
import com.coloros16.liquidglass.engine.effect.EffectKind
import com.coloros16.liquidglass.engine.effect.EffectParams
import com.coloros16.liquidglass.engine.effect.EffectResolver
import com.coloros16.liquidglass.engine.effect.EffectSettings
import com.coloros16.liquidglass.log.Lg
import kotlin.math.roundToInt

/** 页面内一个可选项。 */
data class AodOption(val value: String, @StringRes val labelRes: Int)

/** 页面内一个控件。 */
sealed class AodItem {
    class Note(@StringRes val textRes: Int) : AodItem()

    class Switch(
        @StringRes val titleRes: Int,
        @StringRes val descRes: Int?,
        val get: (EffectSettings) -> Boolean,
        val set: (EffectSettings, Boolean) -> Unit,
    ) : AodItem()

    class PrefSwitch(
        @StringRes val titleRes: Int,
        @StringRes val descRes: Int?,
        val key: String,
        val default: Boolean,
    ) : AodItem()

    class Choice(
        @StringRes val titleRes: Int,
        @StringRes val descRes: Int?,
        val options: List<AodOption>,
        val get: (EffectSettings) -> String,
        val set: (EffectSettings, String) -> Unit,
        val gate: EffectKind? = null,
        val showHeader: Boolean = true,
    ) : AodItem()

    class Param(val id: String) : AodItem()

    /** 时刻选择（分钟数 0..1439）：点开系统时间选择器，写回配置。 */
    class TimeOfDay(
        @StringRes val titleRes: Int,
        @StringRes val descRes: Int?,
        val get: (EffectSettings) -> Int,
        val set: (EffectSettings, Int) -> Unit,
    ) : AodItem()

    /** 按当前息屏样式渲染专属参数；[styleId] 为空时取配置里的样式。 */
    class StyleParams(val styleId: String?) : AodItem()

    class Styles(
        @StringRes val titleRes: Int,
        val get: (EffectSettings) -> String,
        val set: (EffectSettings, String) -> Unit,
        val showTitle: Boolean = false,
    ) : AodItem()

    /** 效果卡片网格：点选即写入；选中且有参数的卡片下方展开调整面板。 */
    class EffectGrid(
        val kind: EffectKind,
        val get: (EffectSettings) -> String,
        val set: (EffectSettings, String) -> Unit,
    ) : AodItem()

    class Nav(
        @StringRes val titleRes: Int,
        @StringRes val descRes: Int,
        val icon: Int,
        val action: AodNavAction,
    ) : AodItem()

    class Stub(@StringRes val titleRes: Int) : AodItem()

    /** 打开内联编辑面板（添加小组件）；无内联面板时退回 [fallbackScreenId] 编辑页。 */
    class EditAction(val action: String, val fallbackScreenId: String) : AodItem()

    object ResetParams : AodItem()
}

/** 行跳转动作。 */
sealed class AodNavAction {
    class Push(val screenId: String) : AodNavAction()
    object SystemWallpaper : AodNavAction()
    object SystemIcons : AodNavAction()
    object PickPhoto : AodNavAction()
    object PickFramePhoto : AodNavAction()
    object SakuraExample : AodNavAction()
}

data class AodGroup(@StringRes val titleRes: Int, val items: List<AodItem>)

data class AodPage(
    val id: String,
    @StringRes val titleRes: Int,
    val groups: List<AodGroup>,
    val styleBound: Boolean = false,
)

/**
 * 息屏 / 锁屏 / 桌面 三套设置页的声明式清单。
 *
 * 只描述「有哪些控件、改哪个字段」，渲染与持久化在 [AodSettingsController]。
 * 效果本身由 engine/effect 求值，UI 不实现任何渲染。
 */
object AodSettingsPages {

    const val PAGE_AOD_EFFECT = "aod-effect"
    const val PAGE_AOD_STYLE = "aod-style"
    const val PAGE_AOD_WALLPAPER = "aod-wallpaper"
    const val PAGE_AOD_OTHER = "aod-other"
    const val PAGE_LOCK_EFFECT = "lock-effect"
    const val PAGE_LOCK_WALLPAPER = "lock-wallpaper"
    const val PAGE_LOCK_STYLE = "lock-style"
    const val PAGE_LOCK_OTHER = "lock-other"
    const val PAGE_LOCK_EDIT = "lock-edit"
    const val PAGE_AOD_EDIT = "aod-edit"
    const val PAGE_HOME_WALLPAPER = "home-wallpaper"
    const val PAGE_HOME_STYLE = "home-style"
    const val PAGE_HOME_OTHER = "home-other"
    const val PAGE_OTHER_ALL = "other-all"
    const val PAGE_ADVANCED = "advanced"

    val ids: List<String> = listOf(
        PAGE_AOD_EFFECT, PAGE_AOD_STYLE, PAGE_AOD_WALLPAPER, PAGE_AOD_OTHER,
        PAGE_LOCK_EFFECT, PAGE_LOCK_WALLPAPER, PAGE_LOCK_STYLE, PAGE_LOCK_OTHER,
        PAGE_LOCK_EDIT, PAGE_AOD_EDIT,
        PAGE_HOME_WALLPAPER, PAGE_HOME_STYLE, PAGE_HOME_OTHER,
        PAGE_OTHER_ALL, PAGE_ADVANCED,
    )

    /** 页面 id → 标题（路由表建屏用，不依赖 Context 里已渲染的页面）。 */
    fun titleOf(id: String): Int = when (id) {
        PAGE_AOD_EFFECT -> R.string.settings_aod_row_effect
        PAGE_AOD_STYLE -> R.string.settings_aod_style_group_aod
        PAGE_AOD_WALLPAPER -> R.string.settings_aod_wallpaper
        PAGE_AOD_OTHER -> R.string.settings_aod_group_other
        PAGE_LOCK_EFFECT -> R.string.settings_aod_row_effect
        PAGE_LOCK_WALLPAPER -> R.string.settings_aod_wallpaper
        PAGE_LOCK_STYLE -> R.string.settings_aod_style_group_lock
        PAGE_LOCK_OTHER -> R.string.settings_aod_group_other
        PAGE_LOCK_EDIT -> R.string.settings_aod_edit_title_lock
        PAGE_AOD_EDIT -> R.string.settings_aod_edit_title_aod
        PAGE_HOME_WALLPAPER -> R.string.settings_aod_home_wallpaper
        PAGE_HOME_STYLE -> R.string.settings_aod_style
        PAGE_HOME_OTHER -> R.string.settings_aod_group_other
        PAGE_OTHER_ALL -> R.string.settings_aod_hub_other_row
        PAGE_ADVANCED -> R.string.settings_aod_advanced_row
        else -> R.string.settings_aod_group_other
    }

    /** 编辑页由 [AodEditController] 单独绑定，不走声明式控件清单。 */
    fun isEditPage(id: String): Boolean = id == PAGE_LOCK_EDIT || id == PAGE_AOD_EDIT

    fun of(context: Context, id: String, styleId: String? = null): AodPage? {
        val groups = when (id) {
            PAGE_AOD_EFFECT -> aodEffectGroups(styleId)
            PAGE_AOD_STYLE -> listOf(
                AodGroup(
                    R.string.settings_aod_style_group_aod,
                    listOf(
                        AodItem.Note(R.string.settings_aod_style_note),
                        AodItem.Styles(
                            R.string.settings_aod_style_group_aod,
                            { it.aodStyleId },
                            { s, v -> s.aodStyleId = v },
                        ),
                    ),
                ),
            )
            PAGE_AOD_WALLPAPER -> aodWallpaperGroups()
            PAGE_AOD_OTHER -> aodOtherGroups() + editWidgetGroup(PAGE_AOD_EDIT)
            PAGE_LOCK_EFFECT -> lockEffectGroups(context)
            PAGE_LOCK_WALLPAPER -> lockWallpaperGroups()
            PAGE_LOCK_STYLE -> listOf(
                AodGroup(
                    R.string.settings_aod_style_group_lock,
                    listOf(
                        AodItem.Note(R.string.settings_aod_style_note),
                        AodItem.Styles(
                            R.string.settings_aod_style_group_lock,
                            { it.lockStyleId },
                            { s, v -> s.lockStyleId = v },
                        ),
                    ),
                ),
            )
            PAGE_LOCK_OTHER -> lockOtherGroups() + editWidgetGroup(PAGE_LOCK_EDIT)
            PAGE_HOME_WALLPAPER -> homeWallpaperGroups()
            PAGE_HOME_STYLE -> homeStyleGroups()
            PAGE_HOME_OTHER -> homeOtherGroups()
            PAGE_OTHER_ALL -> otherAllGroups()
            PAGE_ADVANCED -> advancedGroups()
            else -> return null
        }
        return AodPage(id, titleOf(id), groups, styleBound = id == PAGE_AOD_EFFECT)
    }

    /** 预览页圆钮分类：按三态给出可用入口（息屏 4 / 锁屏 3 / 桌面 3）。 */
    fun categoriesOf(state: com.coloros16.liquidglass.engine.SceneState): List<String> =
        when (state) {
            com.coloros16.liquidglass.engine.SceneState.AOD ->
                listOf(PAGE_AOD_EFFECT, PAGE_AOD_WALLPAPER, PAGE_AOD_STYLE, PAGE_AOD_OTHER)
            com.coloros16.liquidglass.engine.SceneState.LOCK ->
                listOf(PAGE_LOCK_EFFECT, PAGE_LOCK_WALLPAPER, PAGE_LOCK_STYLE, PAGE_LOCK_OTHER)
            com.coloros16.liquidglass.engine.SceneState.HOME ->
                listOf(PAGE_HOME_WALLPAPER, PAGE_HOME_STYLE, PAGE_HOME_OTHER)
        }

    /** 圆钮标签：分类 → 展示名。 */
    fun categoryLabel(pageId: String): Int = when (pageId) {
        PAGE_AOD_EFFECT, PAGE_LOCK_EFFECT -> R.string.settings_aod_row_effect
        PAGE_AOD_WALLPAPER, PAGE_LOCK_WALLPAPER, PAGE_HOME_WALLPAPER -> R.string.settings_aod_wallpaper
        PAGE_AOD_STYLE, PAGE_LOCK_STYLE, PAGE_HOME_STYLE -> R.string.settings_aod_style
        else -> R.string.settings_aod_group_other
    }

    /** 圆钮图标：分类 → 图标资源。 */
    fun categoryIcon(pageId: String): Int = when (pageId) {
        PAGE_AOD_EFFECT, PAGE_LOCK_EFFECT -> R.drawable.ic_row_config
        PAGE_AOD_WALLPAPER, PAGE_LOCK_WALLPAPER, PAGE_HOME_WALLPAPER -> R.drawable.ic_row_image
        PAGE_AOD_STYLE, PAGE_LOCK_STYLE, PAGE_HOME_STYLE -> R.drawable.ic_row_palette
        else -> R.drawable.ic_row_list
    }

    /** 效果页：动态总开关 + 当前样式的专属参数（无参数的样式只显示总开关）。 */
    private fun aodEffectGroups(styleId: String?): List<AodGroup> = listOf(
        AodGroup(
            R.string.settings_aod_effect_group_motion,
            listOf(
                AodItem.Switch(
                    R.string.settings_aod_dynamic,
                    R.string.settings_aod_dynamic_desc,
                    { it.allowAodDynamic },
                    { s, v -> s.allowAodDynamic = v },
                ),
                AodItem.StyleParams(styleId),
                AodItem.Note(R.string.settings_aod_effect_aod_note),
            ),
        ),
    )

    /** 每个息屏样式的专属参数，对齐官方样式资源登记。 */
    fun styleParamItems(styleId: String): List<AodItem> = when (styleId) {
        AodStyleCatalog.ID_SAIL -> listOf(
            AodItem.Switch(
                R.string.settings_aod_always_play,
                R.string.settings_aod_always_play_desc,
                { it.aodAlwaysPlay },
                { s, v -> s.aodAlwaysPlay = v },
            ),
            AodItem.Param(EffectParams.AOD_COLOR_MIX),
            AodItem.Param(EffectParams.AOD_ROTATE_SPEED),
        )
        AodStyleCatalog.ID_LEAVE_LIGHT -> listOf(
            AodItem.Switch(
                R.string.settings_aod_frame_pair,
                R.string.settings_aod_frame_pair_desc,
                { it.aodFramePair },
                { s, v -> s.aodFramePair = v },
            ),
            AodItem.Nav(
                R.string.settings_aod_frame_photo,
                R.string.settings_aod_frame_photo_desc,
                R.drawable.ic_row_image,
                AodNavAction.PickFramePhoto,
            ),
            AodItem.Nav(
                R.string.settings_aod_frame_crop,
                R.string.settings_aod_frame_crop_desc,
                R.drawable.ic_row_image,
                AodNavAction.Push("aod-frame-crop"),
            ),
            AodItem.Param(EffectParams.AOD_FRAME_X),
            AodItem.Param(EffectParams.AOD_FRAME_Y),
            AodItem.Param(EffectParams.AOD_FRAME_SIZE),
            AodItem.Param(EffectParams.AOD_FRAME_ANGLE),
            AodItem.Nav(
                R.string.settings_aod_sakura_example,
                R.string.settings_aod_sakura_example_desc,
                R.drawable.ic_row_image,
                AodNavAction.SakuraExample,
            ),
            AodItem.Param(EffectParams.AOD_PHOTO_TILT),
        )
        AodStyleCatalog.ID_STARMOON,
        AodStyleCatalog.ID_DOOR,
        AodStyleCatalog.ID_MOUNTAIN,
        AodStyleCatalog.ID_GALAXY,
        -> listOf(
            AodItem.Nav(
                R.string.settings_aod_change_photo,
                R.string.settings_aod_change_photo_desc,
                R.drawable.ic_row_image,
                AodNavAction.PickPhoto,
            ),
        )
        else -> emptyList()
    }

    private fun aodWallpaperGroups(): List<AodGroup> = listOf(
        AodGroup(
            R.string.settings_aod_wallpaper,
            listOf(
                AodItem.PrefSwitch(
                    R.string.settings_aod_takeover_wallpaper,
                    R.string.settings_aod_takeover_wallpaper_desc,
                    Prefs.KEY_ALIVE_AOD_WALLPAPER,
                    Prefs.DEFAULT_ALIVE_AOD_WALLPAPER,
                ),
                AodItem.Nav(
                    R.string.settings_aod_wallpaper_pick,
                    R.string.settings_aod_wallpaper_pick_desc,
                    R.drawable.ic_row_image,
                    AodNavAction.SystemWallpaper,
                ),
            ),
        ),
    )

    private fun aodOtherGroups(): List<AodGroup> = listOf(
        AodGroup(
            R.string.settings_aod_other_group_info,
            listOf(
                AodItem.Switch(
                    R.string.settings_aod_other_battery,
                    R.string.settings_aod_other_battery_desc,
                    { it.aodInfoBattery },
                    { s, v -> s.aodInfoBattery = v },
                ),
                AodItem.Switch(
                    R.string.settings_aod_other_notif_icon,
                    R.string.settings_aod_other_notif_icon_desc,
                    { it.aodInfoNotificationIcon },
                    { s, v -> s.aodInfoNotificationIcon = v },
                ),
            ),
        ),
        AodGroup(
            R.string.settings_aod_other_group_scene,
            listOf(
                AodItem.Switch(
                    R.string.settings_aod_other_scene,
                    R.string.settings_aod_other_scene_desc,
                    { it.aodSceneInfo },
                    { s, v -> s.aodSceneInfo = v },
                ),
                AodItem.Switch(
                    R.string.settings_aod_other_new_notif,
                    R.string.settings_aod_other_new_notif_desc,
                    { it.aodNewNotification },
                    { s, v -> s.aodNewNotification = v },
                ),
            ),
        ),
        AodGroup(
            R.string.settings_aod_other_group_display,
            listOf(
                AodItem.Choice(
                    R.string.settings_aod_display_mode,
                    R.string.settings_aod_display_mode_desc,
                    listOf(
                        AodOption("always", R.string.settings_aod_mode_always),
                        AodOption("scheduled", R.string.settings_aod_mode_scheduled),
                        AodOption("power_save", R.string.settings_aod_mode_power),
                    ),
                    { it.displayMode.name.lowercase() },
                    { s, v ->
                        s.displayMode = when (v) {
                            "scheduled" -> com.coloros16.liquidglass.engine.effect.AodDisplayMode.SCHEDULED
                            "power_save" -> com.coloros16.liquidglass.engine.effect.AodDisplayMode.POWER_SAVE
                            else -> com.coloros16.liquidglass.engine.effect.AodDisplayMode.ALWAYS
                        }
                    },
                ),
                AodItem.TimeOfDay(
                    R.string.settings_aod_schedule_start,
                    R.string.settings_aod_schedule_start_desc,
                    { it.scheduleStartMinute },
                    { s, v -> s.scheduleStartMinute = v },
                ),
                AodItem.TimeOfDay(
                    R.string.settings_aod_schedule_end,
                    R.string.settings_aod_schedule_end_desc,
                    { it.scheduleEndMinute },
                    { s, v -> s.scheduleEndMinute = v },
                ),
                AodItem.Note(R.string.settings_aod_schedule_hint),
            ),
        ),
        AodGroup(
            R.string.settings_aod_other_group_glass,
            listOf(AodItem.Stub(R.string.settings_aod_glass_weak)),
        ),
    )

    private fun lockEffectGroups(context: Context): List<AodGroup> = listOf(
        AodGroup(
            R.string.settings_aod_effect_group_wall,
            listOf(
                AodItem.EffectGrid(
                    EffectKind.LOCK_WALLPAPER,
                    { it.lockWallpaperEffect },
                    { s, v -> s.lockWallpaperEffect = v },
                ),
            ),
        ),
        AodGroup(
            R.string.settings_aod_effect_group_special,
            listOf(
                AodItem.EffectGrid(
                    EffectKind.LOCK_SPECIAL,
                    { it.lockSpecialEffect },
                    { s, v -> s.lockSpecialEffect = v },
                ),
                AodItem.Note(R.string.settings_aod_effect_lock_note),
            ),
        ),
    )

    private fun editWidgetGroup(editPageId: String): List<AodGroup> = listOf(
        AodGroup(
            R.string.settings_aod_edit_widgets,
            listOf(
                AodItem.EditAction("widget_picker", "aodui/$editPageId"),
                AodItem.Nav(
                    R.string.settings_aod_edit_row,
                    if (editPageId == PAGE_AOD_EDIT) {
                        R.string.settings_aod_edit_row_desc_aod
                    } else {
                        R.string.settings_aod_edit_row_desc_lock
                    },
                    R.drawable.ic_row_config,
                    AodNavAction.Push("aodui/$editPageId"),
                ),
            ),
        ),
    )

    private fun lockWallpaperGroups(): List<AodGroup> = listOf(
        AodGroup(
            R.string.settings_aod_wallpaper,
            listOf(
                AodItem.Nav(
                    R.string.settings_aod_wallpaper_pick,
                    R.string.settings_aod_wallpaper_pick_desc,
                    R.drawable.ic_row_image,
                    AodNavAction.SystemWallpaper,
                ),
                AodItem.Switch(
                    R.string.settings_aod_home_follow_lock,
                    R.string.settings_aod_home_follow_lock_desc,
                    { it.homeFollowLock },
                    { s, v -> s.homeFollowLock = v },
                ),
            ),
        ),
    )

    private fun lockOtherGroups(): List<AodGroup> = listOf(
        AodGroup(
            R.string.settings_aod_other_group_glass,
            listOf(
                AodItem.Stub(R.string.settings_aod_glass_weak_lock),
                AodItem.Nav(
                    R.string.settings_aod_lock_glass_jump,
                    R.string.settings_aod_lock_glass_jump_desc,
                    R.drawable.ic_row_config,
                    AodNavAction.Push("glass"),
                ),
            ),
        ),
    )

    private fun homeWallpaperGroups(): List<AodGroup> = listOf(
        AodGroup(
            R.string.settings_aod_wallpaper,
            listOf(
                AodItem.Nav(
                    R.string.settings_aod_home_wallpaper,
                    R.string.settings_aod_home_wallpaper_desc,
                    R.drawable.ic_row_image,
                    AodNavAction.SystemWallpaper,
                ),
                AodItem.Switch(
                    R.string.settings_aod_home_follow_lock,
                    R.string.settings_aod_home_follow_lock_desc,
                    { it.homeFollowLock },
                    { s, v -> s.homeFollowLock = v },
                ),
            ),
        ),
    )

    private fun homeStyleGroups(): List<AodGroup> = listOf(
        AodGroup(
            R.string.settings_aod_home_group_transition,
            listOf(
                AodItem.Choice(
                    R.string.settings_aod_home_group_transition,
                    null,
                    emptyList(),
                    { it.homeTransition },
                    { s, v -> s.homeTransition = v },
                    EffectKind.HOME_TRANSITION,
                ),
                AodItem.Note(R.string.settings_aod_home_transition_note),
            ),
        ),
        AodGroup(
            R.string.settings_aod_home_group_icon,
            listOf(
                AodItem.Choice(
                    R.string.settings_aod_home_group_icon,
                    null,
                    emptyList(),
                    { it.homeIconEffect },
                    { s, v -> s.homeIconEffect = v },
                    EffectKind.HOME_ICON,
                ),
            ),
        ),
        AodGroup(
            R.string.settings_aod_home_group_aod,
            listOf(
                AodItem.Switch(
                    R.string.settings_aod_home_aod_independent,
                    R.string.settings_aod_home_aod_independent_desc,
                    { it.homeAodIndependent },
                    { s, v -> s.homeAodIndependent = v },
                ),
                AodItem.Stub(R.string.settings_aod_home_aod_style),
            ),
        ),
    )

    private fun homeOtherGroups(): List<AodGroup> = listOf(
        AodGroup(
            R.string.settings_aod_group_home,
            listOf(
                AodItem.Nav(
                    R.string.settings_aod_home_icons,
                    R.string.settings_aod_home_icons_desc,
                    R.drawable.ic_row_generic,
                    AodNavAction.SystemIcons,
                ),
            ),
        ),
    )

    private fun otherAllGroups(): List<AodGroup> = listOf(
        AodGroup(
            R.string.settings_aod_group_aod,
            aodOtherGroups().flatMap { it.items } + editWidgetGroup(PAGE_AOD_EDIT).flatMap { it.items },
        ),
        AodGroup(
            R.string.settings_aod_group_lock,
            lockOtherGroups().flatMap { it.items } + editWidgetGroup(PAGE_LOCK_EDIT).flatMap { it.items },
        ),
        AodGroup(R.string.settings_aod_group_home, homeOtherGroups().flatMap { it.items }),
        AodGroup(
            R.string.settings_aod_group_other,
            listOf(
                AodItem.Switch(
                    R.string.settings_aod_dynamic,
                    R.string.settings_aod_dynamic_desc,
                    { it.allowAodDynamic },
                    { s, v -> s.allowAodDynamic = v },
                ),
            ),
        ),
    )

    private fun advancedGroups(): List<AodGroup> {
        val out = ArrayList<AodGroup>(8)
        out.add(
            AodGroup(
                R.string.settings_aod_advanced_group_tip,
                listOf(AodItem.Note(R.string.settings_aod_advanced_warn)),
            ),
        )
        out.add(
            AodGroup(
                R.string.settings_aod_advanced_group_motion,
                listOf(
                    AodItem.Switch(
                        R.string.settings_aod_dynamic,
                        R.string.settings_aod_dynamic_desc,
                        { it.allowAodDynamic },
                        { s, v -> s.allowAodDynamic = v },
                    ),
                    AodItem.Switch(
                        R.string.settings_aod_always_play,
                        R.string.settings_aod_always_play_desc,
                        { it.aodAlwaysPlay },
                        { s, v -> s.aodAlwaysPlay = v },
                    ),
                    AodItem.Param(EffectParams.AOD_COLOR_MIX),
                    AodItem.Param(EffectParams.AOD_ROTATE_SPEED),
                    AodItem.Param(EffectParams.AOD_PHOTO_TILT),
                    AodItem.Param(EffectParams.AOD_POWER_SAVE_TIMEOUT),
                ),
            ),
        )
        out.add(
            AodGroup(
                R.string.settings_aod_advanced_group_lock_wp,
                listOf(
                    AodItem.Choice(
                        R.string.settings_aod_effect_group_wall,
                        null,
                        emptyList(),
                        { it.lockWallpaperEffect },
                        { s, v -> s.lockWallpaperEffect = v },
                        EffectKind.LOCK_WALLPAPER,
                        showHeader = false,
                    ),
                    AodItem.Param(EffectParams.LOCK_WP_SPEED),
                    AodItem.Param(EffectParams.LOCK_WP_INTENSITY),
                ),
            ),
        )
        out.add(
            AodGroup(
                R.string.settings_aod_advanced_group_lock_sp,
                listOf(
                    AodItem.Choice(
                        R.string.settings_aod_effect_group_special,
                        null,
                        emptyList(),
                        { it.lockSpecialEffect },
                        { s, v -> s.lockSpecialEffect = v },
                        EffectKind.LOCK_SPECIAL,
                        showHeader = false,
                    ),
                    AodItem.Param(EffectParams.LOCK_SP_SPEED),
                    AodItem.Param(EffectParams.LOCK_SP_INTENSITY),
                    AodItem.Param(EffectParams.LOCK_SP_GYRO_GAIN),
                ),
            ),
        )
        out.add(
            AodGroup(
                R.string.settings_aod_advanced_group_home_transition,
                listOf(
                    AodItem.Choice(
                        R.string.settings_aod_home_group_transition,
                        null,
                        emptyList(),
                        { it.homeTransition },
                        { s, v -> s.homeTransition = v },
                        EffectKind.HOME_TRANSITION,
                        showHeader = false,
                    ),
                    AodItem.Param(EffectParams.HOME_TRANSITION_DURATION),
                ),
            ),
        )
        out.add(
            AodGroup(
                R.string.settings_aod_advanced_group_home_icon,
                listOf(
                    AodItem.Choice(
                        R.string.settings_aod_home_group_icon,
                        null,
                        emptyList(),
                        { it.homeIconEffect },
                        { s, v -> s.homeIconEffect = v },
                        EffectKind.HOME_ICON,
                        showHeader = false,
                    ),
                    AodItem.Param(EffectParams.HOME_ICON_DURATION),
                    AodItem.Param(EffectParams.HOME_ICON_STAGGER),
                    AodItem.Param(EffectParams.HOME_ICON_TRAVEL),
                ),
            ),
        )
        out.add(
            AodGroup(
                R.string.settings_aod_other_group_info,
                listOf(
                    AodItem.Switch(
                        R.string.settings_aod_other_battery,
                        null,
                        { it.aodInfoBattery },
                        { s, v -> s.aodInfoBattery = v },
                    ),
                    AodItem.Switch(
                        R.string.settings_aod_other_notif_icon,
                        null,
                        { it.aodInfoNotificationIcon },
                        { s, v -> s.aodInfoNotificationIcon = v },
                    ),
                    AodItem.Switch(
                        R.string.settings_aod_other_scene,
                        null,
                        { it.aodSceneInfo },
                        { s, v -> s.aodSceneInfo = v },
                    ),
                    AodItem.Switch(
                        R.string.settings_aod_other_new_notif,
                        null,
                        { it.aodNewNotification },
                        { s, v -> s.aodNewNotification = v },
                    ),
                    AodItem.Choice(
                        R.string.settings_aod_display_mode,
                        null,
                        listOf(
                            AodOption("always", R.string.settings_aod_mode_always),
                            AodOption("scheduled", R.string.settings_aod_mode_scheduled),
                            AodOption("power_save", R.string.settings_aod_mode_power),
                        ),
                        { it.displayMode.name.lowercase() },
                        { s, v ->
                            s.displayMode = when (v) {
                                "scheduled" -> com.coloros16.liquidglass.engine.effect.AodDisplayMode.SCHEDULED
                                "power_save" -> com.coloros16.liquidglass.engine.effect.AodDisplayMode.POWER_SAVE
                                else -> com.coloros16.liquidglass.engine.effect.AodDisplayMode.ALWAYS
                            }
                        },
                    ),
                    AodItem.TimeOfDay(
                        R.string.settings_aod_schedule_start,
                        R.string.settings_aod_schedule_start_desc,
                        { it.scheduleStartMinute },
                        { s, v -> s.scheduleStartMinute = v },
                    ),
                    AodItem.TimeOfDay(
                        R.string.settings_aod_schedule_end,
                        R.string.settings_aod_schedule_end_desc,
                        { it.scheduleEndMinute },
                        { s, v -> s.scheduleEndMinute = v },
                    ),
                ),
            ),
        )
        out.add(AodGroup(R.string.settings_aod_advanced_reset, listOf(AodItem.ResetParams)))
        return out
    }

    /** 效果清单 → 选项；标签取 EffectDef.nameKey 对应的字符串资源。 */
    private fun effectOptions(context: Context, kind: EffectKind): List<AodOption> =
        EffectCatalog.of(kind).map { AodOption(it.id, labelOf(context, it)) }

    private fun labelOf(context: Context, def: EffectDef): Int {
        val res = context.resources.getIdentifier(def.nameKey, "string", context.packageName)
        return if (res != 0) res else android.R.string.ok
    }

    /** 家族展示名。 */
    fun familyTitle(family: EffectFamily): Int = when (family) {
        EffectFamily.PANORAMA -> R.string.settings_aod_style_family_panorama
        EffectFamily.MEIZU -> R.string.settings_aod_style_family_meizu
        EffectFamily.SCENE_3D -> R.string.settings_aod_style_family_scene3d
        EffectFamily.OPLUS -> R.string.settings_aod_style_family_oplus
        EffectFamily.STANDARD -> R.string.settings_aod_style_family_standard
    }
}

/**
 * 设置页渲染：按 [AodPage] 建控件、读改 [EffectSettings]、写回配置文本。
 *
 * 每次选择后整页重建（控件量小），重建前记滚动位置，重建后还原。
 */
class AodSettingsController(
    private val context: Context,
    private val root: View,
    private val page: AodPage,
    private val readSettings: () -> EffectSettings,
    private val writeSettings: (EffectSettings) -> Unit,
    private val readBool: (String, Boolean) -> Boolean,
    private val writeBool: (String, Boolean) -> Unit,
    private val push: (String) -> Unit,
    private val scene3dActive: () -> Boolean = { false },
    private val onChanged: (() -> Unit)? = null,
    /** 预览面板用的样式覆盖（草稿 id）；null=读配置里的已应用样式。 */
    private val styleOverride: String? = null,
    private val editAction: ((String) -> Unit)? = null,
    private val pickPhoto: ((frame: Boolean) -> Unit)? = null,
) {

    private companion object {
        const val TAG = "LgAodUi"
    }

    private val density = context.resources.displayMetrics.density
    private val ink100 = context.getColor(R.color.lg_ink_100)
    private val ink300 = context.getColor(R.color.lg_ink_300)
    private val ink400 = context.getColor(R.color.lg_ink_400)
    private val brass = context.getColor(R.color.lg_brass)
    private val blued = context.getColor(R.color.lg_blued)
    private val bluedInk = context.getColor(R.color.lg_blued_ink)

    private lateinit var container: LinearLayout
    private var settings = EffectSettings.defaults()

    private val hasGyro: Boolean by lazy {
        val sensors = context.getSystemService(SensorManager::class.java) ?: return@lazy false
        sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
    }

    fun bind() {
        container = root.findViewById(R.id.aodSettingsContainer)
            ?: return missing("aodSettingsContainer")
        settings = readSettings()
        rebuild()
        Lg.i(TAG, "aod settings page=${page.id} groups=${page.groups.size} dynamic=${settings.allowAodDynamic}")
    }

    fun refresh() {
        if (!::container.isInitialized) return
        bind()
    }

    private fun rebuild() {
        val scroll = (root as? ScrollView)
        val keep = scroll?.scrollY ?: 0
        container.removeAllViews()
        for (group in page.groups) {
            container.addView(groupHead(group.titleRes))
            val plate = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_plate)
                dividerDrawable = ContextCompat.getDrawable(context, R.drawable.bg_seam_line_inset)
                showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            }
            for (item in group.items) {
                buildItem(plate, item, group.titleRes)
            }
            container.addView(
                plate,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        if (keep > 0) container.post { scroll?.scrollTo(0, keep) }
    }

    private fun buildItem(plate: LinearLayout, item: AodItem, groupTitle: Int) {
        when (item) {
            is AodItem.Note -> plate.addView(noteRow(item.textRes))
            is AodItem.Switch -> plate.addView(switchRow(item.titleRes, item.descRes, item.get(settings)) { on ->
                item.set(settings, on)
                persist(allowChanged = item.titleRes == R.string.settings_aod_dynamic)
            })
            is AodItem.PrefSwitch -> plate.addView(
                switchRow(item.titleRes, item.descRes, readBool(item.key, item.default)) { on ->
                    writeBool(item.key, on)
                    Lg.i(TAG, "aod pref ${item.key}=$on")
                },
            )
            is AodItem.Choice -> {
                if (item.showHeader && item.titleRes != groupTitle) {
                    plate.addView(headerRow(item.titleRes, item.descRes, null))
                }
                val current = item.get(settings)
                val gate = item.gate
                val options = if (item.options.isEmpty() && gate != null) {
                    optionsOf(gate)
                } else {
                    item.options
                }
                for (option in options) {
                    val sceneBlocked = sceneBlockedOption(option.value, gate)
                    plate.addView(choiceRow(option, option.value == current, gate, sceneBlocked) {
                        if (sceneBlocked) {
                            Toast.makeText(context, R.string.settings_aod_gate_scene3d, Toast.LENGTH_SHORT).show()
                            return@choiceRow
                        }
                        item.set(settings, option.value)
                        persist()
                    })
                }
            }
            is AodItem.Param -> plate.addView(paramRow(item.id))
            is AodItem.TimeOfDay -> plate.addView(timeRow(item))
            is AodItem.EffectGrid -> {
                plate.addView(effectGrid(item))
                val selectedId = item.get(settings)
                val params = effectParamIds(item.kind, selectedId)
                if (params.isNotEmpty()) {
                    val nameRes = effectNameRes(selectedId)
                    plate.addView(
                        headerRow(
                            R.string.settings_aod_effect_adjust_head,
                            null,
                            if (nameRes != 0) context.getString(nameRes) else selectedId,
                        ),
                    )
                    for (paramId in params) plate.addView(paramRow(paramId))
                }
            }
            is AodItem.StyleParams -> {
                val styleId = item.styleId ?: styleOverride ?: settings.aodStyleId
                val items = AodSettingsPages.styleParamItems(styleId)
                if (items.isEmpty()) {
                    plate.addView(noteRow(R.string.settings_aod_effect_style_none))
                } else {
                    plate.addView(
                        headerRow(R.string.settings_aod_effect_style_head, null, context.getString(AodStyleCatalog.find(styleId).nameRes)),
                    )
                    for (child in items) buildItem(plate, child, groupTitle)
                }
            }
            is AodItem.Styles -> plate.addView(
                styleGrid(item.titleRes, item.get(settings), item.showTitle) { id ->
                    item.set(settings, id)
                    persist()
                    Lg.i(TAG, "aod style -> $id")
                },
            )
            is AodItem.Nav -> plate.addView(navRow(item))
            is AodItem.EditAction -> {
                val handler = editAction
                if (handler == null) {
                    // 独立设置页没有内联面板：退回编辑页入口，不做无反应的按钮
                    plate.addView(
                        navRow(
                            AodItem.Nav(
                                R.string.settings_aod_edit_widget_picker,
                                R.string.settings_aod_edit_widget_picker_desc,
                                R.drawable.ic_row_config,
                                AodNavAction.Push(item.fallbackScreenId),
                            ),
                        ),
                    )
                } else {
                    plate.addView(actionRow(R.string.settings_aod_edit_widget_picker) { handler(item.action) })
                }
            }
            is AodItem.Stub -> plate.addView(stubRow(item.titleRes))
            AodItem.ResetParams -> plate.addView(actionRow(R.string.settings_aod_advanced_reset) {
                settings.resetAllParams()
                persist()
                Toast.makeText(context, R.string.settings_aod_advanced_reset_done, Toast.LENGTH_SHORT).show()
            })
        }
    }

    private fun optionsOf(kind: EffectKind): List<AodOption> =
        EffectCatalog.of(kind).map { def ->
            val res = context.resources.getIdentifier(def.nameKey, "string", context.packageName)
            AodOption(def.id, if (res != 0) res else android.R.string.ok)
        }

    private fun persist(allowChanged: Boolean = false) {
        writeSettings(settings)
        if (allowChanged) Lg.i(TAG, "aod dynamic=${settings.allowAodDynamic}")
        container.post { rebuild() }
        onChanged?.invoke()
    }

    /** 主题下的点按水波纹底（attr 不能直接当 drawable 资源 id 用）。 */
    private fun selectable(): android.graphics.drawable.Drawable? {
        val attrs = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        return try {
            attrs.getDrawable(0)
        } finally {
            attrs.recycle()
        }
    }

    // ---------------------------------------------------------------- 控件构建

    private fun groupHead(@StringRes titleRes: Int): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(4f), dpi(22f), dpi(4f), dpi(9f))
            addView(
                TextView(context).apply {
                    text = context.getString(titleRes)
                    letterSpacing = 0.22f
                    setTextColor(brass)
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                },
            )
            addView(
                View(context).apply { setBackgroundResource(R.drawable.bg_rule_brass) },
                LinearLayout.LayoutParams(0, dpi(1f), 1f).apply { marginStart = dpi(12f) },
            )
        }

    private fun headerRow(@StringRes titleRes: Int, @StringRes descRes: Int?, value: String?): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(16f), dpi(12f), dpi(16f), dpi(6f))
        }
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        line.addView(
            TextView(context).apply {
                text = context.getString(titleRes)
                setTextColor(ink100)
                textSize = 15f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (!value.isNullOrEmpty()) {
            line.addView(
                TextView(context).apply {
                    text = value
                    setTextColor(bluedInk)
                    textSize = 12.5f
                },
            )
        }
        box.addView(line)
        if (descRes != null) box.addView(descView(descRes))
        return box
    }

    private fun switchRow(
        @StringRes titleRes: Int,
        @StringRes descRes: Int?,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
    ): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(16f), dpi(10f), dpi(16f), dpi(10f))
        }
        val toggle = Switch(context).apply {
            text = context.getString(titleRes)
            setTextColor(ink100)
            textSize = 15f
            minHeight = 0
            setThumbDrawable(ContextCompat.getDrawable(context, R.drawable.lg_sw_thumb))
            setTrackDrawable(ContextCompat.getDrawable(context, R.drawable.lg_sw_track))
            isChecked = checked
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }
        box.addView(toggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (descRes != null) box.addView(descView(descRes))
        return box
    }

    private fun choiceRow(
        option: AodOption,
        selected: Boolean,
        gate: EffectKind?,
        sceneBlocked: Boolean,
        onClick: () -> Unit,
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(16f), dpi(10f), dpi(16f), dpi(10f))
            background = selectable()
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        row.addView(
            TextView(context).apply {
                text = context.getString(option.labelRes)
                setTextColor(
                    when {
                        sceneBlocked -> ink400
                        selected -> bluedInk
                        else -> ink100
                    },
                )
                textSize = 14.5f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (sceneBlocked) {
            row.addView(chip(context.getString(R.string.settings_aod_gate_scene3d), ink400))
        } else {
            gateBlockReason(option.value, gate)?.let { reason ->
                row.addView(chip(context.getString(gateTextRes(reason)), ink400))
            }
        }
        if (selected) row.addView(chip("✓", bluedInk))
        return row
    }

    /** 时刻行：右侧显示 HH:mm，点开系统时间选择器；仅在定时档下有意义。 */
    private fun timeRow(item: AodItem.TimeOfDay): View {
        val minute = item.get(settings).coerceIn(0, 24 * 60 - 1)
        val label = String.format(java.util.Locale.US, "%02d:%02d", minute / 60, minute % 60)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(16f), dpi(12f), dpi(16f), dpi(12f))
            background = selectable()
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val cur = item.get(settings).coerceIn(0, 24 * 60 - 1)
                android.app.TimePickerDialog(
                    context,
                    { _, h, m ->
                        item.set(settings, h * 60 + m)
                        persist()
                    },
                    cur / 60,
                    cur % 60,
                    true,
                ).show()
            }
        }
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(
            TextView(context).apply {
                text = context.getString(item.titleRes)
                setTextColor(ink100)
                textSize = 14.5f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        item.descRes?.let { d ->
            texts.addView(
                TextView(context).apply {
                    text = context.getString(d)
                    setTextColor(ink400)
                    textSize = 12f
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                },
            )
        }
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(
            TextView(context).apply {
                text = label
                setTextColor(bluedInk)
                textSize = 15f
                setBackgroundResource(R.drawable.bg_chip)
                setPadding(dpi(10f), dpi(5f), dpi(10f), dpi(5f))
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                lp.marginStart = dpi(8f)
                layoutParams = lp
            },
        )
        return row
    }

    /** 3D 场景壁纸下贴图类壁纸效果不可用（贴图叠不进连续场景）；「无」始终可选。 */
    private fun sceneBlockedOption(id: String, kind: EffectKind?): Boolean {
        if (kind != EffectKind.LOCK_WALLPAPER) return false
        if (!scene3dActive()) return false
        val def = EffectCatalog.find(id) ?: return false
        return def.textureBased
    }

    /** 选中效果对应的参数：无效果=无参数；壁纸类=速度+强度；特殊类=速度+强度（+需陀螺仪时的灵敏度）。 */
    private fun effectParamIds(kind: EffectKind, selectedId: String): List<String> {
        val def = EffectCatalog.find(selectedId) ?: return emptyList()
        if (def.kind != kind) return emptyList()
        return when (kind) {
            EffectKind.LOCK_WALLPAPER ->
                if (selectedId == EffectCatalog.LOCK_WP_NONE) emptyList()
                else listOf(EffectParams.LOCK_WP_SPEED, EffectParams.LOCK_WP_INTENSITY)
            EffectKind.LOCK_SPECIAL ->
                if (selectedId == EffectCatalog.LOCK_SP_NONE) emptyList()
                else buildList {
                    add(EffectParams.LOCK_SP_SPEED)
                    add(EffectParams.LOCK_SP_INTENSITY)
                    if (def.requiresGyro) add(EffectParams.LOCK_SP_GYRO_GAIN)
                }
            EffectKind.HOME_TRANSITION ->
                if (selectedId == EffectCatalog.HOME_TRANSITION_NONE) emptyList()
                else listOf(EffectParams.HOME_TRANSITION_DURATION)
            EffectKind.HOME_ICON -> listOf(
                EffectParams.HOME_ICON_DURATION,
                EffectParams.HOME_ICON_STAGGER,
                EffectParams.HOME_ICON_TRAVEL,
            )
            EffectKind.AOD_STYLE -> emptyList()
        }
    }

    private fun effectNameRes(id: String): Int {
        val def = EffectCatalog.find(id) ?: return 0
        return context.resources.getIdentifier(def.nameKey, "string", context.packageName)
    }

    /** 效果卡片网格：每行 3 张，点选即写入；门槛项灰显带原因。 */
    private fun effectGrid(item: AodItem.EffectGrid): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(12f), dpi(10f), dpi(12f), dpi(12f))
        }
        val current = item.get(settings)
        val defs = EffectCatalog.of(item.kind)
        var row: LinearLayout? = null
        for ((index, def) in defs.withIndex()) {
            if (index % 3 == 0) {
                row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                box.addView(
                    row,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = if (index == 0) 0 else dpi(10f)
                    },
                )
            }
            row!!.addView(effectCard(def, item, def.id == current))
        }
        val tail = defs.size % 3
        if (tail != 0 && row != null) {
            repeat(3 - tail) {
                row.addView(
                    View(context),
                    LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dpi(5f); marginEnd = dpi(5f) },
                )
            }
        }
        return box
    }

    private fun effectCard(def: EffectDef, item: AodItem.EffectGrid, selected: Boolean): View {
        val nameRes = context.resources.getIdentifier(def.nameKey, "string", context.packageName)
        val label = if (nameRes != 0) context.getString(nameRes) else def.id
        val sceneBlocked = sceneBlockedOption(def.id, item.kind)
        val gateReason = if (sceneBlocked) null else gateBlockReason(def.id, item.kind)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dpi(5f)
                marginEnd = dpi(5f)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(14f)
                setColor(if (selected) 0x263E6FD1 else 0x14FFFFFF)
                setStroke(dpi(if (selected) 1.5f else 1f), if (selected) blued else 0x14FFFFFF)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (sceneBlocked) {
                    Toast.makeText(context, R.string.settings_aod_gate_scene3d, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                item.set(settings, def.id)
                persist()
                Lg.i(TAG, "effect grid ${item.kind} -> ${def.id}")
            }
        }
        card.addView(
            AodEffectThumbView(context).apply { effectId = def.id },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(64f)).apply {
                setMargins(dpi(6f), dpi(6f), dpi(6f), 0)
            },
        )
        card.addView(
            TextView(context).apply {
                text = label
                textSize = 11.5f
                gravity = Gravity.CENTER
                setTextColor(if (sceneBlocked) ink400 else ink100)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dpi(2f), dpi(6f), dpi(2f), if (gateReason == null && !sceneBlocked) dpi(8f) else dpi(2f))
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        if (sceneBlocked || gateReason != null) {
            val reasonText = if (sceneBlocked) R.string.settings_aod_gate_scene3d else gateTextRes(gateReason!!)
            card.addView(
                TextView(context).apply {
                    text = context.getString(reasonText)
                    textSize = 9f
                    gravity = Gravity.CENTER
                    setTextColor(ink400)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(dpi(2f), 0, dpi(2f), dpi(6f))
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        return card
    }

    private fun paramRow(paramId: String): View {
        val spec = EffectParams.find(paramId)
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(16f), dpi(10f), dpi(16f), dpi(12f))
        }
        if (spec == null) {
            box.addView(TextView(context).apply { text = paramId; setTextColor(ink400); textSize = 13f })
            return box
        }
        val labelRes = context.resources.getIdentifier(spec.labelKey, "string", context.packageName)
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        line.addView(
            TextView(context).apply {
                text = if (labelRes != 0) context.getString(labelRes) else spec.id
                setTextColor(ink100)
                textSize = 14.5f
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val valueView = TextView(context).apply {
            text = formatParam(spec.id, settings.param(spec.id))
            setTextColor(bluedInk)
            textSize = 12.5f
        }
        line.addView(valueView)
        box.addView(line)

        val steps = ((spec.max - spec.min) / spec.step).roundToInt().coerceAtLeast(1)
        val seek = SeekBar(context).apply {
            max = steps
            progress = (((settings.param(spec.id) - spec.min) / spec.step).roundToInt()).coerceIn(0, steps)
            progressDrawable = ContextCompat.getDrawable(context, R.drawable.lg_seek_track)
            thumb = ContextCompat.getDrawable(context, R.drawable.lg_seek_thumb)
            splitTrack = false
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    valueView.text = formatParam(spec.id, spec.min + progress * spec.step)
                }

                override fun onStartTrackingTouch(bar: SeekBar?) = Unit

                override fun onStopTrackingTouch(bar: SeekBar?) {
                    val value = spec.min + (bar?.progress ?: 0) * spec.step
                    settings.setParam(spec.id, EffectParams.quantize(spec.id, value))
                    persist()
                }
            })
        }
        box.addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return box
    }

    private fun styleGrid(
        @StringRes titleRes: Int,
        currentId: String,
        showTitle: Boolean,
        onPick: (String) -> Unit,
    ): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(16f), dpi(10f), dpi(16f), dpi(12f))
        }
        if (showTitle) {
            box.addView(
                TextView(context).apply {
                    text = context.getString(titleRes)
                    setTextColor(ink100)
                    textSize = 15f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                },
            )
        }
        val families = listOf(
            EffectFamily.PANORAMA, EffectFamily.MEIZU, EffectFamily.SCENE_3D,
            EffectFamily.OPLUS, EffectFamily.STANDARD,
        )
        for (family in families) {
            val styles = AodStyleCatalog.styles.filter {
                com.coloros16.liquidglass.engine.effect.StyleFamilyMap.familyOf(it.id) == family
            }
            if (styles.isEmpty()) continue
            box.addView(
                TextView(context).apply {
                    text = context.getString(AodSettingsPages.familyTitle(family))
                    setTextColor(ink400)
                    textSize = 11.5f
                    letterSpacing = 0.06f
                    setPadding(0, dpi(10f), 0, dpi(6f))
                },
            )
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                clipToPadding = false
            }
            for (style in styles) {
                row.addView(styleCard(style, style.id == currentId) { onPick(style.id) })
            }
            val scroller = android.widget.HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                clipToPadding = false
                addView(row)
            }
            box.addView(scroller)
        }
        return box
    }

    private fun styleCard(style: AodStyle, selected: Boolean, onClick: () -> Unit): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(6f), dpi(6f), dpi(6f), dpi(6f))
            background = GradientDrawable().apply {
                cornerRadius = dp(16f)
                setColor(if (selected) 0x263E6FD1 else 0x14FFFFFF)
                setStroke(dpi(if (selected) 1.5f else 1f), if (selected) blued else 0x14FFFFFF)
            }
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(dpi(88f), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dpi(10f)
            }
            setOnClickListener { onClick() }
        }
        card.addView(
            AodStyleThumbView(context).apply { this.style = style },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(100f)),
        )
        card.addView(
            TextView(context).apply {
                text = context.getString(style.nameRes)
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(ink100)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dpi(2f), dpi(8f), dpi(2f), dpi(2f))
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        return card
    }

    private fun navRow(item: AodItem.Nav): View {
        val row = android.view.LayoutInflater.from(context).inflate(R.layout.item_row, container, false)
        row.findViewById<TextView>(R.id.rowTitle)?.text = context.getString(item.titleRes)
        row.findViewById<TextView>(R.id.rowDesc)?.apply {
            text = context.getString(item.descRes)
            visibility = View.VISIBLE
        }
        row.findViewById<android.widget.ImageView>(R.id.rowIcon)?.apply {
            setImageResource(item.icon)
            setColorFilter(bluedInk)
        }
        row.setOnClickListener { onNav(item.action) }
        return row
    }

    private fun stubRow(@StringRes titleRes: Int): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(16f), dpi(12f), dpi(16f), dpi(12f))
        }
        row.addView(
            TextView(context).apply {
                text = context.getString(titleRes)
                setTextColor(ink300)
                textSize = 14.5f
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(chip(context.getString(R.string.settings_aod_not_implemented), ink400))
        return row
    }

    private fun actionRow(@StringRes titleRes: Int, onClick: () -> Unit): View {
        val row = TextView(context).apply {
            text = context.getString(titleRes)
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(bluedInk)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dpi(16f), dpi(14f), dpi(16f), dpi(14f))
            isClickable = true
            isFocusable = true
            background = selectable()
            setOnClickListener { onClick() }
        }
        return row
    }

    private fun noteRow(@StringRes textRes: Int): View =
        TextView(context).apply {
            text = context.getString(textRes)
            textSize = 12f
            setTextColor(ink400)
            setPadding(dpi(16f), dpi(10f), dpi(16f), dpi(12f))
        }

    private fun descView(@StringRes descRes: Int): TextView =
        TextView(context).apply {
            text = context.getString(descRes)
            textSize = 12f
            setTextColor(ink400)
            setPadding(0, dpi(2f), 0, 0)
        }

    private fun chip(text: String, color: Int): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 10.5f
            setTextColor(color)
            setBackgroundResource(R.drawable.bg_chip)
            setPadding(dpi(7f), dpi(3f), dpi(7f), dpi(3f))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            lp.marginStart = dpi(6f)
            layoutParams = lp
        }

    // ---------------------------------------------------------------- 行为

    private fun onNav(action: AodNavAction) {
        when (action) {
            is AodNavAction.Push -> push(action.screenId)
            AodNavAction.SystemWallpaper -> launch(
                Intent(Intent.ACTION_SET_WALLPAPER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            AodNavAction.SystemIcons -> launch(
                Intent().setClassName("com.android.launcher", "com.oplus.uxdesign.icon.UxIconStyleActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) {
                launch(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            AodNavAction.PickPhoto -> {
                val picker = pickPhoto
                if (picker != null) picker(false) else launch(
                    Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            AodNavAction.PickFramePhoto -> {
                val picker = pickPhoto
                if (picker != null) picker(true) else launch(
                    Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            AodNavAction.SakuraExample -> {
                settings.aodFramePhoto = "sakura"
                persist()
                Toast.makeText(context, R.string.settings_aod_sakura_applied, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun launch(intent: Intent, fallback: (() -> Unit)? = null) {
        try {
            context.startActivity(intent)
            Lg.i(TAG, "aod nav ${intent.action ?: intent.component}")
        } catch (t: Throwable) {
            Lg.w(TAG, "aod nav failed: $intent", t)
            if (fallback != null) {
                fallback()
            } else {
                Toast.makeText(context, R.string.settings_aod_page_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun gateBlockReason(id: String, kind: EffectKind?): com.coloros16.liquidglass.engine.effect.EffectBlockReason? {
        if (kind == null) return null
        val resolved = EffectResolver.resolveOne(
            id, kind, id,
            settings.allowAodDynamic,
            settings.aodStyleId == AodStyleCatalog.ID_FULLSCREEN,
            hasGyro,
            false,
        )
        return resolved.reason.takeIf { it != com.coloros16.liquidglass.engine.effect.EffectBlockReason.NONE }
    }

    private fun gateTextRes(reason: com.coloros16.liquidglass.engine.effect.EffectBlockReason): Int = when (reason) {
        com.coloros16.liquidglass.engine.effect.EffectBlockReason.UNIMPLEMENTED -> R.string.settings_aod_gate_unimplemented
        com.coloros16.liquidglass.engine.effect.EffectBlockReason.NEEDS_AOD_DYNAMIC -> R.string.settings_aod_gate_dynamic
        com.coloros16.liquidglass.engine.effect.EffectBlockReason.NEEDS_PANORAMA_AOD -> R.string.settings_aod_gate_panorama
        com.coloros16.liquidglass.engine.effect.EffectBlockReason.NEEDS_GYRO -> R.string.settings_aod_gate_gyro
        com.coloros16.liquidglass.engine.effect.EffectBlockReason.NEEDS_PHOTO_SCENE -> R.string.settings_aod_gate_photo
        else -> R.string.settings_aod_gate_unimplemented
    }

    private fun formatParam(id: String, value: Float): String {
        val spec = EffectParams.find(id) ?: return value.toString()
        val text = if (spec.step < 1f) String.format("%.1f", value) else value.roundToInt().toString()
        return when (id) {
            EffectParams.AOD_COLOR_MIX,
            EffectParams.AOD_FRAME_X,
            EffectParams.AOD_FRAME_Y,
            EffectParams.AOD_FRAME_SIZE,
            EffectParams.LOCK_WP_INTENSITY,
            EffectParams.LOCK_SP_INTENSITY,
            EffectParams.LOCK_SP_GYRO_GAIN,
            EffectParams.HOME_ICON_TRAVEL,
            -> context.getString(R.string.settings_aod_value_suffix_percent, text)

            EffectParams.AOD_ROTATE_SPEED, EffectParams.AOD_PHOTO_TILT, EffectParams.AOD_FRAME_ANGLE ->
                context.getString(R.string.settings_aod_value_suffix_deg, text)

            EffectParams.AOD_POWER_SAVE_TIMEOUT -> context.getString(R.string.settings_aod_value_suffix_s, text)
            EffectParams.LOCK_WP_SPEED, EffectParams.LOCK_SP_SPEED ->
                context.getString(R.string.settings_aod_value_suffix_x, text)
            else -> context.getString(R.string.settings_aod_value_suffix_ms, text)
        }
    }

    private fun missing(name: String) {
        Lg.w(TAG, "aod settings missing view $name")
    }

    private fun dp(value: Float): Float = value * density

    private fun dpi(value: Float): Int = (value * density).toInt()
}
