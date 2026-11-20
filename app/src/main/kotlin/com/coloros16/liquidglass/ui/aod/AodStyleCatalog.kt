package com.coloros16.liquidglass.ui.aod

import androidx.annotation.StringRes
import com.coloros16.liquidglass.R
import com.coloros16.liquidglass.config.Prefs

/** 息屏样式的形态大分类。 */
enum class AodForm { FULL, PARTIAL }

/** 样式声明的过渡类型：遮罩揭开 / 内容交叠 / 连续场景。 */
enum class AodTransition { MASK, CONTENT, SCENE }

/** 一个可选息屏样式。 */
data class AodStyle(
    val id: String,
    @StringRes val nameRes: Int,
    val form: AodForm,
    val accent: Int,
    val transition: AodTransition = AodTransition.MASK,
    val maskCenterX: Float = 0.5f,
    val maskCenterY: Float = 0.5f,
)

/** 息屏样式目录：稳定 id + 展示名 + 形态 + 主色。 */
object AodStyleCatalog {

    const val ID_FULLSCREEN = "aod.fullscreen"
    const val ID_HANDPAINT = "aod.handpaint"
    const val ID_TEXT = "aod.text"
    const val ID_PORTRAIT = "aod.portrait"
    const val ID_SAIL = "aod.sail"
    const val ID_STARMOON = "aod.starmoon"
    const val ID_LEAVE_LIGHT = "aod.leave_light"
    const val ID_DOOR = "aod.door"
    const val ID_MOUNTAIN = "aod.mountain"
    const val ID_GALAXY = "aod.galaxy"

    val styles: List<AodStyle> = listOf(
        AodStyle(ID_FULLSCREEN, R.string.aod_style_fullscreen, AodForm.FULL, 0xFF8FA6C4.toInt(), AodTransition.MASK),
        AodStyle(ID_HANDPAINT, R.string.aod_style_handpaint, AodForm.PARTIAL, 0xFFE8D8B0.toInt(), AodTransition.SCENE),
        AodStyle(ID_TEXT, R.string.aod_style_text, AodForm.PARTIAL, 0xFFA9D3C2.toInt(), AodTransition.MASK),
        AodStyle(ID_PORTRAIT, R.string.aod_style_portrait, AodForm.PARTIAL, 0xFFC4B4E0.toInt(), AodTransition.MASK),
        AodStyle(ID_SAIL, R.string.aod_style_sail, AodForm.PARTIAL, 0xFFC9A227.toInt(), AodTransition.MASK),
        AodStyle(ID_LEAVE_LIGHT, R.string.aod_style_leave_light, AodForm.PARTIAL, 0xFFD8C39A.toInt(), AodTransition.SCENE),
        AodStyle(ID_STARMOON, R.string.aod_style_starmoon, AodForm.PARTIAL, 0xFF9CC0FF.toInt(), AodTransition.SCENE),
        AodStyle(ID_DOOR, R.string.aod_style_door, AodForm.PARTIAL, 0xFFC8B48C.toInt(), AodTransition.SCENE),
        AodStyle(ID_MOUNTAIN, R.string.aod_style_mountain, AodForm.PARTIAL, 0xFF9CB8A8.toInt(), AodTransition.SCENE),
        AodStyle(ID_GALAXY, R.string.aod_style_galaxy, AodForm.PARTIAL, 0xFFA8BCE0.toInt(), AodTransition.SCENE),
    )

    private val byId: Map<String, AodStyle> = styles.associateBy { it.id }

    /** 最近使用初始顺序：星月 / 启航 / 全景息屏。 */
    val defaultRecent: List<String> = listOf(ID_STARMOON, ID_SAIL, ID_FULLSCREEN)

    fun find(id: String?): AodStyle =
        byId[id] ?: byId[Prefs.DEFAULT_ALIVE_AOD_STYLE_ID] ?: styles.first()

    fun allIds(): Set<String> = byId.keys
}
