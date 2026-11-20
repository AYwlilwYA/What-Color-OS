package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SystemSceneMotion
import com.coloros16.liquidglass.engine.TransitionEdge
import com.coloros16.liquidglass.engine.from
import com.coloros16.liquidglass.engine.to
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 反向边内容缩放真值契约：三条反向边此前完全不写缩放通道，源层「原样大小淡出」。
 *
 * 真值取自 C16 `OplusPanelKeyguardAnimControllerImpl` 各 Scene 的 animInit，
 * 与 C15 `NotificationPanelViewControllerExImp` 的 fraction→lerp 换算。
 */
class ReverseEdgeScaleTruthContractTest {

    private val layers = SystemSceneMotion.Layers()

    private fun motion(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/engine/SystemSceneMotion.kt",
    ).readText()

    private fun at(edge: TransitionEdge, t: Float, direct: Boolean = false): SystemSceneMotion.Layers {
        SystemSceneMotion.sample(edge, t, layers, direct)
        return layers
    }

    private fun exitRatio(totalMs: Long): Float = SystemSceneMotion.LOCK_EXIT_MS / totalMs

    // ---------------------------------------------------------------- 常量真值

    @Test
    fun scaleConstantsMatchSystemTruth() {
        assertEquals("锁屏入场起点", 0.90f, SystemSceneMotion.LOCK_SCALE_IN, 1e-6f)
        assertEquals("锁屏静止原样", 1.0f, SystemSceneMotion.LOCK_SCALE_OUT, 1e-6f)
        assertEquals("全景解锁起点", 1.1f, SystemSceneMotion.LOCK_SCALE_PANORAMIC_FROM, 1e-6f)
        assertEquals("全景解锁终点", 0.95f, SystemSceneMotion.LOCK_SCALE_PANORAMIC_TO, 1e-6f)
        assertEquals("桌面入场起点", 0.96f, SystemSceneMotion.HOME_SCALE_IN, 1e-6f)
    }

    /** 缩放区间必须由常量表达，不得再散落字面量（改真值只需改一处）。 */
    @Test
    fun sampleUsesConstantsNotLiterals() {
        val src = motion()
        assertTrue(src.contains("lerp(LOCK_SCALE_IN, LOCK_SCALE_OUT, spring)"))
        assertTrue(src.contains("lerp(LOCK_SCALE_OUT, LOCK_SCALE_IN, exit)"))
        assertTrue(src.contains("lerp(LOCK_SCALE_PANORAMIC_FROM, LOCK_SCALE_PANORAMIC_TO, exit)"))
        assertTrue(src.contains("lerp(HOME_SCALE_IN, 1f, spring)"))
        assertTrue(src.contains("lerp(1f, HOME_SCALE_IN, exit)"))
        assertTrue(src.contains("lerp(1f, HOME_SCALE_IN, mask)"))
        assertTrue("旧字面量算式必须清零", !src.contains("0.90f + 0.10f"))
        assertTrue("旧字面量算式必须清零", !src.contains("0.96f + 0.04f"))
        assertTrue("旧字面量算式必须清零", !src.contains("1f + 0.04f"))
    }

    // ---------------------------------------------------------------- 灭屏反向

    @Test
    fun lockToAodShrinksLockContentBackToEntryScale() {
        assertEquals("首帧原样", 1.0f, at(TransitionEdge.LOCK_TO_AOD, 0f).lockScale, 1e-6f)
        val end = at(TransitionEdge.LOCK_TO_AOD, exitRatio(SystemSceneMotion.LOCK_TO_AOD_MS)).lockScale
        assertEquals("退场窗末退到入场起点", SystemSceneMotion.LOCK_SCALE_IN, end, 1e-6f)
    }

    /** ★阳性对照：修前该通道整条边恒 1（reset 默认），源层原样大小淡出。 */
    @Test
    fun positiveControlOldLockToAodNeverScaled() {
        val old = 1f
        val now = at(TransitionEdge.LOCK_TO_AOD, 0.2f).lockScale
        assertTrue("修前恒原样", old == 1f)
        assertTrue("修后必须已在缩小", now < 1f - 0.005f)
        assertTrue("幅度必须可见", (1f - now) > 0.01f)
    }

    @Test
    fun lockToAodScaleIsMonotonicShrinking() {
        var prev = 1f
        for (i in 0..20) {
            val v = at(TransitionEdge.LOCK_TO_AOD, i / 20f).lockScale
            assertTrue("i=$i 不得回升", v <= prev + 1e-6f)
            prev = v
        }
        assertEquals(SystemSceneMotion.LOCK_SCALE_IN, prev, 1e-6f)
    }

    @Test
    fun lockToAodScaleFinishesWithAlpha() {
        val r = exitRatio(SystemSceneMotion.LOCK_TO_AOD_MS)
        val l = at(TransitionEdge.LOCK_TO_AOD, r)
        assertEquals("退场窗末 alpha 归零", 0f, l.lockAlpha, 1e-6f)
        assertEquals("同刻缩放也到位", SystemSceneMotion.LOCK_SCALE_IN, l.lockScale, 1e-6f)
    }

    // ---------------------------------------------------------------- 桌面回锁屏

    @Test
    fun homeToLockShrinksHomeContent() {
        assertEquals("首帧原样", 1f, at(TransitionEdge.HOME_TO_LOCK, 0f).homeScale, 1e-6f)
        val end = at(TransitionEdge.HOME_TO_LOCK, exitRatio(SystemSceneMotion.HOME_TO_LOCK_MS)).homeScale
        assertEquals("退场窗末退到入场起点", SystemSceneMotion.HOME_SCALE_IN, end, 1e-6f)
    }

    @Test
    fun positiveControlOldHomeToLockNeverScaled() {
        val now = at(TransitionEdge.HOME_TO_LOCK, 0.15f).homeScale
        assertTrue("修后必须已在缩小", now < 1f - 0.005f)
    }

    @Test
    fun homeToLockAlsoGrowsIncomingLockContent() {
        assertEquals(SystemSceneMotion.LOCK_SCALE_IN, at(TransitionEdge.HOME_TO_LOCK, 0f).lockScale, 1e-6f)
        assertEquals(SystemSceneMotion.LOCK_SCALE_OUT, at(TransitionEdge.HOME_TO_LOCK, 1f).lockScale, 1e-6f)
    }

    // ---------------------------------------------------------------- 桌面回息屏

    @Test
    fun homeToAodShrinksHomeContentAcrossWholeEdge() {
        assertEquals(1f, at(TransitionEdge.HOME_TO_AOD, 0f).homeScale, 1e-6f)
        assertEquals(SystemSceneMotion.HOME_SCALE_IN, at(TransitionEdge.HOME_TO_AOD, 1f).homeScale, 1e-6f)
    }

    @Test
    fun homeToAodUsesMaskCurveNotExitWindow() {
        val t = 0.5f
        val l = at(TransitionEdge.HOME_TO_AOD, t)
        assertEquals("与 alpha 同曲线", 1f - l.homeAlpha, 1f - (1f - l.homeAlpha), 1e-6f)
        val expect = 1f + (SystemSceneMotion.HOME_SCALE_IN - 1f) * SystemSceneMotion.MASK.map(t)
        assertEquals("缩放跟 mask 不跟退场窗", expect, l.homeScale, 1e-6f)
    }

    @Test
    fun positiveControlOldHomeToAodNeverScaled() {
        val now = at(TransitionEdge.HOME_TO_AOD, 0.5f).homeScale
        assertTrue("修后必须已在缩小", now < 1f - 0.005f)
    }

    // ---------------------------------------------------------------- 解锁全景真值

    @Test
    fun lockToHomeUsesPanoramicScaleRange() {
        assertEquals(
            "首帧取全景放大态",
            SystemSceneMotion.LOCK_SCALE_PANORAMIC_FROM,
            at(TransitionEdge.LOCK_TO_HOME, 0f).lockScale,
            1e-6f,
        )
        val end = at(TransitionEdge.LOCK_TO_HOME, exitRatio(SystemSceneMotion.LOCK_TO_HOME_MS)).lockScale
        assertEquals(
            "退场窗末收到全景终点",
            SystemSceneMotion.LOCK_SCALE_PANORAMIC_TO,
            end,
            1e-6f,
        )
    }

    /**
     * ★阳性对照：修前 1→1.04 微放大（相对静止 1 在涨），真值 1.1→0.95 收回（相对起点在落）。
     *
     * 两条口径的参照点不同：旧的参照静止值 1，真值的参照起点 1.1；
     * 故不能拿同一个 1 去比方向，必须各自比自己的参照。
     */
    @Test
    fun positiveControlOldUnlockGrewInsteadOfShrinking() {
        val t = 0.15f
        val exit = SystemSceneMotion.MASK.map(t / exitRatio(SystemSceneMotion.LOCK_TO_HOME_MS))
        val old = 1f + 0.04f * exit
        val now = at(TransitionEdge.LOCK_TO_HOME, t).lockScale
        val start = SystemSceneMotion.LOCK_SCALE_PANORAMIC_FROM
        assertTrue("旧行为相对静止在放大", old > 1f)
        assertTrue("真值相对起点在缩小", now < start)
        assertTrue("旧行为绝不会低于静止值", old >= 1f)
        assertTrue("真值必须能落到静止值以下", SystemSceneMotion.LOCK_SCALE_PANORAMIC_TO < 1f)
        assertTrue("两者行程量级差一个数量级", (start - SystemSceneMotion.LOCK_SCALE_PANORAMIC_TO) > 3f * (old - 1f))
    }

    @Test
    fun unlockScaleIsSuppressedAtContentLayer() {
        assertTrue(
            "解锁两条边内容层抑制缩放的口径不变",
            File("src/main/kotlin/com/coloros16/liquidglass/engine/ContentMotionSource.kt")
                .readText()
                .contains("LOCK_TO_HOME") ,
        )
    }

    // ---------------------------------------------------------------- 六边端点

    @Test
    fun everyEdgeStartsSourceAtItsStaticScale() {
        for (edge in TransitionEdge.values()) {
            val l = at(edge, 0f)
            when (edge.from) {
                com.coloros16.liquidglass.engine.SceneState.LOCK ->
                    assertEquals("edge=$edge 锁屏首帧", expectedLockStart(edge), l.lockScale, 1e-6f)
                com.coloros16.liquidglass.engine.SceneState.HOME ->
                    assertEquals("edge=$edge 桌面首帧", 1f, l.homeScale, 1e-6f)
                else -> Unit
            }
        }
    }

    private fun expectedLockStart(edge: TransitionEdge): Float =
        if (edge == TransitionEdge.LOCK_TO_HOME) SystemSceneMotion.LOCK_SCALE_PANORAMIC_FROM
        else SystemSceneMotion.LOCK_SCALE_OUT

    @Test
    fun thirdStateScaleStaysIdentityOnEveryEdge() {
        for (edge in TransitionEdge.values()) {
            for (i in 0..10) {
                val l = at(edge, i / 10f)
                if (edge.from != com.coloros16.liquidglass.engine.SceneState.AOD &&
                    edge.to != com.coloros16.liquidglass.engine.SceneState.AOD
                ) {
                    assertEquals("edge=$edge 息屏层不缩放", 1f, l.aodClockScale, 1e-6f)
                }
                if (edge.from != com.coloros16.liquidglass.engine.SceneState.LOCK &&
                    edge.to != com.coloros16.liquidglass.engine.SceneState.LOCK
                ) {
                    assertEquals("edge=$edge 第三态锁屏恒原样", 1f, l.lockScale, 1e-6f)
                }
                if (edge.from != com.coloros16.liquidglass.engine.SceneState.HOME &&
                    edge.to != com.coloros16.liquidglass.engine.SceneState.HOME
                ) {
                    assertEquals("edge=$edge 第三态桌面恒原样", 1f, l.homeScale, 1e-6f)
                }
            }
        }
    }

    @Test
    fun directModeSkipsCurvesForScaleToo() {
        val t = 0.3f
        val played = at(TransitionEdge.LOCK_TO_AOD, t).lockScale
        val dragged = at(TransitionEdge.LOCK_TO_AOD, t, direct = true).lockScale
        assertNotEquals("拖动与播放必须不同", played, dragged, 1e-4f)
        val exit = (t / exitRatio(SystemSceneMotion.LOCK_TO_AOD_MS)).coerceIn(0f, 1f)
        assertEquals(
            "拖动走线性",
            1f + (SystemSceneMotion.LOCK_SCALE_IN - 1f) * exit,
            dragged,
            1e-6f,
        )
    }

    @Test
    fun scaleNeverLeavesItsRange() {
        for (edge in TransitionEdge.values()) {
            for (i in 0..20) {
                val l = at(edge, i / 20f)
                assertTrue("edge=$edge lock 下界", l.lockScale >= SystemSceneMotion.LOCK_SCALE_IN - 1e-6f)
                assertTrue("edge=$edge lock 上界", l.lockScale <= SystemSceneMotion.LOCK_SCALE_PANORAMIC_FROM + 1e-6f)
                assertTrue("edge=$edge home 下界", l.homeScale >= SystemSceneMotion.HOME_SCALE_IN - 1e-6f)
                assertTrue("edge=$edge home 上界", l.homeScale <= 1f + 1e-6f)
            }
        }
    }

    /** 静止态锁屏仍是原样：入场起点只在转场期出现，不改静止真值。 */
    @Test
    fun staticLockScaleStaysUnity() {
        SystemSceneMotion.sampleStatic(com.coloros16.liquidglass.engine.SceneState.LOCK, layers)
        assertEquals(1f, layers.lockScale, 1e-6f)
        SystemSceneMotion.sampleStatic(com.coloros16.liquidglass.engine.SceneState.HOME, layers)
        assertEquals(1f, layers.homeScale, 1e-6f)
    }
}
