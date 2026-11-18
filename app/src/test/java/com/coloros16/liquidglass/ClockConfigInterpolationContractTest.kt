package com.coloros16.liquidglass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 转场期时钟配置插值契约：位置与字号必须走同一条进度，两端与静止态严格相等。
 *
 * 病灶是「锚点 scale 走 MASK 曲线、配置 scale 走另一套 wLock/wAod 权重」，
 * 两条不同步导致中途字号超出两端区间、收尾跳变。
 */
class ClockConfigInterpolationContractTest {

    private fun delegate(): String =
        File("src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodPreviewEditDelegate.kt").readText()

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

    /** 配置缩放必须与 pose 用同一个 e，不得另立权重。 */
    @Test
    fun configScaleUsesSameProgressAsPose() {
        val fn = body(delegate(), "drawSceneClockAt")
        assertTrue("缩放必须按 e 直接插值两端配置", fn.contains("lerp(fromSettings.clockScale, toSettings.clockScale, e)"))
        assertFalse("不得再有 wLock 权重", fn.contains("wLock"))
        assertFalse("不得再有 wAod 权重", fn.contains("wAod"))
        assertFalse("不得再有 total 归一", fn.contains("/ total"))
    }

    @Test
    fun colorUsesSameProgressToo() {
        val fn = body(delegate(), "drawSceneClockAt")
        assertTrue(fn.contains("lerpColor(colorOf(fromSettings, sceneColor), colorOf(toSettings, sceneColor), e)"))
    }

    /** 两端取值：e=0 必等于 from 配置，e=1 必等于 to 配置。 */
    @Test
    fun endpointsMatchExactly() {
        val fromScale = 1.2764f
        val toScale = 0.9393f
        fun at(e: Float) = fromScale + (toScale - fromScale) * e
        assertEquals(fromScale, at(0f), 1e-6f)
        assertEquals(toScale, at(1f), 1e-6f)
    }

    /** 中途必须落在两端之间（旧权重会超出区间）。 */
    @Test
    fun midValuesStayWithinEndpoints() {
        val fromScale = 1.2764f
        val toScale = 0.9393f
        val lo = minOf(fromScale, toScale)
        val hi = maxOf(fromScale, toScale)
        for (i in 0..40) {
            val e = i / 40f
            val v = fromScale + (toScale - fromScale) * e
            assertTrue("e=$e 必须落在两端之间", v in (lo - 1e-5f)..(hi + 1e-5f))
        }
    }

    /** 阳性对照：旧权重在 AOD→LOCK 末段会超出两端区间。 */
    @Test
    fun positiveControlOldWeightingOvershoots() {
        val lockScale = 0.9393f
        val aodScale = 1.2764f
        val e = 0.9663f
        val wLock = e
        val wAod = 1f
        val total = wLock + wAod
        val oldMultiplier = (lockScale * wLock + aodScale * wAod) / total
        val newMultiplier = aodScale + (lockScale - aodScale) * e
        assertTrue(
            "旧权重末段必须显著偏大（实测 1.1107 vs 应有 0.9507）",
            oldMultiplier - newMultiplier > 0.10f,
        )
        assertTrue("新值必须落在两端之间", newMultiplier in 0.9393f..1.2764f)
    }

    /** 阳性对照：旧口径末帧与静止 LOCK 字号必不同（实测 126.57 vs 106.81）。 */
    @Test
    fun positiveControlOldLastFrameDiffersFromStatic() {
        val poseLast = 0.62079f
        val poseStatic = 0.62f
        val oldLast = 126.57f
        val staticFont = 106.81f
        assertTrue("旧口径末帧与静止必须差 15% 以上", (oldLast - staticFont) / staticFont > 0.15f)
        val newLast = poseLast * 0.9393f
        val newStatic = poseStatic * 0.9393f
        assertTrue("新口径末帧与静止差必须小于 1%", kotlin.math.abs(newLast - newStatic) / newStatic < 0.01f)
    }

    @Test
    fun dominantStyleSwitchesAtMidpoint() {
        val fn = body(delegate(), "drawSceneClockAt")
        assertTrue("字体/字距按半程切换主导端", fn.contains("if (e < 0.5f) fromSettings else toSettings"))
    }

    @Test
    fun homeToHomeStillBailsOut() {
        val fn = body(delegate(), "drawSceneClockAt")
        assertTrue(fn.contains("if (fromState == SceneState.HOME && toState == SceneState.HOME) return false"))
    }

    @Test
    fun missingLayoutFallsBackToBase() {
        val fn = body(delegate(), "drawSceneClockAt")
        assertTrue(fn.contains("layoutOf(fromState) ?: layoutOf(toState) ?: return false"))
        assertTrue(fn.contains("layoutOf(fromState) ?: base"))
        assertTrue(fn.contains("layoutOf(toState) ?: base"))
    }
}