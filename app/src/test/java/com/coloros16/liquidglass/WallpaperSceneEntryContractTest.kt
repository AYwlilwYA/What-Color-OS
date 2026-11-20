package com.coloros16.liquidglass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3D 壁纸场景选择入口契约：入口存在、写对键、与相机曲线互斥。
 *
 * SettingsActivity 带 android 依赖不能在纯 JVM 加载，源码侧断言一律读文本。
 */
class WallpaperSceneEntryContractTest {

    private val activity =
        File("src/main/kotlin/com/coloros16/liquidglass/ui/SettingsActivity.kt").readText()
    private val layout =
        File("src/main/res/layout/view_cat_wallpaper.xml").readText()
    private val strings =
        File("src/main/res/values/strings.xml").readText()

    private fun body(text: String, fn: String): String {
        val m = Regex("""^([ \t]*)(private |internal |override )*fun $fn\b""", RegexOption.MULTILINE)
            .find(text) ?: error("取不到 $fn")
        val start = m.range.first
        val lineEnd = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        val brace = text.indexOf('{', start)
        if (brace < 0 || brace > lineEnd) {
            val indent = m.groupValues[1].length
            val next = Regex(
                """^[ \t]{0,$indent}(private |internal |override |public )*(fun|val|var|@|class|companion)\b""",
                RegexOption.MULTILINE,
            ).find(text, lineEnd + 1)
            return text.substring(start, next?.range?.first ?: text.length)
        }
        var depth = 0
        var i = brace
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(brace, i + 1)
                }
            }
            i++
        }
        error("$fn 括号不闭合")
    }

    /** 布局必须有场景下拉，且排在曲线下拉之前（先选场景再谈曲线）。 */
    @Test
    fun sceneSpinnerExistsBeforeCurve() {
        assertTrue("缺场景下拉", layout.contains("@+id/spinnerCameraScene"))
        assertTrue("缺场景说明", layout.contains("@+id/tvWp3dSceneHint"))
        assertTrue("缺曲线锁定说明", layout.contains("@+id/tvWp3dCurveHint"))
        assertTrue(
            "场景必须排在曲线之前",
            layout.indexOf("spinnerCameraScene") < layout.indexOf("spinnerCameraCurve"),
        )
    }

    /** 场景下拉必须写场景键，不能误写曲线键。 */
    @Test
    fun sceneWritesSceneKey() {
        val fn = body(activity, "bindWallpaperScene")
        assertTrue("必须写场景键", fn.contains("writePrefString(Prefs.KEY_WALLPAPER_3D_SCENE, sceneId)"))
        assertTrue("不许写曲线键", !fn.contains("KEY_WALLPAPER_3D_CURVE"))
        assertTrue("初值必须读场景键", fn.contains("Prefs.KEY_WALLPAPER_3D_SCENE, Prefs.DEFAULT_WALLPAPER_3D_SCENE"))
        assertTrue("必须留诊断日志", fn.contains("wp3d scene="))
    }

    /** 取值必须取共用常量，不在 UI 里另写一份字面量。 */
    @Test
    fun sceneIdsComeFromSharedConstants() {
        val fn = body(activity, "bindWallpaperScene")
        assertTrue("必须用共用常量", fn.contains("WallpaperSettings.SCENE_NONE"))
        assertTrue("雪山必须用共用常量", fn.contains("WallpaperSettings.SCENE_SNOW_MOUNTAIN"))
        assertTrue("不许硬编码 snowmountain", !fn.contains("\"snowmountain\""))
    }

    /** 场景与曲线互斥：选场景锁曲线，改回无解锁。 */
    @Test
    fun sceneLocksCurveBothWays() {
        val fn = body(activity, "applySceneLock")
        assertTrue("锁定判据=场景非空", fn.contains("val locked = sceneId.isNotEmpty()"))
        assertTrue("必须禁用曲线下拉", fn.contains("curveSpinner.isEnabled = !locked"))
        assertTrue("必须置灰", fn.contains("curveSpinner.alpha ="))
        assertTrue("必须露出说明", fn.contains("if (locked) View.VISIBLE else View.GONE"))
        val bind = body(activity, "bindWallpaperScene")
        assertTrue("初始化就要按存值上锁", bind.contains("applySceneLock(view, curveSpinner, sceneIds[index])"))
        assertTrue("切换时要重新上锁", bind.contains("applySceneLock(view, curveSpinner, sceneId)"))
    }

    /** 入口必须真的被壁纸页调用，否则声明了也进不去。 */
    @Test
    fun entryIsWiredFromWallpaperPage() {
        val fn = body(activity, "bindWallpaper")
        assertTrue("壁纸页必须调用场景绑定", fn.contains("bindWallpaperScene(view, curveSpinner)"))
        assertTrue(
            "必须在曲线下拉建好之后调用",
            fn.indexOf("curveSpinner.onItemSelectedListener") < fn.indexOf("bindWallpaperScene(view, curveSpinner)"),
        )
    }

    /** 文案齐备且不含内部黑话。 */
    @Test
    fun stringsPresentAndUserFacing() {
        for (k in listOf("wp_3d_scene", "wp_3d_scene_desc", "wp_3d_scene_none", "wp_3d_scene_snow", "wp_3d_curve_locked_by_scene")) {
            assertTrue("缺文案 $k", strings.contains("name=\"$k\""))
        }
        val locked = Regex("""name="wp_3d_curve_locked_by_scene">([^<]*)<""").find(strings)!!.groupValues[1]
        assertTrue("锁定说明必须给出改回方法", locked.contains("改回") || locked.contains("无"))
        for (bad in listOf("spec/", "KEY_", "snowmountain", "Prefs")) {
            assertTrue("用户文案不许出现内部词 $bad", !locked.contains(bad))
        }
    }

    /** 阳性对照：只加布局不接线时，壁纸页仍然没有场景入口。 */
    @Test
    fun positiveControlLayoutWithoutWiringIsUnreachable() {
        val legacy = """
            val curveSpinner = view.findViewById<Spinner>(R.id.spinnerCameraCurve)
            curveSpinner.adapter = ArrayAdapter(...)
            curveSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener { }
            bindIntSeekBarStep(...)
        """.trimIndent()
        assertTrue("旧写法没有场景绑定", !legacy.contains("bindWallpaperScene"))
    }

    /** 阳性对照：不置灰只禁用，用户看不出为什么点不动。 */
    @Test
    fun positiveControlDisableWithoutHintIsSilent() {
        val onlyDisable = "curveSpinner.isEnabled = !locked"
        assertTrue("只禁用没有视觉反馈", !onlyDisable.contains("alpha"))
        assertTrue("只禁用没有文字说明", !onlyDisable.contains("VISIBLE"))
    }

    /** 场景常量口径：空串=走曲线，非法值由 WallpaperSettings 回落。 */
    @Test
    fun sceneNoneIsEmptyString() {
        assertEquals("", com.coloros16.liquidglass.wallpaper.WallpaperSettings.SCENE_NONE)
        assertEquals("snowmountain", com.coloros16.liquidglass.wallpaper.WallpaperSettings.SCENE_SNOW_MOUNTAIN)
        assertTrue(!com.coloros16.liquidglass.wallpaper.WallpaperSettings.validScene(""))
        assertTrue(!com.coloros16.liquidglass.wallpaper.WallpaperSettings.validScene("bogus"))
        assertTrue(com.coloros16.liquidglass.wallpaper.WallpaperSettings.validScene("snowmountain"))
    }
}
