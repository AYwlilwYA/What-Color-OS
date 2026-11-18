package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.AodStageAnchors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 息屏时钟锚点候选：id 优先、类名兜底的契约。
 *
 * 时钟内容可能由插件提供（类名不在本进程类空间），故必须有 id 路径。
 */
class AodClockAnchorCandidateTest {

    @Test
    fun candidateIdsAreFiveAndAllNonEmpty() {
        assertEquals(5, AodStageAnchors.AOD_CLOCK_IDS.size)
        for (n in AodStageAnchors.AOD_CLOCK_IDS) assertTrue("id 名不得为空: $n", n.isNotBlank())
    }

    @Test
    fun candidateIdsHaveNoDuplicates() {
        assertEquals(
            AodStageAnchors.AOD_CLOCK_IDS.size,
            AodStageAnchors.AOD_CLOCK_IDS.toSet().size,
        )
    }

    @Test
    fun innermostClockComesFirstOuterContainerLast() {
        val ids = AodStageAnchors.AOD_CLOCK_IDS
        assertEquals("最内层时钟容器优先", AodStageAnchors.ID_AOD_CLOCK_LAYOUT, ids[0])
        assertEquals("最外层根容器兜底", AodStageAnchors.ID_AOD_CLOCK_ROOT, ids[ids.size - 1])
    }

    @Test
    fun concreteClockViewsPrecedeThemeContainer() {
        val ids = AodStageAnchors.AOD_CLOCK_IDS.toList()
        val defaultIdx = ids.indexOf(AodStageAnchors.ID_AOD_DEFAULT_CLOCK)
        val analogIdx = ids.indexOf(AodStageAnchors.ID_AOD_ANALOG_CLOCK)
        val themeIdx = ids.indexOf(AodStageAnchors.ID_AOD_THEME_LAYOUT)
        assertTrue(defaultIdx in 0 until themeIdx)
        assertTrue(analogIdx in 0 until themeIdx)
    }

    @Test
    fun candidateIdsMatchDecompiledNames() {
        assertEquals("clock_layout", AodStageAnchors.ID_AOD_CLOCK_LAYOUT)
        assertEquals("aod_default_clock_view", AodStageAnchors.ID_AOD_DEFAULT_CLOCK)
        assertEquals("aod_analog_clock_view", AodStageAnchors.ID_AOD_ANALOG_CLOCK)
        assertEquals("aod_global_theme_layout", AodStageAnchors.ID_AOD_THEME_LAYOUT)
        assertEquals("clock_root_view", AodStageAnchors.ID_AOD_CLOCK_ROOT)
    }

    @Test
    fun innerClockSuffixMatchesDecompiledClass() {
        assertEquals(".ClockLayout", AodStageAnchors.SUFFIX_AOD_CLOCK_INNER)
        assertTrue(
            "内层后缀应命中反编译实证的时钟容器类名",
            "com.oplus.systemui.aod.aodclock.off.clock.ClockLayout".endsWith(AodStageAnchors.SUFFIX_AOD_CLOCK_INNER),
        )
    }

    @Test
    fun hostContainerSuffixStillMatches() {
        assertTrue(
            "com.oplus.systemui.aod.aodclock.off.AodClockLayout".endsWith(AodStageAnchors.SUFFIX_AOD_CLOCK),
        )
    }

    @Test
    fun innerSuffixDoesNotMatchHostContainer() {
        assertFalse(
            "宿主容器类名不该被内层后缀命中，否则优先级失效",
            "com.oplus.systemui.aod.aodclock.off.AodClockLayout".endsWith(AodStageAnchors.SUFFIX_AOD_CLOCK_INNER),
        )
    }

    @Test
    fun hostSuffixDoesNotMatchInnerClock() {
        assertFalse(
            "com.oplus.systemui.aod.aodclock.off.clock.ClockLayout".endsWith(AodStageAnchors.SUFFIX_AOD_CLOCK),
        )
    }

    @Test
    fun pluginProvidedClassNamesMatchNeitherSuffix() {
        val pluginNames = arrayOf(
            "com.oplus.aod.clock.SomePluginClockView",
            "a.b.c",
            "com.oplusos.aodplugin.view.ClockContainer",
        )
        for (n in pluginNames) {
            assertFalse("插件类名 $n 不该被宿主后缀命中", n.endsWith(AodStageAnchors.SUFFIX_AOD_CLOCK))
            assertFalse("插件类名 $n 不该被内层后缀命中", n.endsWith(AodStageAnchors.SUFFIX_AOD_CLOCK_INNER))
        }
    }

    @Test
    fun positiveControlClassOnlyStrategyMissesPluginClock() {
        val pluginClock = "com.oplus.aod.plugin.RealClockView"
        val byClass = pluginClock.endsWith(AodStageAnchors.SUFFIX_AOD_CLOCK) ||
            pluginClock.endsWith(AodStageAnchors.SUFFIX_AOD_CLOCK_INNER)
        assertFalse("★纯类名策略对插件时钟必 miss —— 这就是真机 stage-static-noclock 的根因", byClass)
        assertTrue("但 id 路径与类名无关，故 id 优先能救", AodStageAnchors.AOD_CLOCK_IDS.isNotEmpty())
    }

    @Test
    fun anchorIdsDoNotCollideWithStageIds() {
        for (n in AodStageAnchors.AOD_CLOCK_IDS) {
            assertFalse("时钟候选 id 不该与舞台 id 重名: $n", AodStageAnchors.IDS.contains(n))
        }
    }

    @Test
    fun allStageIdsStillSix() {
        assertEquals("舞台 id 表不受本次新增影响", 6, AodStageAnchors.IDS.size)
    }

    @Test
    fun secureSuffixesUnaffected() {
        assertEquals(5, AodStageAnchors.SECURE_SUFFIXES.size)
    }
}
