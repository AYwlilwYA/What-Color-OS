package com.coloros16.liquidglass

import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.ui.aod.AodStyleSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 应用款样式解析口径契约。
 *
 * 反面实锤（真机配置旁证）：抽屉「应用」只写 `alive_aod_style_id`，
 * 而独立编辑页曾按 `effect_config` 的 `aod_style=` 取款 ——
 * 两套存储并存时，预览/hook 显示手绘、编辑页仍按启航取锚点，差一整段锚点位移。
 */
class AodStyleSelectionTest {

    private fun resolve(configId: String?, configStyle: String?, styleId: String?): String =
        AodStyleSelection.resolve(configId, { configStyle }, styleId)

    /** 配置快照优先：有 configId 且能查到款式时以它为准。 */
    @Test
    fun configSnapshotWinsOverPlainStyleKey() {
        assertEquals("aod.handpaint", resolve("cfg-1", "aod.handpaint", "aod.sail"))
    }

    /** 配置已删除（查不到）时回落到样式键，不能掉回默认。 */
    @Test
    fun missingConfigFallsBackToStyleKey() {
        assertEquals("aod.handpaint", resolve("cfg-gone", null, "aod.handpaint"))
    }

    /** 两者都没有才用默认款。 */
    @Test
    fun emptyFallsBackToDefault() {
        assertEquals(Prefs.DEFAULT_ALIVE_AOD_STYLE_ID, resolve(null, null, null))
        assertEquals(Prefs.DEFAULT_ALIVE_AOD_STYLE_ID, resolve("", null, "  "))
    }

    /**
     * ★阳性对照：分叉现场（alive 键=手绘、effect_config=启航）。
     *
     * 正确口径必须给出**手绘**；若有人把独立页改回读 effect_config，
     * 这里会变成启航 —— 与预览/hook 分叉。
     */
    @Test
    fun positiveControlDivergentStoresResolveToAliveKey() {
        val aliveStyle = "aod.handpaint"
        val effectConfigStyle = "aod.sail"
        assertEquals("分叉时必须跟 alive 键（预览/hook 的取法）", aliveStyle, resolve(null, null, aliveStyle))
        assertTrue("两套存储值确实不同（否则这条对照无意义）", aliveStyle != effectConfigStyle)
    }

    /** 预览与独立页必须调同一个解析件，不许各写一份。 */
    @Test
    fun bothSurfacesUseSharedResolver() {
        val controller = File("src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodDisplayController.kt").readText()
        val activity = File("src/main/kotlin/com/coloros16/liquidglass/ui/SettingsActivity.kt").readText()
        assertTrue("预览侧调共享件", controller.contains("AodStyleSelection.resolve("))
        assertTrue("独立页调共享件", activity.contains("AodStyleSelection.resolve("))
        // 独立页的 styleIdOf 不得再直读 effect_config 的 aod_style
        val editPage = activity.substringAfter("private fun bindAodEditPage(").substringBefore("private fun readLayoutSettings")
        assertTrue("独立页必须有 styleIdOf", editPage.contains("styleIdOf = {"))
        assertTrue(
            "独立页不得再按 effect_config 的 aod_style 取款（那是另一套存储）",
            !editPage.contains("readEffectSettings().aodStyleId"),
        )
    }
}
