package com.coloros16.liquidglass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 揭开层快照缓存契约：判重键的输入集必须覆盖快照真正画到的全部输入，且旧位图必须释放。
 *
 * 快照画的是目标态整屏画面（底图 + 效果层 + 锁屏浮层或桌面图标），
 * 键漏一项 ⇒ 换了那一项还返回旧图，画面不动且无日志。
 */
class RevealCacheKeyContractTest {

    private val text =
        File("src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodPreviewView.kt").readText()

    private fun body(fn: String): String {
        val start = Regex("""^\s*(override |internal |private )*fun $fn\b""", RegexOption.MULTILINE)
            .find(text)?.range?.first ?: error("取不到 $fn")
        var depth = 0
        var i = text.indexOf('{', start)
        val from = i
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(from, i + 1)
                }
            }
            i++
        }
        error("$fn 括号不闭合")
    }

    private val capture = body("captureRevealTarget")

    /** 键必须含目标态与画布尺寸。 */
    @Test
    fun keyCarriesTargetAndSize() {
        assertTrue("键缺目标态", capture.contains("\${target.name}"))
        assertTrue("键缺宽度", capture.contains("\${w.toInt()}"))
        assertTrue("键缺高度", capture.contains("\${h.toInt()}"))
    }

    /** 键必须含底图身份：三条底图来源任换一条都要重画。 */
    @Test
    fun keyCarriesBackdropIdentity() {
        assertTrue("键缺底图身份", capture.contains("\$backdropId"))
        val head = capture.indexOf("val backdropId = System.identityHashCode(")
        assertTrue("取不到 backdropId 表达式", head >= 0)
        var depth = 0
        var i = capture.indexOf('(', head)
        val from = i
        while (i < capture.length) {
            when (capture[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) break
                }
            }
            i++
        }
        val idLine = capture.substring(from, i + 1)
        assertTrue("底图身份必须走共用选择件", idLine.contains("backdropSource()"))
        assertTrue("键必须显式带系统壁纸开关", capture.contains(":\$useSystemWallpaper"))
        val picker = body("backdropSource")
        for (src in listOf("stylePhoto", "wallpaper", "sceneSky")) {
            assertTrue("底图选择没算上 $src", picker.contains(src))
        }
        assertTrue("系统壁纸开关必须参与底图选择", picker.contains("useSystemWallpaper"))
        assertTrue("选择必须走共用策略件", picker.contains("BackdropSourcePolicy.backdropKind("))
    }

    /** 快照实际画的底图必须与算身份用的是同一个选择件，否则键与画面不同源。 */
    @Test
    fun snapshotDrawsSameBackdropAsIdentity() {
        assertTrue("快照底图走共用件", capture.contains("val backdrop = backdropSource()"))
        assertFalse(
            "不得再就地拼三选一",
            capture.contains("stylePhoto ?: realWallpaper ?: sceneSky"),
        )
    }

    /** 键必须含效果三参与效果层身份：效果换档或后台层算好都要重画。 */
    @Test
    fun keyCarriesEffectInputs() {
        for (p in listOf("lockWallpaperEffect", "lockSpecialEffect", "lockWallpaperIntensity", "effectLayerKey")) {
            assertTrue("键缺效果输入 $p", capture.contains("\$$p"))
        }
    }

    /** 键必须含 UI 缩放与图标集与页数：它们决定桌面图标的几何与内容。 */
    @Test
    fun keyCarriesHomeGeometryInputs() {
        assertTrue("键缺 uiScale", capture.contains("\$uiScale"))
        assertTrue("键缺图标集身份", capture.contains("System.identityHashCode(homeIcons)"))
        assertTrue("键缺桌面页数", capture.contains("\$homePages"))
    }

    /** 快照必须画静态图标（目标态已落位），不许带入场相位与错峰位移。 */
    @Test
    fun revealSnapshotUsesStaticIcons() {
        assertTrue("桌面快照必须静态画图标", capture.contains("drawAppGrid(off, homeGeometry(w, h), -1L)"))
        assertFalse("快照不许走带入场相位的整层", capture.contains("drawSceneHomeIcons(off"))
        assertFalse("快照不许走带错峰的 Dock 整层", capture.contains("drawSceneHomeDock(off"))
        assertTrue("快照 Dock 必须走默认满可见度", capture.contains("drawDock(off, homeGeometry(w, h))"))
        assertFalse("快照不许读入场相位", capture.contains("iconEntranceElapsedMs()"))
        assertFalse("快照不许读错峰姿态", capture.contains("homeStaggerPose("))
    }

    /**
     * ★锁屏目标同样必须静态：`drawLockOverlay` 内部读 live `edge/layers.lockAlpha`
     * （逐元素错峰），快照按 live 画会把捕获那一刻的姿态烤进位图，而键里没有 edge/t
     * ⇒ 整条边沿用近空白目标图（AOD-150）。
     */
    @Test
    fun revealSnapshotFreezesLockLayer() {
        assertTrue("锁屏快照必须冻结姿态", capture.contains("drawLockOverlay(off, w, h, 1f, 0f, frozen = true)"))
        assertFalse(
            "锁屏快照不许按 live 姿态画",
            Regex("""drawLockOverlay\(off, w, h, 1f, 0f\)""").containsMatchIn(capture),
        )
        assertTrue("诊断必须回显是否冻结（E2 判据）", capture.contains("lockFrozen=\${target == SceneState.LOCK}"))
        val overlay = body("drawLockOverlay")
        assertTrue("冻结时按满可见度零位移", overlay.contains("if (frozen) 1f to 0f"))
        assertTrue("冻结时不打错峰日志", overlay.contains("if (!frozen) logLockStagger("))
    }

    /** 入场相位与错峰姿态必须显式传参，不许在网格内部自己读全局进度。 */
    @Test
    fun entrancePhaseIsInjectedNotReadInsideGrid() {
        assertTrue(
            "网格必须按参数取相位与错峰",
            text.contains("private fun drawAppGrid(") &&
                text.contains("entranceMs: Long,") &&
                text.contains("visible: Float = 1f,") &&
                text.contains("shift: Float = 0f,"),
        )
        val grid = body("drawAppGrid")
        assertFalse("网格不许自读相位", grid.contains("iconEntranceElapsedMs()"))
        assertFalse("网格不许自读错峰", grid.contains("homeStaggerPose("))
        assertTrue(
            "整层必须把相位与错峰喂进去",
            body("drawSceneHomeIcons").contains("drawAppGrid(canvas, geo, iconEntranceElapsedMs(), pose.first, pose.second)"),
        )
    }

    /** 重画时必须先释放旧位图，否则每次换底图泄漏一张整屏半尺寸图。 */
    @Test
    fun oldBitmapRecycledOnRebuild() {
        val recycleAt = capture.indexOf("revealCache?.recycle()")
        val assignAt = capture.indexOf("revealCache = bitmap")
        assertTrue("重画路径缺释放", recycleAt >= 0)
        assertTrue("赋新图缺位", assignAt >= 0)
        assertTrue("必须先释放再赋新图", recycleAt < assignAt)
    }

    /** 离开窗口必须释放位图并清键，否则回到预览会拿已回收的图。 */
    @Test
    fun detachReleasesBitmapAndKey() {
        val fn = body("onDetachedFromWindow")
        assertTrue("detach 缺释放", fn.contains("revealCache?.recycle()"))
        assertTrue("detach 缺置空", fn.contains("revealCache = null"))
        assertTrue("detach 缺清键", fn.contains("revealCacheKey = null"))
    }

    /** 缓存命中才允许复用，且键必须先比后画。 */
    @Test
    fun cacheHitCheckedBeforeDrawing() {
        val hitAt = capture.indexOf("revealCacheKey == key")
        val createAt = capture.indexOf("Bitmap.createBitmap(")
        assertTrue("缺判重早退", hitAt >= 0)
        assertTrue("判重必须在建图之前", hitAt < createAt)
        assertTrue("命中必须返回缓存图", capture.contains("return cached"))
    }

    /** 阳性对照：只带尺寸与效果参数的旧键，换底图后仍相等 ⇒ 检测器必须能抓到。 */
    @Test
    fun positiveControlOldKeyIgnoresBackdrop() {
        val oldKey = { target: String, w: Int, h: Int, wp: String, sp: String, i: Float ->
            "$target:${w}x$h:$wp:$sp:$i"
        }
        val a = oldKey("HOME", 734, 1591, "blur", "none", 0.6f)
        val b = oldKey("HOME", 734, 1591, "blur", "none", 0.6f)
        assertEquals("旧键换底图后必然相等", a, b)

        val newKey = { backdropId: Int -> "$a:$backdropId" }
        assertFalse("新键换底图后必须不同", newKey(1001) == newKey(2002))
    }

    /** 阳性对照：旧快照走带入场相位的整层，同一目标态在不同相位下画面不同。 */
    @Test
    fun positiveControlEntrancePhaseChangesSnapshot() {
        val legacy = """
            SceneState.HOME -> {
                drawSceneHomeIcons(off, w, h, 1f)
                drawSceneHomeDock(off, w, h, 1f)
            }
        """.trimIndent()
        assertTrue("阳性样本必须走整层", legacy.contains("drawSceneHomeIcons(off"))
        assertFalse("现实现不得走整层", capture.contains("drawSceneHomeIcons(off"))
    }

    /** 阳性对照：不释放旧图的写法，检测器必须判红。 */
    @Test
    fun positiveControlMissingRecycleIsDetectable() {
        val legacy = """
            revealCache = bitmap
            revealCacheKey = key
        """.trimIndent()
        assertFalse("阳性样本无释放", legacy.contains("revealCache?.recycle()"))
        assertTrue("现实现有释放", capture.contains("revealCache?.recycle()"))
    }
}
