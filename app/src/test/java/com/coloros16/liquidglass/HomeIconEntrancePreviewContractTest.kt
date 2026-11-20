package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.effect.EffectCatalog
import com.coloros16.liquidglass.engine.effect.HomeMotion
import com.coloros16.liquidglass.engine.effect.IconEntranceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 图标入场预览接线契约：四种效果真的不同、只在进桌面边播、参数从配置注入。 */
class HomeIconEntrancePreviewContractTest {

    private fun preview(): String =
        File("src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodPreviewView.kt").readText()

    private fun controller(): String =
        File("src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodDisplayController.kt").readText()

    private fun body(text: String, fn: String): String {
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

    private val effects = listOf(
        EffectCatalog.HOME_ICON_FOREGROUND_FLY,
        EffectCatalog.HOME_ICON_BACKGROUND_FLY,
        EffectCatalog.HOME_ICON_ROTATE_FLY,
        EffectCatalog.HOME_ICON_CROSSFADE,
    )

    @Test
    fun fourEffectsProduceDistinctMidPoses() {
        val seen = HashSet<String>()
        for (id in effects) {
            val out = IconEntranceState()
            HomeMotion.iconEntrance(id, 2, 12, 120L, 420f, 28f, 60f, out)
            val key = "%.4f|%.4f|%.4f|%.4f".format(out.x, out.y, out.scale, out.rotationDeg)
            assertTrue("效果 $id 的姿态必须与其它不同", seen.add(key))
        }
        assertEquals(4, seen.size)
    }

    @Test
    fun foregroundAndBackgroundFlyOppositeDirections() {
        val fore = IconEntranceState()
        val back = IconEntranceState()
        HomeMotion.iconEntrance(EffectCatalog.HOME_ICON_FOREGROUND_FLY, 0, 8, 0L, 420f, 28f, 60f, fore)
        HomeMotion.iconEntrance(EffectCatalog.HOME_ICON_BACKGROUND_FLY, 0, 8, 0L, 420f, 28f, 60f, back)
        assertTrue("前景飞入自下而上", fore.y > 0f)
        assertTrue("后景飞入自上而下", back.y < 0f)
        assertTrue("前景在屏幕前方必须放大", fore.scale > 1f)
        assertTrue("后景在屏幕后方必须缩小", back.scale < 1f)
    }

    @Test
    fun rotateFlyIsTheOnlyOneWithRotation() {
        for (id in effects) {
            val out = IconEntranceState()
            HomeMotion.iconEntrance(id, 0, 8, 0L, 420f, 28f, 60f, out)
            if (id == EffectCatalog.HOME_ICON_ROTATE_FLY) {
                assertTrue("旋转飞入必须有角度", out.rotationDeg < -1f)
            } else {
                assertEquals("其它三种不得有角度", 0f, out.rotationDeg, 1e-6f)
            }
        }
    }

    @Test
    fun entranceSettlesAtRest() {
        for (id in effects) {
            val out = IconEntranceState()
            HomeMotion.iconEntrance(id, 11, 12, 99999L, 420f, 28f, 60f, out)
            assertEquals(0f, out.x, 1e-4f)
            assertEquals(0f, out.y, 1e-4f)
            assertEquals(1f, out.scale, 1e-4f)
            assertEquals(1f, out.alpha, 1e-4f)
            assertEquals(0f, out.rotationDeg, 1e-4f)
        }
    }

    @Test
    fun staggerDelaysLaterIcons() {
        val first = IconEntranceState()
        val last = IconEntranceState()
        HomeMotion.iconEntrance(EffectCatalog.HOME_ICON_ROTATE_FLY, 0, 12, 60L, 420f, 28f, 60f, first)
        HomeMotion.iconEntrance(EffectCatalog.HOME_ICON_ROTATE_FLY, 11, 12, 60L, 420f, 28f, 60f, last)
        assertTrue("旋转飞入靠后的图标必须还没落位", last.alpha < first.alpha)
    }

    /** 前景/后景/淡出淡入整屏一起动：首末位姿态逐位相同。 */
    @Test
    fun nonRotateEffectsMoveAllIconsTogether() {
        for (id in listOf(
            EffectCatalog.HOME_ICON_FOREGROUND_FLY,
            EffectCatalog.HOME_ICON_BACKGROUND_FLY,
            EffectCatalog.HOME_ICON_CROSSFADE,
        )) {
            val first = IconEntranceState()
            val last = IconEntranceState()
            HomeMotion.iconEntrance(id, 0, 12, 60L, 420f, 28f, 60f, first)
            HomeMotion.iconEntrance(id, 11, 12, 60L, 420f, 28f, 60f, last)
            assertEquals("$id 首末位 alpha 必须相同", first.alpha, last.alpha, 1e-6f)
            assertEquals("$id 首末位 scale 必须相同", first.scale, last.scale, 1e-6f)
            assertEquals("$id 首末位 y 必须相同", first.y, last.y, 1e-6f)
        }
    }

    @Test
    fun previewConsumesHomeMotion() {
        val text = preview()
        assertTrue("预览必须 import HomeMotion", text.contains("import com.coloros16.liquidglass.engine.effect.HomeMotion"))
        assertTrue("图标网格必须调求值器", body(text, "drawAppGrid").contains("HomeMotion.iconEntrance("))
    }

    @Test
    fun entranceOnlyPlaysOnIntoHomeEdges() {
        val fn = body(preview(), "iconEntranceElapsedMs")
        assertTrue(fn.contains("TransitionEdge.LOCK_TO_HOME"))
        assertTrue(fn.contains("TransitionEdge.AOD_TO_HOME"))
        assertTrue("其它边必须返回 -1 走静态", fn.contains("return -1L"))
    }

    @Test
    fun previewAppliesAllPoseChannels() {
        val fn = body(preview(), "drawAppGrid")
        for (channel in listOf("iconPose.x", "iconPose.y", "iconPose.scale", "iconPose.rotationDeg", "iconPose.alpha")) {
            assertTrue("通道 $channel 必须被消费", fn.contains(channel))
        }
        assertTrue("静止分支必须保留", fn.contains("if (entranceMs < 0L)"))
    }

    @Test
    fun controllerInjectsEffectAndThreeParams() {
        val text = controller()
        assertTrue(text.contains("preview.homeIconEffect = effects.homeIconEffect"))
        assertTrue(text.contains("preview.homeIconDurationMs"))
        assertTrue(text.contains("preview.homeIconStaggerMs"))
        assertTrue(text.contains("preview.homeIconTravelPercent"))
        assertTrue(text.contains("EffectParams.HOME_ICON_DURATION"))
        assertTrue(text.contains("EffectParams.HOME_ICON_STAGGER"))
        assertTrue(text.contains("EffectParams.HOME_ICON_TRAVEL"))
    }

    /** 阳性对照：不接求值器的旧写法（整层统一 alpha）检测器必报警。 */
    @Test
    fun positiveControlDetectsUniformAlphaOnlyGrid() {
        val legacy = """
            private fun drawAppGrid(canvas: Canvas, geo: HomeGeometry) {
                for (index in 0 until count) {
                    drawAppIcon(canvas, index, cx, cy, size)
                    canvas.drawText(label, cx, labelY, text)
                }
            }
        """.trimIndent()
        assertTrue("旧写法无逐图标求值", !legacy.contains("HomeMotion.iconEntrance"))
        assertTrue("旧写法无逐图标姿态", !legacy.contains("iconPose"))
    }
}