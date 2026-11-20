package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.ClockStateMachine
import com.coloros16.liquidglass.engine.ClockAnchor
import com.coloros16.liquidglass.engine.Easing
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.SystemSceneMotion
import com.coloros16.liquidglass.engine.TransitionEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 时钟位移曲线契约：hook 与预览必须按边取同一条系统曲线，不吃状态机默认曲线。
 */
class ClockTransitionCurveContractTest {

    private fun stage(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/hook/aod/AodStageHook.kt",
    ).readText()

    private fun preview(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodPreviewView.kt",
    ).readText()

    private fun machine(): ClockStateMachine = ClockStateMachine().apply {
        setAnchor(SceneState.AOD, ClockAnchor(centerX = 0.5f, centerY = 0.435f), 0L, false)
        setAnchor(SceneState.LOCK, ClockAnchor(centerX = 0.36f, centerY = 0.155f), 0L, false)
        setAnchor(SceneState.HOME, ClockAnchor(centerX = 0.13f, centerY = 0.021f), 0L, false)
    }

    private fun yAt(edge: TransitionEdge, t: Float, curve: Easing): Float =
        machine().apply { onTransition(edge, t, curve) }.pose.centerY

    @Test
    fun unlockEdgesUseWallpaperCurve() {
        assertSame(SystemSceneMotion.WALLPAPER, SystemSceneMotion.clockCurve(TransitionEdge.LOCK_TO_HOME))
        assertSame(SystemSceneMotion.WALLPAPER, SystemSceneMotion.clockCurve(TransitionEdge.HOME_TO_LOCK))
    }

    @Test
    fun otherFourEdgesUseMaskCurve() {
        for (e in arrayOf(
            TransitionEdge.AOD_TO_LOCK, TransitionEdge.LOCK_TO_AOD,
            TransitionEdge.AOD_TO_HOME, TransitionEdge.HOME_TO_AOD,
        )) {
            assertSame("边 $e 走蒙版曲线", SystemSceneMotion.MASK, SystemSceneMotion.clockCurve(e))
        }
    }

    /** 默认曲线与两条系统曲线都不同 ⇒ 不传曲线就是错的位置。 */
    @Test
    fun defaultCurveDiffersFromBothSystemCurves() {
        val t = 0.3f
        val def = Easing.STANDARD.map(t)
        assertNotEquals(def, SystemSceneMotion.MASK.map(t), 1e-4f)
        assertNotEquals(def, SystemSceneMotion.WALLPAPER.map(t), 1e-4f)
    }

    /** 阳性对照：默认曲线让时钟提前跑到位，最大偏差约半程。 */
    @Test
    fun positiveControlDefaultCurveRunsAhead() {
        val edge = TransitionEdge.AOD_TO_LOCK
        var maxDiff = 0f
        for (i in 1..9) {
            val t = i / 10f
            val wrong = yAt(edge, t, Easing.STANDARD)
            val right = yAt(edge, t, SystemSceneMotion.clockCurve(edge))
            maxDiff = maxOf(maxDiff, kotlin.math.abs(wrong - right))
        }
        assertTrue("偏差必须大到可见（>0.1 屏高）", maxDiff > 0.1f)
    }

    /** 解锁边偏差同样显著，且与蒙版曲线也不等价（三条曲线互不相同）。 */
    @Test
    fun positiveControlUnlockEdgeMaskCurveAlsoWrong() {
        val edge = TransitionEdge.LOCK_TO_HOME
        val t = 0.3f
        val right = yAt(edge, t, SystemSceneMotion.clockCurve(edge))
        val mask = yAt(edge, t, SystemSceneMotion.MASK)
        val def = yAt(edge, t, Easing.STANDARD)
        assertTrue(kotlin.math.abs(right - def) > 0.05f)
        assertNotEquals("解锁边不能套蒙版曲线", right, mask, 1e-4f)
    }

    /** 端点必须一致：曲线只改中间过程，不改起止落位。 */
    @Test
    fun endpointsUnaffectedByCurveChoice() {
        for (edge in TransitionEdge.values()) {
            for (t in arrayOf(0f, 1f)) {
                val a = machine().apply {
                    onTransition(edge, t, SystemSceneMotion.clockCurve(edge))
                }.pose.centerY
                val b = machine().apply { onTransition(edge, t, Easing.STANDARD) }.pose.centerY
                assertEquals("边 $edge t=$t 端点必须相同", a, b, 1e-5f)
            }
        }
    }

    @Test
    fun hookPassesPerEdgeCurve() {
        val s = stage()
        assertTrue(
            "hook 必须按边取曲线",
            s.contains("machine.onTransition(edge, t, SystemSceneMotion.clockCurve(edge))"),
        )
    }

    /** 阳性对照：不带第三参的重载会落到状态机默认曲线。 */
    @Test
    fun positiveControlTwoArgOverloadIsDefaultCurve() {
        val s = stage()
        assertTrue("禁止用两参重载", !s.contains("machine.onTransition(edge, t)\n"))
        val m = machine()
        m.onTransition(TransitionEdge.AOD_TO_LOCK, 0.3f)
        val twoArg = m.pose.centerY
        assertEquals(yAt(TransitionEdge.AOD_TO_LOCK, 0.3f, Easing.STANDARD), twoArg, 1e-6f)
    }

    @Test
    fun previewPassesSameCurve() {
        assertTrue(
            preview().contains("clockMachine.onTransition(edge, this.t, SystemSceneMotion.clockCurve(edge))"),
        )
    }

    @Test
    fun curveChangeIsLogged() {
        val s = stage()
        assertTrue("曲线选择必须有诊断日志", s.contains("stage-clock-curve"))
        assertTrue(s.contains("clockCurveName"))
    }

    @Test
    fun curveNameDistinguishesTwoFamilies() {
        val s = stage()
        assertTrue(s.contains("\"wallpaper\""))
        assertTrue(s.contains("\"mask\""))
        assertTrue(s.contains("=== SystemSceneMotion.WALLPAPER"))
    }

    /** 状态机默认曲线仍是 STANDARD（不改默认，避免影响其它调用方）。 */
    @Test
    fun stateMachineDefaultUnchanged() {
        val m = machine()
        m.onTransition(TransitionEdge.AOD_TO_LOCK, 0.3f)
        assertEquals(
            yAt(TransitionEdge.AOD_TO_LOCK, 0.3f, Easing.STANDARD), m.pose.centerY, 1e-6f,
        )
    }
}
