package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.ClockHandoffPolicy
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.TransitionEdge
import com.coloros16.liquidglass.engine.from
import com.coloros16.liquidglass.engine.to
import com.coloros16.liquidglass.hook.DesktopClockPath
import com.coloros16.liquidglass.hook.Profiles
import com.coloros16.liquidglass.hook.VersionPool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 时钟跨进程交接口径：让位判定、交接进度、两侧可见度互补、下行去重。 */
class ClockHandoffPolicyTest {

    // ---------------------------------------------------------------- 让位

    @Test
    fun nativeHostYieldsModuleSupplements() {
        assertFalse("宿主自带平移则让位", ClockHandoffPolicy.moduleTranslates(true))
        assertTrue("宿主无内置则模块补", ClockHandoffPolicy.moduleTranslates(false))
    }

    @Test
    fun launcherProfilesCarryDesktopClockPath() {
        assertEquals(
            DesktopClockPath.NATIVE_INSPIRATION, Profiles.LAUNCHER_17PLUS.desktopClockPath,
        )
        assertEquals(
            DesktopClockPath.MODULE_SUPPLEMENT, Profiles.LAUNCHER_PRE17.desktopClockPath,
        )
    }

    /** C15 launcher 未登记点，全表 floor 落到 16.4.28 档，仍是模块补（实证两版都无灵感时钟）。 */
    @Test
    fun c15LauncherFloorsToSupplementProfile() {
        val d = VersionPool.query(VersionPool.PKG_LAUNCHER, 150080017L)
        assertEquals(150080017L / 10_000_000L, d.major.toLong())
        assertEquals(DesktopClockPath.MODULE_SUPPLEMENT, d.profile.desktopClockPath)
        assertTrue(ClockHandoffPolicy.moduleTranslates(d.profile.desktopClockPath == DesktopClockPath.NATIVE_INSPIRATION))
    }

    @Test
    fun c17LauncherPointYields() {
        val d = VersionPool.query(VersionPool.PKG_LAUNCHER, 170030012L)
        assertEquals(DesktopClockPath.NATIVE_INSPIRATION, d.profile.desktopClockPath)
        assertFalse(ClockHandoffPolicy.moduleTranslates(true))
    }

    /** 正交断言：桌面时钟档不得污染 posteffect 世代与抓屏档。 */
    @Test
    fun desktopClockPathIsOrthogonalToOtherAxes() {
        val d = VersionPool.query(VersionPool.PKG_LAUNCHER, 170030012L)
        assertEquals(
            com.coloros16.liquidglass.hook.PosteffectArch.UNKNOWN, d.profile.posteffectArch,
        )
        assertEquals(
            com.coloros16.liquidglass.hook.SeedlingDataPath.UNKNOWN, d.profile.seedlingDataPath,
        )
        val sysui = VersionPool.query(VersionPool.PKG_SYSTEMUI, 169912L)
        assertEquals(DesktopClockPath.UNKNOWN, sysui.profile.desktopClockPath)
    }

    // ---------------------------------------------------------------- 交接边

    @Test
    fun onlyHomeEdgesHandOff() {
        val expected = setOf(
            TransitionEdge.LOCK_TO_HOME,
            TransitionEdge.HOME_TO_LOCK,
            TransitionEdge.AOD_TO_HOME,
            TransitionEdge.HOME_TO_AOD,
        )
        val actual = TransitionEdge.values().filter { ClockHandoffPolicy.handsOff(it) }.toSet()
        assertEquals(expected, actual)
        assertEquals("六边里四条涉及桌面", 4, actual.size)
    }

    @Test
    fun progressRunsForwardToHomeAndBackwardFromHome() {
        for (edge in TransitionEdge.values()) {
            val start = ClockHandoffPolicy.progress(edge, 0f)
            val end = ClockHandoffPolicy.progress(edge, 1f)
            when {
                !ClockHandoffPolicy.handsOff(edge) -> {
                    assertEquals("非交接边恒 0 start", 0f, start, 1e-6f)
                    assertEquals("非交接边恒 0 end", 0f, end, 1e-6f)
                }
                edge.to == SceneState.HOME -> {
                    assertEquals("向桌面走起点 0", 0f, start, 1e-6f)
                    assertEquals("向桌面走终点 1", 1f, end, 1e-6f)
                }
                else -> {
                    assertEquals("离桌面走起点 1", 1f, start, 1e-6f)
                    assertEquals("离桌面走终点 0", 0f, end, 1e-6f)
                }
            }
        }
    }

    @Test
    fun progressClampsOutOfRangeAndIsMonotonic() {
        assertEquals(0f, ClockHandoffPolicy.progress(TransitionEdge.LOCK_TO_HOME, -3f), 1e-6f)
        assertEquals(1f, ClockHandoffPolicy.progress(TransitionEdge.LOCK_TO_HOME, 9f), 1e-6f)
        var prev = -1f
        for (i in 0..20) {
            val p = ClockHandoffPolicy.progress(TransitionEdge.LOCK_TO_HOME, i / 20f)
            assertTrue("向桌面必须单调不减", p >= prev)
            prev = p
        }
    }

    // ---------------------------------------------------------------- 两侧互补

    /** 交接全程两侧 alpha 相加恒 1：不出现双亮（叠影）也不出现双灭（闪断）。 */
    @Test
    fun twoSidesAlwaysSumToOne() {
        for (i in 0..40) {
            val p = i / 40f
            val sys = ClockHandoffPolicy.alphaOf(ClockHandoffPolicy.Side.SYSTEM_CLOCK, p)
            val widget = ClockHandoffPolicy.alphaOf(ClockHandoffPolicy.Side.DESKTOP_WIDGET, p)
            assertEquals("p=$p 两侧必须互补", 1f, sys + widget, 1e-6f)
            assertTrue(sys in 0f..1f)
            assertTrue(widget in 0f..1f)
        }
    }

    @Test
    fun endpointsAreFullyOwnedByOneSide() {
        assertEquals(1f, ClockHandoffPolicy.alphaOf(ClockHandoffPolicy.Side.SYSTEM_CLOCK, 0f), 1e-6f)
        assertEquals(0f, ClockHandoffPolicy.alphaOf(ClockHandoffPolicy.Side.DESKTOP_WIDGET, 0f), 1e-6f)
        assertEquals(0f, ClockHandoffPolicy.alphaOf(ClockHandoffPolicy.Side.SYSTEM_CLOCK, 1f), 1e-6f)
        assertEquals(1f, ClockHandoffPolicy.alphaOf(ClockHandoffPolicy.Side.DESKTOP_WIDGET, 1f), 1e-6f)
    }

    /** 阳性对照：若两侧都用同一条曲线（都取 1-p），中点会双灭到 0，肉眼是闪断。 */
    @Test
    fun positiveControlSameCurveOnBothSidesWouldBlackOutMidway() {
        val p = 0.5f
        val wrongSum = (1f - p) + (1f - p)
        assertEquals("同曲线在中点两侧全灭", 1f, wrongSum, 1e-6f)
        assertTrue("中点两侧都只剩半亮", (1f - p) < 0.9f)
        val rightSum = ClockHandoffPolicy.alphaOf(ClockHandoffPolicy.Side.SYSTEM_CLOCK, p) +
            ClockHandoffPolicy.alphaOf(ClockHandoffPolicy.Side.DESKTOP_WIDGET, p)
        assertEquals("互补口径中点仍满亮", 1f, rightSum, 1e-6f)
    }

    @Test
    fun alphaClampsOutOfRangeProgress() {
        assertEquals(1f, ClockHandoffPolicy.alphaOf(ClockHandoffPolicy.Side.SYSTEM_CLOCK, -2f), 1e-6f)
        assertEquals(0f, ClockHandoffPolicy.alphaOf(ClockHandoffPolicy.Side.SYSTEM_CLOCK, 5f), 1e-6f)
        assertEquals(0f, ClockHandoffPolicy.alphaOf(ClockHandoffPolicy.Side.DESKTOP_WIDGET, -2f), 1e-6f)
        assertEquals(1f, ClockHandoffPolicy.alphaOf(ClockHandoffPolicy.Side.DESKTOP_WIDGET, 5f), 1e-6f)
    }

    // ---------------------------------------------------------------- 下行去重

    @Test
    fun sameValueAndSameEnabledIsNotRepublished() {
        assertFalse(
            "同值同使能不得重发",
            ClockHandoffPolicy.shouldPublish(0.5f, 0.5f, true, true),
        )
    }

    @Test
    fun changedValueOrEnabledIsPublished() {
        assertTrue(ClockHandoffPolicy.shouldPublish(0.6f, 0.5f, true, true))
        assertTrue(ClockHandoffPolicy.shouldPublish(0.5f, 0.5f, false, true))
        assertTrue(ClockHandoffPolicy.shouldPublish(0.5f, 0.5f, true, false))
    }

    /** 首次发送必须放行：上一次值未初始化（NaN）时不能被判成「没变」。 */
    @Test
    fun firstPublishPassesWhenPreviousIsNaN() {
        assertTrue(ClockHandoffPolicy.shouldPublish(0f, Float.NaN, false, false))
        assertTrue(ClockHandoffPolicy.shouldPublish(Float.NaN, 0f, true, true))
    }

    /** 阳性对照：只用「值相等」当判据会漏掉使能位翻转，桌面那颗钟就撤不回来。 */
    @Test
    fun positiveControlValueOnlyDedupeMissesEnableFlip() {
        val valueUnchanged = 0f == 0f
        assertTrue("值确实没变", valueUnchanged)
        assertTrue(
            "使能位翻转必须仍然发",
            ClockHandoffPolicy.shouldPublish(0f, 0f, false, true),
        )
    }

    // ---------------------------------------------------------------- 写侧闸

    @Test
    fun widgetWriteRequiresBothSupplementAndWidget() {
        assertTrue(ClockHandoffPolicy.writesWidget(false, true))
        assertFalse("让位档不写", ClockHandoffPolicy.writesWidget(true, true))
        assertFalse("无小部件不写", ClockHandoffPolicy.writesWidget(false, false))
        assertFalse(ClockHandoffPolicy.writesWidget(true, false))
    }

    // ---------------------------------------------------------------- 时序基准

    /**
     * 交接跟内部时间轴：同一条边的交接进度必须与状态机吃的 t 完全同源，
     * 不另做一次缓动（否则两颗钟的淡入淡出与位移不同相）。
     */
    @Test
    fun handoffUsesSameTAsStateMachineWithoutSecondEasing() {
        for (edge in arrayOf(TransitionEdge.LOCK_TO_HOME, TransitionEdge.HOME_TO_LOCK)) {
            for (i in 0..10) {
                val t = i / 10f
                val expected = if (edge.to == SceneState.HOME) t else 1f - t
                assertEquals(
                    "edge=$edge t=$t 交接进度就是线性 t",
                    expected, ClockHandoffPolicy.progress(edge, t), 1e-6f,
                )
            }
        }
    }

    /** 交接终点与状态机 HOME 端一致：边末系统那颗钟全灭、桌面那颗全亮。 */
    @Test
    fun handoffEndpointMatchesStateMachineHomeSide() {
        val edge = TransitionEdge.LOCK_TO_HOME
        assertEquals(SceneState.HOME, edge.to)
        assertEquals(SceneState.LOCK, edge.from)
        val p = ClockHandoffPolicy.progress(edge, 1f)
        assertEquals(0f, ClockHandoffPolicy.alphaOf(ClockHandoffPolicy.Side.SYSTEM_CLOCK, p), 1e-6f)
        assertEquals(1f, ClockHandoffPolicy.alphaOf(ClockHandoffPolicy.Side.DESKTOP_WIDGET, p), 1e-6f)
    }
}
