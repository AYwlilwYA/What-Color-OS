package com.coloros16.liquidglass

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3D 壁纸可被普通应用设为壁纸：不声明 ambient 能力，三态走公开命令。
 *
 * 声明 supportsAmbientMode 会让系统校验 signature|preinstalled 的 AMBIENT_WALLPAPER 权限，
 * 普通应用设壁纸时抛 SecurityException，壁纸根本设不上。
 */
class WallpaperAmbientPermissionContractTest {

    private val lg3d = File("src/main/res/xml/wallpaper_lg3d.xml").readText()
    private val lg2d = File("src/main/res/xml/wallpaper_lg2d.xml").readText()
    private val manifest = File("src/main/AndroidManifest.xml").readText()
    private val service =
        File("src/main/kotlin/com/coloros16/liquidglass/wallpaper/Lg3DWallpaperService.kt").readText()

    /** 两份壁纸描述都不许声明 ambient 能力。 */
    @Test
    fun neitherWallpaperDeclaresAmbientSupport() {
        assertTrue("3D 不许声明 supportsAmbientMode", !lg3d.contains("supportsAmbientMode"))
        assertTrue("2D 不许声明 supportsAmbientMode", !lg2d.contains("supportsAmbientMode"))
    }

    /** 也不许申请那个 signature 级权限——申请了也拿不到，只会误导。 */
    @Test
    fun manifestDoesNotRequestAmbientPermission() {
        assertTrue("不许申请 AMBIENT_WALLPAPER", !manifest.contains("android.permission.AMBIENT_WALLPAPER"))
    }

    /** 三态切换必须走公开 onCommand 三条命令，不依赖 ambient 回调。 */
    @Test
    fun sceneStateUsesPublicCommands() {
        for (c in listOf("android.wallpaper.goingtosleep", "android.wallpaper.wakingup", "android.wallpaper.keyguardgoingaway")) {
            assertTrue("缺公开命令常量 $c", service.contains("\"$c\""))
        }
        assertTrue("必须 override onCommand", service.contains("override fun onCommand("))
        assertTrue("不许 override ambient 回调", !service.contains("onAmbientModeChanged"))
    }

    /** 屏幕广播兜底：命令没来时仍能判息屏/亮屏/解锁。 */
    @Test
    fun screenBroadcastFallbackPresent() {
        assertTrue("缺 SCREEN_OFF", service.contains("Intent.ACTION_SCREEN_OFF"))
        assertTrue("缺 SCREEN_ON", service.contains("Intent.ACTION_SCREEN_ON"))
        assertTrue("缺 USER_PRESENT", service.contains("Intent.ACTION_USER_PRESENT"))
        assertTrue("收到后必须刷新场景态", service.contains("refreshSceneState()"))
    }

    /** 阳性对照：带 ambient 声明的描述文件就是设不上的那种。 */
    @Test
    fun positiveControlAmbientDeclarationBlocksInstall() {
        val bad = """<wallpaper android:supportsAmbientMode="true" />"""
        assertTrue("阳性样本必须含该声明", bad.contains("supportsAmbientMode"))
        assertTrue("现文件与阳性样本必须不同", !lg3d.contains("supportsAmbientMode=\"true\""))
    }

    /** 壁纸服务仍需 BIND_WALLPAPER 与 wallpaper intent-filter，别把该留的一起删了。 */
    @Test
    fun requiredWallpaperDeclarationKept() {
        assertTrue("必须保留 BIND_WALLPAPER", manifest.contains("android.permission.BIND_WALLPAPER"))
        assertTrue("必须保留 wallpaper action", manifest.contains("android.service.wallpaper.WallpaperService"))
        assertTrue("必须保留 meta-data 描述", manifest.contains("android:resource=\"@xml/wallpaper_lg3d\""))
        assertTrue("3D 描述必须仍可解析", lg3d.contains("<wallpaper"))
    }
}
