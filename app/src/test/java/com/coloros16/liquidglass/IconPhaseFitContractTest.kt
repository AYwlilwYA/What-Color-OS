package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SystemSceneMotion
import com.coloros16.liquidglass.engine.TransitionEdge
import com.coloros16.liquidglass.engine.effect.EffectCatalog
import com.coloros16.liquidglass.engine.effect.HomeMotion
import com.coloros16.liquidglass.engine.effect.IconEntranceState
import com.coloros16.liquidglass.engine.effect.IconPhaseFit
import com.coloros16.liquidglass.engine.home.HomeLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 图标入场相位必须在边内落位的契约。
 *
 * 用户配的时长与错峰是期望节奏，边长是硬约束；超出时等比压缩，不改默认值。
 */
class IconPhaseFitContractTest {

    private val count = HomeLayout.COLUMNS * HomeLayout.ROWS
    private val ip = IconEntranceState()

    private fun preview(): String =
        File("src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodPreviewView.kt").readText()

    private fun body(name: String): String {
        val text = preview()
        val at = text.indexOf("fun $name(")
        if (at < 0) return ""
        var depth = 0
        var started = false
        val sb = StringBuilder()
        for (i in at until text.length) {
            val c = text[i]
            sb.append(c)
            if (c == '{') { depth++; started = true }
            if (c == '}') { depth--; if (started && depth == 0) break }
        }
        return sb.toString()
    }

    private fun alpha(id: String, index: Int, ms: Long, d: Float, s: Float): Float {
        HomeMotion.iconEntrance(id, index, count, ms, d, s, 60f, ip)
        return ip.alpha
    }

    /** 需求口径：末位图标在边结束那一刻必须已经完全落位。 */
    @Test
    fun lastIconSettlesWithinEveryEdge() {
        for (e in TransitionEdge.values()) {
            val ms = SystemSceneMotion.durationOf(e)
            val (d, s) = IconPhaseFit.fit(ms, count, 420f, 28f)
            assertEquals(
                "$e 末位边内必满",
                1f, alpha(EffectCatalog.HOME_ICON_ROTATE_FLY, count - 1, ms, d, s), 1e-4f,
            )
        }
    }

    /** 桌面过渡选项全量程（200..1200）逐档末位必满。 */
    @Test
    fun lastIconSettlesAcrossOptionRange() {
        for (ms in listOf(200L, 300L, 420L, 500L, 644L, 700L, 800L, 1000L, 1064L, 1200L)) {
            val (d, s) = IconPhaseFit.fit(ms, count, 420f, 28f)
            assertEquals(
                "override=${ms}ms 末位必满",
                1f, alpha(EffectCatalog.HOME_ICON_ROTATE_FLY, count - 1, ms, d, s), 1e-4f,
            )
        }
    }

    /** ★阳性对照：不压缩时六边里有五边到不了 1，短边甚至恒 0。 */
    @Test
    fun positiveControlWithoutFitLastIconNeverSettles() {
        val bad = mutableListOf<String>()
        for (e in TransitionEdge.values()) {
            val ms = SystemSceneMotion.durationOf(e)
            val a = alpha(EffectCatalog.HOME_ICON_ROTATE_FLY, count - 1, ms, 420f, 28f)
            if (a < 0.999f) bad.add("$e=${"%.4f".format(a)}")
        }
        assertEquals("六边全部落不了位（实测 $bad）", 6, bad.size)
        assertEquals(
            "解锁边末位 0.7267",
            0.7267f, alpha(EffectCatalog.HOME_ICON_ROTATE_FLY, count - 1, 800L, 420f, 28f), 2e-3f,
        )
        assertEquals(
            "200ms 选项下末位恒 0（连第一位都没画完）",
            0f, alpha(EffectCatalog.HOME_ICON_ROTATE_FLY, count - 1, 200L, 420f, 28f), 1e-6f,
        )
    }

    /** 边长充足时一个字都不许改（默认档与长边保持原节奏）。 */
    @Test
    fun fitIsNoOpWhenEdgeIsLongEnough() {
        for (ms in listOf(1064L, 1200L, 3840L)) {
            val (d, s) = IconPhaseFit.fit(ms, count, 420f, 28f)
            assertEquals("edge=$ms 时长原样", 420f, d, 1e-6f)
            assertEquals("edge=$ms 错峰原样", 28f, s, 1e-6f)
            assertFalse("edge=$ms 不该标记压缩", IconPhaseFit.compressed(ms, count, 420f, 28f))
        }
    }

    /** 临界：need 恰等于边长时不压缩，少 1ms 就压缩。 */
    @Test
    fun fitBoundaryIsExact() {
        val need = IconPhaseFit.neededMs(count, 420f, 28f)
        assertEquals("末位需求 1064ms（23×28+420）", 1064f, need, 1e-3f)
        assertFalse("恰好放得下不压缩", IconPhaseFit.compressed(1064L, count, 420f, 28f))
        assertTrue("少 1ms 就压缩", IconPhaseFit.compressed(1063L, count, 420f, 28f))
    }

    /** 等比：压缩后时长与错峰的比例不变，错峰形状不被扭曲。 */
    @Test
    fun compressionKeepsRatio() {
        for (ms in listOf(200L, 500L, 800L, 1000L)) {
            val (d, s) = IconPhaseFit.fit(ms, count, 420f, 28f)
            assertEquals("edge=$ms 比例不变", 420f / 28f, d / s, 1e-3f)
            assertTrue("edge=$ms 两者都变小", d < 420f && s < 28f)
        }
    }

    /** 压缩后末位需求恰好等于边长（不是压过头）。 */
    @Test
    fun compressionLandsExactlyOnEdge() {
        for (ms in listOf(200L, 500L, 644L, 800L, 1000L)) {
            val (d, s) = IconPhaseFit.fit(ms, count, 420f, 28f)
            assertEquals("edge=$ms 压缩后需求=边长", ms.toFloat(), IconPhaseFit.neededMs(count, d, s), 0.05f)
        }
    }

    /** 错峰仍在：半程时末位必须落后于首位，不能被压成整屏同相位。 */
    @Test
    fun staggerSurvivesCompression() {
        for (ms in listOf(200L, 500L, 800L)) {
            val (d, s) = IconPhaseFit.fit(ms, count, 420f, 28f)
            val half = ms / 2
            val first = alpha(EffectCatalog.HOME_ICON_ROTATE_FLY, 0, half, d, s)
            val last = alpha(EffectCatalog.HOME_ICON_ROTATE_FLY, count - 1, half, d, s)
            assertTrue("edge=$ms 半程末位必须落后（$last < $first）", last < first)
        }
    }

    /** 非旋转三种本来同相位，压缩后仍同相位且边内必满。 */
    @Test
    fun nonRotateEffectsStayInPhaseAndSettle() {
        for (id in listOf(
            EffectCatalog.HOME_ICON_FOREGROUND_FLY,
            EffectCatalog.HOME_ICON_BACKGROUND_FLY,
            EffectCatalog.HOME_ICON_CROSSFADE,
        )) {
            for (ms in listOf(200L, 500L, 800L)) {
                val (d, s) = IconPhaseFit.fit(ms, count, 420f, 28f)
                val first = alpha(id, 0, ms, d, s)
                val last = alpha(id, count - 1, ms, d, s)
                assertEquals("$id edge=$ms 首末同相位", first, last, 1e-6f)
                assertEquals("$id edge=$ms 边内必满", 1f, last, 1e-4f)
            }
        }
    }

    /** ★阳性对照：200ms 下不压缩连非旋转三种都只有 0.8213。 */
    @Test
    fun positiveControlShortEdgeBreaksNonRotateToo() {
        for (id in listOf(
            EffectCatalog.HOME_ICON_FOREGROUND_FLY,
            EffectCatalog.HOME_ICON_BACKGROUND_FLY,
            EffectCatalog.HOME_ICON_CROSSFADE,
        )) {
            assertEquals(
                "$id 200ms 不压缩只到 0.8213",
                0.8213f, alpha(id, count - 1, 200L, 420f, 28f), 2e-3f,
            )
        }
    }

    /** 用户把滑杆推到上限也要落位（错峰 120 / 时长 1200）。 */
    @Test
    fun extremeSliderValuesStillSettle() {
        for ((d0, s0) in listOf(420f to 120f, 1200f to 28f, 1200f to 120f)) {
            for (ms in listOf(200L, 800L, 3840L)) {
                val (d, s) = IconPhaseFit.fit(ms, count, d0, s0)
                assertEquals(
                    "want=$d0/$s0 edge=$ms 末位必满",
                    1f, alpha(EffectCatalog.HOME_ICON_ROTATE_FLY, count - 1, ms, d, s), 1e-4f,
                )
            }
        }
    }

    /** 边长非正（静止态传 -1）视为无约束，原样返回。 */
    @Test
    fun nonPositiveEdgeMeansNoConstraint() {
        for (ms in listOf(-1L, 0L)) {
            val (d, s) = IconPhaseFit.fit(ms, count, 420f, 28f)
            assertEquals(420f, d, 1e-6f)
            assertEquals(28f, s, 1e-6f)
        }
    }

    /** 单图标与空网格不得除零或算出负需求。 */
    @Test
    fun tinyCountIsSafe() {
        assertEquals("count=1 需求就是时长", 420f, IconPhaseFit.neededMs(1, 420f, 28f), 1e-6f)
        assertEquals("count=0 需求也是时长", 420f, IconPhaseFit.neededMs(0, 420f, 28f), 1e-6f)
        val (d, s) = IconPhaseFit.fit(200L, 1, 420f, 28f)
        assertTrue("count=1 时长被压到边内", d <= 200f)
        assertEquals("count=1 错峰无意义但按比例缩", 28f * (200f / 420f), s, 1e-3f)
    }

    /** 负配置不得算出负时长（引擎已有 coerce，策略件也要挡住）。 */
    @Test
    fun negativeConfigIsClamped() {
        assertTrue("负错峰需求不为负", IconPhaseFit.neededMs(count, 420f, -50f) >= 0f)
        assertTrue("负时长需求不为负", IconPhaseFit.neededMs(count, -420f, 28f) >= 0f)
    }

    /** ★接线：预览必须走策略件，不得再直接把用户配置喂给求值器。 */
    @Test
    fun previewGoesThroughFitPolicy() {
        assertTrue("必须 import 策略件", preview().contains("import com.coloros16.liquidglass.engine.effect.IconPhaseFit"))
        val grid = body("drawAppGrid")
        assertTrue("网格必须先求相位", grid.contains("val phase = iconPhase(count)"))
        assertTrue("求值必须用压缩后的值", grid.contains("phase.first, phase.second, homeIconTravelPercent"))
        assertFalse(
            "不得再直接喂原始配置",
            grid.contains("homeIconDurationMs, homeIconStaggerMs, homeIconTravelPercent"),
        )
    }

    /** ★边长必须与播放进度同源，否则又是两个分母。 */
    @Test
    fun edgeLengthUsesSameSourceAsPlayback() {
        val b = body("iconPhase")
        // 钉取值路径：四处边长已归一到 edgeMsFor（单点内部才是三参 durationOf）
        assertTrue("边长必须经单点 edgeMsFor", b.contains("edgeMsFor(effEdge)"))
        assertTrue("边取自 scrub 或播放同一口径", b.contains("if (scrubP >= 0f) SceneTimeline.edgeAt(scrubP) else edge"))
        assertTrue("静止态传 -1", b.contains("if (effEdge == null) -1L"))
        assertFalse("不得用系统常量当边长", b.contains("SystemSceneMotion.durationOf"))
    }

    /** 诊断必须能分辨「压了没压、压成多少」。 */
    @Test
    fun diagnosticCarriesWantFitAndCompressed() {
        val b = body("logIconPhase")
        assertTrue("带边长", b.contains("edgeMs=\$edgeMs"))
        assertTrue("带期望值", b.contains("want="))
        assertTrue("带压缩后值", b.contains("fit="))
        assertTrue("带需求总量", b.contains("need="))
        assertTrue("带是否压缩", b.contains("compressed="))
        assertTrue("按配置变化节流不逐帧刷", b.contains("if (key == iconPhaseLogKey) return"))
    }

    /** 阳性对照：只打压缩后值分不清「本来就短」与「被压短」。 */
    @Test
    fun positiveControlFitOnlyLogCannotTellReason() {
        val fitOnly = "icon-phase fit=315.8/21.05"
        assertFalse("只有 fit 分不出是否压缩", fitOnly.contains("want=") || fitOnly.contains("compressed="))
    }
}