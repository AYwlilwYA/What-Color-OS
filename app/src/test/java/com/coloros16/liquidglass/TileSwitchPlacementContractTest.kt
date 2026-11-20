package com.coloros16.liquidglass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 磁贴两项开关归属控制中心：玻璃页不得再留一份，同键不得两处并存。
 *
 * 只读布局与源码文本（ui 类带 android 依赖不能在纯 JVM 加载）。
 */
class TileSwitchPlacementContractTest {

    private fun layout(name: String): String = File("src/main/res/layout/$name.xml").readText()

    private fun activity(): String =
        File("src/main/kotlin/com/coloros16/liquidglass/ui/SettingsActivity.kt").readText()

    private fun strings(): String = File("src/main/res/values/strings.xml").readText()

    /** 两项只在控制中心页出现，玻璃页零残留。 */
    @Test
    fun tileSwitchesLiveOnlyInControlCenterPage() {
        val panel = layout("view_shell_panel")
        val glass = layout("view_shell_glass")
        for (id in listOf("switchCcTileOnBlue", "switchCcTileGlass")) {
            assertEquals("$id 必须在控制中心页出现一次", 1, Regex("""android:id="@\+id/$id"""").findAll(panel).count())
            assertEquals("$id 不得留在玻璃页", 0, Regex(id).findAll(glass).count())
        }
        for (id in listOf("switchTileOnBlueGlass", "switchDoubleLayerGlass")) {
            assertEquals("$id 旧 id 必须全清", 0, Regex(id).findAll(glass).count())
            assertEquals("$id 旧 id 不得进控制中心页", 0, Regex(id).findAll(panel).count())
        }
    }

    /** 玻璃页那个「控制中心磁贴」分组头与信息钮一并撤掉，不留空壳。 */
    @Test
    fun glassPageTileGroupRemoved() {
        val glass = layout("view_shell_glass")
        assertTrue("分组信息钮必须撤", !glass.contains("infoGlassTile"))
        assertTrue("分组标题必须撤", !glass.contains("settings_glass_group_tile"))
        assertTrue("代码里不得再绑该信息钮", !activity().contains("infoGlassTile"))
    }

    /** 一个配置键只允许一处绑定，避免两页各写一份互相覆盖。 */
    @Test
    fun eachTileKeyBoundExactlyOnce() {
        val text = activity()
        for (key in listOf("KEY_TILE_ON_BLUE_GLASS", "KEY_DOUBLE_LAYER_GLASS")) {
            val binds = Regex("""bindGlassSwitch\([^)]*Prefs\.$key""", RegexOption.DOT_MATCHES_ALL)
                .findAll(text).count()
            assertEquals("$key 只能绑一处", 1, binds)
        }
    }

    /** 两项落在控制中心分组内，不在通知或音量分组里。 */
    @Test
    fun tileSwitchesAreInsideControlCenterGroup() {
        val panel = layout("view_shell_panel")
        val ccAt = panel.indexOf("settings_panel_group_cc")
        val volAt = panel.indexOf("settings_panel_group_volume")
        assertTrue("必须有控制中心分组", ccAt > 0)
        assertTrue("必须有音量分组作后界", volAt > ccAt)
        for (id in listOf("switchCcTileOnBlue", "switchCcTileGlass")) {
            val at = panel.indexOf(id)
            assertTrue("$id 必须落在控制中心分组区间", at in (ccAt + 1) until volAt)
        }
    }

    /** 用户可见文案沿用他截图里那两条，不另造新词。 */
    @Test
    fun controlCenterUsesUserFacingWording() {
        val panel = layout("view_shell_panel")
        assertTrue("蓝色项文案", panel.contains("@string/settings_tile_on_blue_glass"))
        assertTrue("双层项文案", panel.contains("@string/settings_double_layer_glass"))
        val xml = strings()
        assertTrue(
            "蓝色项标题必须是用户说法",
            xml.contains("""<string name="settings_tile_on_blue_glass">开启态蓝色透明</string>"""),
        )
        assertTrue(
            "双层项标题必须是用户说法",
            xml.contains("""<string name="settings_double_layer_glass">双层玻璃渲染</string>"""),
        )
    }

    /** 旧的重复文案删干净，不留死键。 */
    @Test
    fun duplicateTileStringsRemoved() {
        val xml = strings()
        for (key in listOf(
            "settings_cc_tile_glass", "settings_cc_tile_glass_desc",
            "settings_cc_tile_on_blue", "settings_cc_tile_on_blue_desc",
            "settings_glass_group_tile", "info_glass_tile",
        )) {
            assertTrue("$key 应已删除", !xml.contains("""<string name="$key">"""))
        }
    }

    /** 搜索能直达控制中心页找这两项。 */
    @Test
    fun searchEntriesPointToControlCenterPage() {
        val text = activity()
        for (key in listOf("settings_tile_on_blue_glass", "settings_double_layer_glass")) {
            assertTrue(
                "$key 搜索项必须指 panel",
                text.contains("""FuncSub(R.string.$key, "panel","""),
            )
        }
    }

    /** 阳性对照：同键两处绑定的旧写法必须被上面的计数抓到。 */
    @Test
    fun positiveControlDuplicateBindingIsDetected() {
        val legacy = """
            bindGlassSwitch(view, R.id.switchTileOnBlueGlass, Prefs.KEY_TILE_ON_BLUE_GLASS, Prefs.DEFAULT_TILE_ON_BLUE_GLASS)
            bindGlassSwitch(view, R.id.switchCcTileOnBlue, Prefs.KEY_TILE_ON_BLUE_GLASS, Prefs.DEFAULT_TILE_ON_BLUE_GLASS)
        """.trimIndent()
        val n = Regex("""bindGlassSwitch\([^)]*Prefs\.KEY_TILE_ON_BLUE_GLASS""", RegexOption.DOT_MATCHES_ALL)
            .findAll(legacy).count()
        assertEquals("旧写法两处绑定必须被数出来", 2, n)
    }
}
