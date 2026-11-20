package com.coloros16.liquidglass

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 整层背景不玻璃化 + 音量圆角真值 key 契约。
 *
 * 这两条都取自 C17 反编译：整层背景由系统标记为 BlurConfig.isFullScreen=true，
 * 圆角真值 dimen 是 volume_vertical_row_radius_os17。断言钉的是「意图」——
 * 背景层必须在**绘制时**被挡下、圆角必须先读 OS17 key、门控必须版本受限。
 */
class FullScreenBackdropGateContractTest {

    private fun hook(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/hook/BlurDrawHook.kt",
    ).readText()

    @Test
    fun fullScreenFieldIsTheCriterion() {
        val src = hook()
        assertTrue("判据必须是 BlurConfig.isFullScreen 字段", src.contains("FIELD_BLUR_CONFIG_IS_FULL_SCREEN"))
        assertTrue("字段名必须是 isFullScreen", src.contains("\"isFullScreen\""))
        assertTrue("必须有整层背景判定函数", src.contains("fun isFullScreenBackdrop("))
        assertTrue("必须有绘制侧判定函数", src.contains("fun isFullScreenBackdropDrawable("))
    }

    @Test
    fun gateRunsBeforeGlassBindingAtDrawTime() {
        val src = hook()
        val fn = src.indexOf("private fun replaceShaderIfPossible")
        assertTrue("replaceShaderIfPossible 必须在", fn >= 0)
        val gate = src.indexOf("isFullScreenBackdropDrawable(drawable)", fn)
        val bind = src.indexOf("GlassRegistry.bind(id", fn)
        assertTrue("绘制时必须判整层背景", gate > fn)
        assertTrue("背景门控必须先于玻璃绑定（否则已被当玻璃接管）", bind > gate)
        assertTrue("命中后必须交回系统绘制", src.substring(gate, gate + 400).contains("return false"))
    }

    @Test
    fun gateIsVersionScoped() {
        val src = hook()
        assertTrue("门控必须有版本总闸", src.contains("fullScreenBackdropGateEnabled"))
        val probe = src.indexOf("fullScreenBackdropGateEnabled = try")
        assertTrue("总闸必须在安装期一次判定", probe > 0)
        val block = src.substring(probe, minOf(src.length, probe + 700))
        assertTrue("总闸判据必须含 OS17 圆角 key（C16 无此资源 ⇒ 保持原行为）", block.contains("DIMEN_VOLUME_ROW_RADIUS_OS17"))
    }

    @Test
    fun volumeCornerPrefersOs17Key() {
        val src = hook()
        assertTrue(
            "OS17 圆角 key 必须是 volume_vertical_row_radius_os17",
            src.contains("DIMEN_VOLUME_ROW_RADIUS_OS17 = \"volume_vertical_row_radius_os17\""),
        )
        val keys = src.indexOf("VOLUME_RADIUS_DIMEN_KEYS")
        assertTrue("必须有解析顺序表", keys > 0)
        val decl = src.substring(keys, minOf(src.length, keys + 220))
        val os17 = decl.indexOf("DIMEN_VOLUME_ROW_RADIUS_OS17")
        assertTrue("顺序表必须含 OS17 key", os17 >= 0)
        // 旧 key 常量名是 OS17 常量名的前缀，必须从 OS17 之后找，否则命中它自己
        val legacy = decl.indexOf("DIMEN_VOLUME_ROW_RADIUS", os17 + 1)
        assertTrue("OS17 key 必须排在旧 key 之前（否则读到错值）", legacy > os17)
    }

    @Test
    fun volumeHostBranchStillProtectsNative() {
        val src = hook()
        val branch = src.indexOf("VolumeMaskGuard.isVolumeHost(hostView)")
        assertTrue("音量宿主分支必须在", branch > 0)
        assertTrue(
            "音量宿主分支必须仍登记原生内容",
            src.substring(branch, minOf(src.length, branch + 600)).contains("protectNativeVolumeContent"),
        )
    }
}
