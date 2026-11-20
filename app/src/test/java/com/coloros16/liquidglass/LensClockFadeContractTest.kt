package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SailLensMotion
import com.coloros16.liquidglass.engine.TransitionEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 镜片息屏时钟淡入淡出契约：hook 必须消费 aodClockAlpha，只作用息屏面、只改 alpha。
 */
class LensClockFadeContractTest {

    private fun stage(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/hook/aod/AodStageHook.kt",
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

    private fun clockAlphaAt(edge: TransitionEdge, t: Float): Float =
        SailLensMotion.Fade().also { SailLensMotion.fade(edge, t, it) }.aodClockAlpha

    /** 出场：起点全亮、终点全灭，中段单调下降。 */
    @Test
    fun exitFadesFromOneToZero() {
        assertEquals(1f, clockAlphaAt(TransitionEdge.AOD_TO_LOCK, 0f), 1e-4f)
        assertEquals(0f, clockAlphaAt(TransitionEdge.AOD_TO_LOCK, 1f), 1e-4f)
        var prev = 1f
        for (i in 1..20) {
            val a = clockAlphaAt(TransitionEdge.AOD_TO_LOCK, i / 20f)
            assertTrue("必须单调不增", a <= prev + 1e-4f)
            prev = a
        }
    }

    /** 入场：起点全灭、终点全亮。 */
    @Test
    fun entryFadesFromZeroToOne() {
        assertEquals(0f, clockAlphaAt(TransitionEdge.LOCK_TO_AOD, 0f), 1e-4f)
        assertEquals(1f, clockAlphaAt(TransitionEdge.LOCK_TO_AOD, 1f), 1e-4f)
        assertEquals(0f, clockAlphaAt(TransitionEdge.HOME_TO_AOD, 0f), 1e-4f)
        assertEquals(1f, clockAlphaAt(TransitionEdge.HOME_TO_AOD, 1f), 1e-4f)
    }

    /** 时钟与息屏层同窗同曲线（官方口径：两者一起溶解）。 */
    @Test
    fun clockMatchesLayerAlphaOnLensEdges() {
        for (edge in arrayOf(
            TransitionEdge.AOD_TO_LOCK, TransitionEdge.AOD_TO_HOME,
            TransitionEdge.LOCK_TO_AOD, TransitionEdge.HOME_TO_AOD,
        )) {
            for (i in 0..10) {
                val t = i / 10f
                val f = SailLensMotion.Fade().also { SailLensMotion.fade(edge, t, it) }
                assertEquals("边 $edge t=$t 时钟与层同值", f.aodAlpha, f.aodClockAlpha, 1e-6f)
            }
        }
    }

    /** 解锁两条边不参与：时钟保持原样（那两条边没有息屏层）。 */
    @Test
    fun unlockEdgesKeepClockOpaque() {
        for (edge in arrayOf(TransitionEdge.LOCK_TO_HOME, TransitionEdge.HOME_TO_LOCK)) {
            for (i in 0..10) {
                assertEquals(1f, clockAlphaAt(edge, i / 10f), 1e-6f)
            }
        }
    }

    /** ★阳性对照：不接这条通道时时钟恒全亮，与真值在中段差近 0.5。 */
    @Test
    fun positiveControlWithoutFadeClockStaysOpaque() {
        val t = 0.72f
        val truth = clockAlphaAt(TransitionEdge.AOD_TO_LOCK, t)
        assertTrue("真值此刻已明显淡出", truth < 0.6f)
        assertTrue("恒全亮与真值差必须显著", 1f - truth > 0.4f)
    }

    /** 窗口边界：出场 0.55 起、0.90 收；入场 0.12 起、0.72 收。 */
    @Test
    fun fadeWindowsMatchTruth() {
        assertEquals(1f, clockAlphaAt(TransitionEdge.AOD_TO_LOCK, 0.55f), 1e-4f)
        assertEquals(0f, clockAlphaAt(TransitionEdge.AOD_TO_LOCK, 0.90f), 1e-4f)
        assertEquals(0f, clockAlphaAt(TransitionEdge.LOCK_TO_AOD, 0.12f), 1e-4f)
        assertEquals(1f, clockAlphaAt(TransitionEdge.LOCK_TO_AOD, 0.72f), 1e-4f)
    }

    @Test
    fun hookConsumesClockAlpha() {
        val b = body(stage(), "withLensClockFade")
        assertTrue("必须读时钟专用通道", b.contains("worldFade.aodClockAlpha"))
        assertTrue("必须走同一份交叠真值", b.contains("SailLensMotion.fade(edge, t, worldFade)"))
    }

    @Test
    fun hookOnlyAppliesToAodSurface() {
        val b = body(stage(), "withLensClockFade")
        assertTrue("只作用息屏面", b.contains("if (surface != EditSurface.AOD || edge == null) return anchor"))
        assertTrue("只镜片款", b.contains("if (!spec.lens) return anchor"))
    }

    /** 只改 alpha：位置缩放拉伸一律不动，避免影响落位对账。 */
    @Test
    fun onlyAlphaIsModified() {
        val b = body(stage(), "withLensClockFade")
        assertTrue(b.contains("anchor.copy(alpha = anchor.alpha * a)"))
        assertFalse("不得动位置", b.contains("centerX ="))
        assertFalse("不得动缩放", b.contains("scale ="))
    }

    @Test
    fun fadeIsAppliedAtCallSite() {
        val s = stage()
        assertTrue(
            "必须套在时钟写入链上",
            s.contains("withLensClockFade(\n            withHandPaintFade(withDrift(base, surface), state, edge), surface, edge, t,\n        )"),
        )
        assertTrue("写入用叠加后的值", s.contains("ClockAnchorApply.apply(clock, state, faded, \"stage\")"))
    }

    /** 与手绘淡入互不冲突：两款互斥，各自只认自己的 spec 标志。 */
    @Test
    fun handPaintAndLensFadesAreExclusive() {
        val s = stage()
        assertTrue(body(s, "withHandPaintFade").contains("if (spec.handPaintStyle <= 0) return anchor"))
        assertTrue(body(s, "withLensClockFade").contains("if (!spec.lens) return anchor"))
    }

    @Test
    fun alphaIsLogged() {
        assertTrue("时钟 alpha 必须可诊断", stage().contains("a=\${\"%.3f\".format(faded.alpha)}"))
    }

    /** 静止态不受影响：edge 为空直接返回原锚点。 */
    @Test
    fun staticStateUnaffected() {
        val b = body(stage(), "withLensClockFade")
        assertTrue(b.contains("edge == null"))
        val f = SailLensMotion.Fade()
        SailLensMotion.fade(null, 0f, f)
        assertEquals(1f, f.aodClockAlpha, 1e-6f)
    }

    /** 满值早退：a>=1 时不复制锚点，省一次分配。 */
    @Test
    fun fullAlphaShortCircuits() {
        assertTrue(body(stage(), "withLensClockFade").contains("if (a >= 1f) return anchor"))
    }
}
