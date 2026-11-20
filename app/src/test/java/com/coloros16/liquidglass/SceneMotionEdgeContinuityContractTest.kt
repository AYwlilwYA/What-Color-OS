package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.SystemSceneMotion
import com.coloros16.liquidglass.engine.WallpaperZoomSettle
import com.coloros16.liquidglass.engine.TransitionEdge
import com.coloros16.liquidglass.engine.from
import com.coloros16.liquidglass.engine.to
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 六条有向边的分层 continuity 契约：端点必须与目标态静止值相接，
 * 反向边与正向边在同一起止态上必须给出同一组端值。
 */
class SceneMotionEdgeContinuityContractTest {

    private val layers = SystemSceneMotion.Layers()

    private fun staticOf(state: SceneState): SystemSceneMotion.Layers {
        val l = SystemSceneMotion.Layers()
        SystemSceneMotion.sampleStatic(state, l)
        return l
    }

    private fun at(edge: TransitionEdge, t: Float, direct: Boolean = false): SystemSceneMotion.Layers {
        val l = SystemSceneMotion.Layers()
        SystemSceneMotion.sample(edge, t, l, direct)
        return l
    }

    @Test
    fun everyEdgeStartMatchesSourceStaticDim() {
        for (edge in TransitionEdge.values()) {
            val start = at(edge, 0f)
            val src = staticOf(edge.from)
            assertEquals("edge=$edge 起点压暗必须等于源态静止值", src.dim, start.dim, 1e-4f)
        }
    }

    @Test
    fun everyEdgeEndMatchesTargetStaticDim() {
        for (edge in TransitionEdge.values()) {
            val end = at(edge, 1f)
            val dst = staticOf(edge.to)
            assertEquals("edge=$edge 终点压暗必须等于目标态静止值", dst.dim, end.dim, 1e-4f)
        }
    }

    @Test
    fun everyEdgeStartSourceLayerFullyVisible() {
        for (edge in TransitionEdge.values()) {
            val start = at(edge, 0f)
            val alpha = when (edge.from) {
                SceneState.AOD -> start.aodAlpha
                SceneState.LOCK -> start.lockAlpha
                SceneState.HOME -> start.homeAlpha
            }
            assertEquals("edge=$edge 起点源层必须全显", 1f, alpha, 1e-4f)
        }
    }

    @Test
    fun everyEdgeEndTargetLayerFullyVisible() {
        for (edge in TransitionEdge.values()) {
            val end = at(edge, 1f)
            val alpha = when (edge.to) {
                SceneState.AOD -> end.aodAlpha
                SceneState.LOCK -> end.lockAlpha
                SceneState.HOME -> end.homeAlpha
            }
            assertEquals("edge=$edge 终点目标层必须全显", 1f, alpha, 1e-4f)
        }
    }

    @Test
    fun everyEdgeEndSourceLayerHidden() {
        for (edge in TransitionEdge.values()) {
            val end = at(edge, 1f)
            val alpha = when (edge.from) {
                SceneState.AOD -> end.aodAlpha
                SceneState.LOCK -> end.lockAlpha
                SceneState.HOME -> end.homeAlpha
            }
            assertEquals("edge=$edge 终点源层必须隐去", 0f, alpha, 1e-4f)
        }
    }

    @Test
    fun forwardAndReverseAgreeOnEndpoints() {
        val pairs = listOf(
            TransitionEdge.AOD_TO_LOCK to TransitionEdge.LOCK_TO_AOD,
            TransitionEdge.LOCK_TO_HOME to TransitionEdge.HOME_TO_LOCK,
            TransitionEdge.AOD_TO_HOME to TransitionEdge.HOME_TO_AOD,
        )
        for ((fwd, rev) in pairs) {
            val f0 = at(fwd, 0f)
            val r1 = at(rev, 1f)
            assertEquals("$fwd 起点 vs $rev 终点 压暗", f0.dim, r1.dim, 1e-4f)
            assertEquals("$fwd 起点 vs $rev 终点 息屏层", f0.aodAlpha, r1.aodAlpha, 1e-4f)
            val f1 = at(fwd, 1f)
            val r0 = at(rev, 0f)
            assertEquals("$rev 起点 vs $fwd 终点 压暗", f1.dim, r0.dim, 1e-4f)
        }
    }

    @Test
    fun dimMonotonicOnEveryEdge() {
        for (edge in TransitionEdge.values()) {
            val src = staticOf(edge.from).dim
            val dst = staticOf(edge.to).dim
            var last = src
            for (i in 0..20) {
                val t = i / 20f
                val d = at(edge, t).dim
                if (dst < src) {
                    assertTrue("edge=$edge t=$t 压暗应递减 d=$d last=$last", d <= last + 1e-4f)
                } else {
                    assertTrue("edge=$edge t=$t 压暗应递增 d=$d last=$last", d >= last - 1e-4f)
                }
                last = d
            }
        }
    }

    @Test
    fun directAndEasedShareSameEndpoints() {
        for (edge in TransitionEdge.values()) {
            for (t in listOf(0f, 1f)) {
                val a = at(edge, t, direct = false)
                val b = at(edge, t, direct = true)
                assertEquals("edge=$edge t=$t 压暗", a.dim, b.dim, 1e-4f)
                assertEquals("edge=$edge t=$t 息屏层", a.aodAlpha, b.aodAlpha, 1e-4f)
                assertEquals("edge=$edge t=$t 锁屏层", a.lockAlpha, b.lockAlpha, 1e-4f)
                assertEquals("edge=$edge t=$t 桌面层", a.homeAlpha, b.homeAlpha, 1e-4f)
                assertEquals("edge=$edge t=$t 壁纸缩放", a.wallpaperScale, b.wallpaperScale, 1e-4f)
            }
        }
    }

    @Test
    fun lockHomeWallpaperStaysConstantBothDirections() {
        for (edge in listOf(TransitionEdge.LOCK_TO_HOME, TransitionEdge.HOME_TO_LOCK)) {
            for (direct in listOf(false, true)) {
                for (i in 0..20) {
                    val l = at(edge, i / 20f, direct)
                    assertEquals(
                        "edge=$edge direct=$direct 壁纸必须恒等",
                        SystemSceneMotion.WALLPAPER_SCALE_LOCK, l.wallpaperScale, 1e-4f,
                    )
                }
            }
        }
    }

    @Test
    fun aodHomeWallpaperStaysAtAndroidTruth() {
        for (edge in listOf(TransitionEdge.AOD_TO_HOME, TransitionEdge.HOME_TO_AOD)) {
            for (direct in listOf(false, true)) {
                for (i in 0..20) {
                    val l = at(edge, i / 20f, direct)
                    assertEquals(
                        "edge=$edge direct=$direct 两端都是 1.0 故全程恒 1.0",
                        SystemSceneMotion.WALLPAPER_SCALE_AOD, l.wallpaperScale, 1e-4f,
                    )
                }
            }
        }
    }

    @Test
    fun aodLockWallpaperEndpointsMatchStaticTruth() {
        val aod = staticOf(SceneState.AOD).wallpaperScale
        val lock = staticOf(SceneState.LOCK).wallpaperScale
        assertEquals(aod, at(TransitionEdge.AOD_TO_LOCK, 0f).wallpaperScale, 1e-4f)
        assertEquals(lock, at(TransitionEdge.AOD_TO_LOCK, 1f).wallpaperScale, 1e-4f)
        assertEquals(lock, at(TransitionEdge.LOCK_TO_AOD, 0f).wallpaperScale, 1e-4f)
        assertEquals(aod, at(TransitionEdge.LOCK_TO_AOD, 1f).wallpaperScale, 1e-4f)
    }

    @Test
    fun aodClockScaleAtEdgeEndMatchesIndependentEntryProgress() {
        for (edge in listOf(TransitionEdge.LOCK_TO_AOD, TransitionEdge.HOME_TO_AOD)) {
            val edgeMs = SystemSceneMotion.durationOf(edge)
            val expected = com.coloros16.liquidglass.engine.AodClockEntry.scaleAt(
                com.coloros16.liquidglass.engine.AodClockEntry.progress(1f, edgeMs),
            )
            assertEquals("edge=$edge 边末时钟缩放必须等于独立进场进度值", expected, at(edge, 1f).aodClockScale, 1e-4f)
        }
    }

    @Test
    fun aodClockEntryOutlastsShorterEdges() {
        for (edge in listOf(TransitionEdge.LOCK_TO_AOD, TransitionEdge.HOME_TO_AOD)) {
            val edgeMs = SystemSceneMotion.durationOf(edge)
            assertTrue("edge=$edge 时钟进场比边长", com.coloros16.liquidglass.engine.AodClockEntry.tailMs(edgeMs) > 0L)
            assertTrue("边末时钟未走完", at(edge, 1f).aodClockScale < 1f)
        }
    }

    @Test
    fun aodClockTailReachesOneAndStartsAtEdgeEnd() {
        for (edge in listOf(TransitionEdge.LOCK_TO_AOD, TransitionEdge.HOME_TO_AOD)) {
            val edgeMs = SystemSceneMotion.durationOf(edge)
            val entry = com.coloros16.liquidglass.engine.AodClockEntry
            val atEdgeEnd = entry.scaleAt(entry.progress(1f, edgeMs))
            val tailStart = entry.scaleAt(entry.tailProgress(0L, edgeMs))
            assertEquals("补尾起点必须接边末不跳变", atEdgeEnd, tailStart, 1e-4f)
            val tail = entry.tailMs(edgeMs)
            assertEquals("补尾结束必须复原", 1f, entry.scaleAt(entry.tailProgress(tail, edgeMs)), 1e-4f)
            assertTrue(entry.tailDone(tail, edgeMs))
        }
    }

    @Test
    fun thirdStateLayerUntouchedOnEveryEdge() {
        for (edge in TransitionEdge.values()) {
            val third = SceneState.values().first { it != edge.from && it != edge.to }
            for (i in 0..10) {
                val l = at(edge, i / 10f)
                val alpha = when (third) {
                    SceneState.AOD -> l.aodAlpha
                    SceneState.LOCK -> l.lockAlpha
                    SceneState.HOME -> l.homeAlpha
                }
                assertEquals("edge=$edge 第三态 $third 不该被碰", 0f, alpha, 1e-4f)
            }
        }
    }

    /** 三个吸附锚点都要核，桌面那个 previously 漏在表外。 */
    @Test
    fun timelineEndpointsMatchStaticStates() {
        val cases = listOf(0f to SceneState.AOD, 0.5f to SceneState.LOCK, 1f to SceneState.HOME)
        for ((u, state) in cases) {
            val l = SystemSceneMotion.Layers()
            SystemSceneMotion.sampleTimeline(u, l)
            val s = staticOf(state)
            assertEquals("u=$u 压暗", s.dim, l.dim, 1e-4f)
            assertEquals("u=$u 息屏层", s.aodAlpha, l.aodAlpha, 1e-4f)
        }
    }

    /**
     * 桌面锚点的壁纸缩放两口径故意不同，交接缝由落位补间补。
     *
     * 边内 LOCK↔HOME 恒 1.1 是参考实现恒等口径，静止桌面 1.0 是系统真值，
     * 两句各自都对；本断言钉住「差值存在且已知」，补间件负责消掉可见跳变。
     */
    @Test
    fun homeAnchorZoomGapIsKnownAndSettled() {
        val edgeEnd = SystemSceneMotion.Layers()
        SystemSceneMotion.sampleTimeline(1f, edgeEnd)
        val staticHome = staticOf(SceneState.HOME)
        assertEquals("边末恒锁屏缩放", SystemSceneMotion.WALLPAPER_SCALE_LOCK, edgeEnd.wallpaperScale, 1e-4f)
        assertEquals("静止桌面系统真值", SystemSceneMotion.WALLPAPER_SCALE_HOME, staticHome.wallpaperScale, 1e-4f)
        assertTrue(
            "两口径确有差 ⇒ 必须补间",
            WallpaperZoomSettle.needed(edgeEnd.wallpaperScale, staticHome.wallpaperScale),
        )
        // 另两个锚点无差，不该起补间
        for (u in listOf(0f, 0.5f)) {
            val l = SystemSceneMotion.Layers()
            SystemSceneMotion.sampleTimeline(u, l)
            val st = if (u == 0f) SceneState.AOD else SceneState.LOCK
            assertFalse(
                "u=$u 无差不补",
                WallpaperZoomSettle.needed(l.wallpaperScale, staticOf(st).wallpaperScale),
            )
        }
    }
}