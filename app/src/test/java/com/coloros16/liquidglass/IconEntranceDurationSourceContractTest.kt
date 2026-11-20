package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SailLensMotion
import com.coloros16.liquidglass.engine.SystemSceneMotion
import com.coloros16.liquidglass.engine.TransitionEdge
import com.coloros16.liquidglass.engine.effect.EffectCatalog
import com.coloros16.liquidglass.engine.effect.EffectParams
import com.coloros16.liquidglass.engine.effect.EffectSettings
import com.coloros16.liquidglass.engine.effect.HomeMotion
import com.coloros16.liquidglass.engine.effect.IconEntranceState
import com.coloros16.liquidglass.ui.aod.AodSceneCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 图标入场的毫秒分母必须与该边真实播放时长同源。
 *
 * 播放进度 t 已按真实边长归一化，再乘系统常量等于换了分母：
 * 镜片款边长 3840ms 而常量 800ms，图标在边刚开头就全落位；
 * 桌面过渡选项 200ms 时常量仍是 800ms，图标比边快四倍。
 */
class IconEntranceDurationSourceContractTest {

    private fun preview(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodPreviewView.kt",
    ).readText()

    private fun controller(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodDisplayController.kt",
    ).readText()

    private fun body(text: String, name: String): String {
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

    private fun settingsWith(id: String, durationMs: Float): EffectSettings =
        EffectSettings().also {
            it.homeTransition = id
            it.setParam(EffectParams.HOME_TRANSITION_DURATION, durationMs)
        }

    // ---------------------------------------------------------------- 缺陷定量

    /** 镜片款解锁边真实 3840ms，系统常量只有 800ms，差 4.8 倍。 */
    @Test
    fun sailUnlockEdgeIsFarLongerThanSystemConstant() {
        val real = AodSceneCatalog.durationOf("aod.sail", TransitionEdge.LOCK_TO_HOME)
        val constant = SystemSceneMotion.durationOf(TransitionEdge.LOCK_TO_HOME)
        assertEquals("镜片解锁边真值 3840ms", SailLensMotion.LOCK_TO_HOME_MS.toLong(), real)
        assertEquals("系统常量 800ms", 800L, constant)
        assertTrue("真实边长必须是常量的四倍以上", real > constant * 4)
    }

    /** 同一 t 下两条分母算出的毫秒差：t=0.25 时 200ms vs 960ms。 */
    @Test
    fun sameProgressYieldsVeryDifferentElapsed() {
        val real = AodSceneCatalog.durationOf("aod.sail", TransitionEdge.LOCK_TO_HOME)
        val constant = SystemSceneMotion.durationOf(TransitionEdge.LOCK_TO_HOME)
        val t = 0.25f
        assertEquals(200f, t * constant, 1e-3f)
        assertEquals(960f, t * real, 1e-3f)
        assertEquals("差 760ms", 760f, t * real - t * constant, 1e-3f)
    }

    /**
     * 单元素行程 420ms：t=0.25 错分母只播 200ms，正确分母已播 960ms 早落位。
     *
     * DECELERATE 前段陡，200/420 处 alpha 已 0.8213，故判据取「未落位且明显落后」不是「过半」。
     */
    @Test
    fun wrongDenominatorLeavesIconLaggingOnSail() {
        val out = IconEntranceState()
        val id = EffectCatalog.HOME_ICON_FOREGROUND_FLY
        HomeMotion.iconEntrance(id, 0, 12, 200L, 420f, 28f, 60f, out)
        val wrongAlpha = out.alpha
        val wrongScale = out.scale
        HomeMotion.iconEntrance(id, 0, 12, 960L, 420f, 28f, 60f, out)
        assertEquals("正确分母下 t=0.25 早已落位", 1f, out.alpha, 1e-4f)
        assertEquals("落位缩放归 1", 1f, out.scale, 1e-4f)
        assertTrue("错分母下仍未落位 alpha=$wrongAlpha", wrongAlpha < 0.9f)
        assertTrue("错分母下仍在前方未缩回", wrongScale > 1f)
        assertTrue("两者差距必须显著", 1f - wrongAlpha > 0.1f)
    }

    /** 桌面过渡选项把边压到 200ms，常量仍 800ms ⇒ 图标比边快四倍。 */
    @Test
    fun transitionOptionShortensEdgeButConstantStays() {
        val s = settingsWith(EffectCatalog.HOME_TRANSITION_CROSSFADE, 200f)
        val real = AodSceneCatalog.durationOf("aod.fullscreen", TransitionEdge.LOCK_TO_HOME, s)
        val constant = SystemSceneMotion.durationOf(TransitionEdge.LOCK_TO_HOME)
        assertEquals("选项时长接管", 200L, real)
        assertEquals("常量不动", 800L, constant)
        assertEquals("图标链快四倍", 4f, constant.toFloat() / real, 1e-4f)
    }

    /** 阳性对照：默认样式 + 不接管时两条分母恰好相等，故默认配置看不出缺陷。 */
    @Test
    fun positiveControlDefaultHidesTheDefect() {
        val s = settingsWith(EffectCatalog.HOME_TRANSITION_NONE, 500f)
        val real = AodSceneCatalog.durationOf("aod.fullscreen", TransitionEdge.LOCK_TO_HOME, s)
        val constant = SystemSceneMotion.durationOf(TransitionEdge.LOCK_TO_HOME)
        assertEquals("默认不接管两者相等", constant, real)
        val sailReal = AodSceneCatalog.durationOf("aod.sail", TransitionEdge.LOCK_TO_HOME, s)
        assertNotEquals("换成镜片款立刻分歧", constant, sailReal)
    }

    /** 息屏直进桌面那条边同样分歧：镜片 1000ms vs 常量 700ms。 */
    @Test
    fun aodToHomeEdgeAlsoDiverges() {
        val real = AodSceneCatalog.durationOf("aod.sail", TransitionEdge.AOD_TO_HOME)
        val constant = SystemSceneMotion.durationOf(TransitionEdge.AOD_TO_HOME)
        assertEquals(SailLensMotion.AOD_TO_HOME_MS.toLong(), real)
        assertEquals(700L, constant)
        assertTrue("两条分母必须不同", real != constant)
    }

    // ---------------------------------------------------------------- 接线

    /** 求值必须与控制器播放时长同源：现在四处边长都归一到 `edgeMsFor`。 */
    @Test
    fun entranceUsesSameDurationSourceAsPlayer() {
        val fn = body(preview(), "iconEntranceElapsedMs")
        assertTrue("必须经单点 edgeMsFor 取边长", fn.contains("edgeMsFor(effEdge)"))
        assertFalse(
            "不得再用系统常量当分母",
            fn.contains("SystemSceneMotion.durationOf(effEdge)"),
        )
        // 同源性的实质保证在单点里：播放账本优先，否则三参重载（含 homeSettings）
        // 单点是表达式体（`= if … else …`），brace 抽取器取不到 ⇒ 按下一处缩进成员切段
        val helper = preview()
            .substringAfter("private fun edgeMsFor(")
            .substringBefore("\n    private fun ")
        assertTrue("单点抽取为空，判据失效", helper.isNotEmpty())
        assertTrue("单点必须优先用播放账本", helper.contains("playbackEdgeMs > 0L"))
        assertTrue(
            "非播放期必须走三参 durationOf（含 settings，与控制器同源）",
            helper.contains("AodSceneCatalog.durationOf(styleId, e, homeSettings())"),
        )
    }

    /** 控制器播放用的也是三参重载，两侧同一个函数。 */
    @Test
    fun controllerPlaysWithThreeArgOverload() {
        assertTrue(
            controller().contains("AodSceneCatalog.durationOf(draftId, edge, effects)"),
        )
    }

    /** homeSettings 必须同时带选项 id 与时长，否则时长覆盖不生效。 */
    @Test
    fun homeSettingsCarriesBothIdAndDuration() {
        val fn = body(preview(), "homeSettings")
        assertTrue("带选项 id", fn.contains("it.homeTransition = homeTransition"))
        assertTrue(
            "带时长参数",
            fn.contains("it.setParam(EffectParams.HOME_TRANSITION_DURATION, homeTransitionDurationMs)"),
        )
    }

    /** 边过滤与静止分支不变：本轮只换分母。 */
    @Test
    fun edgeFilterUnchanged() {
        val fn = body(preview(), "iconEntranceElapsedMs")
        assertTrue(fn.contains("TransitionEdge.LOCK_TO_HOME"))
        assertTrue(fn.contains("TransitionEdge.AOD_TO_HOME"))
        assertTrue("其它边仍返回 -1", fn.contains("return -1L"))
        assertTrue("拖动仍走整轨进度", fn.contains("SceneTimeline.localT(scrubP)"))
    }

    /** 三参重载在不接管时回落两参，默认行为逐位不变。 */
    @Test
    fun threeArgFallsBackToStyleTruth() {
        val s = settingsWith(EffectCatalog.HOME_TRANSITION_NONE, 500f)
        for (id in listOf("aod.sail", "aod.starmoon", "aod.fullscreen", "aod.handpaint")) {
            for (e in TransitionEdge.values()) {
                assertEquals(
                    "$id $e 不接管必须回落样式真值",
                    AodSceneCatalog.durationOf(id, e),
                    AodSceneCatalog.durationOf(id, e, s),
                )
            }
        }
    }

    /** 覆盖只作用两条解锁边，含息屏的四条不受影响。 */
    @Test
    fun overrideOnlyGovernsUnlockEdges() {
        val s = settingsWith(EffectCatalog.HOME_TRANSITION_CROSSFADE, 300f)
        for (e in TransitionEdge.values()) {
            val two = AodSceneCatalog.durationOf("aod.sail", e)
            val three = AodSceneCatalog.durationOf("aod.sail", e, s)
            if (e == TransitionEdge.LOCK_TO_HOME || e == TransitionEdge.HOME_TO_LOCK) {
                assertEquals("$e 必须被覆盖", 300L, three)
            } else {
                assertEquals("$e 不得被覆盖", two, three)
            }
        }
    }
}
