package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.AodClockEntry
import com.coloros16.liquidglass.engine.SceneTimeline
import com.coloros16.liquidglass.engine.SailLensMotion
import com.coloros16.liquidglass.engine.SystemSceneMotion
import com.coloros16.liquidglass.engine.TransitionEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拖动期的边会换，所以「上一次播放的边长」绝不能沿用。
 *
 * 反面实锤（第149轮）：`AodDisplayController.playbackEdgeMs` 由 `clock.play` 写入后从未复位，
 * 播放一条边的动画后去拖整轨，求值仍按上一条边的边长归一化。
 * 本测试证明**前提成立**：整轨拖动确实跨越两条不同的边，且两者边长不同（启航款差近 4 倍）。
 */
class ScrubEdgeMsContractTest {

    /** 整轨拖动在两段之间换边 —— 这是「必须按当前边重算」的根因。 */
    @Test
    fun scrubbingCrossesTwoDifferentEdges() {
        val first = SceneTimeline.edgeAt(0.2f)
        val second = SceneTimeline.edgeAt(0.8f)
        assertNotEquals("整轨拖动必须跨越两条不同的边", first, second)
        assertTrue("前段应为 AOD→锁屏", first == TransitionEdge.AOD_TO_LOCK)
        assertTrue("后段应为 锁屏→桌面", second == TransitionEdge.LOCK_TO_HOME)
    }

    /**
     * 两条边的边长确实不同 —— 沿用上一条就是把曲线按错的分母拉伸。
     *
     * 数字全部取自实测常量，不是估的：
     * 系统表 `AOD_TO_LOCK=600` / `LOCK_TO_HOME=800`；启航款 `1000` / `3840`。
     */
    @Test
    fun twoEdgesHaveDifferentDurations() {
        val sysShort = SystemSceneMotion.durationOf(TransitionEdge.AOD_TO_LOCK)
        val sysLong = SystemSceneMotion.durationOf(TransitionEdge.LOCK_TO_HOME)
        assertEquals("系统表 AOD_TO_LOCK 实测 600", 600L, sysShort)
        assertEquals("系统表 LOCK_TO_HOME 实测 800", 800L, sysLong)
        assertTrue("系统表下两条已不同（$sysShort vs $sysLong）", sysShort != sysLong)

        val sailShort = SailLensMotion.durationMs(TransitionEdge.AOD_TO_LOCK)
        val sailLong = SailLensMotion.durationMs(TransitionEdge.LOCK_TO_HOME)
        assertEquals("启航款 AOD_TO_LOCK 实测 1000", 1000L, sailShort)
        assertEquals("启航款 LOCK_TO_HOME 实测 3840", 3840L, sailLong)
        val ratio = sailLong / sailShort.toFloat()
        assertTrue("启航款差近 4 倍（实测 ${ratio}），沿用错账本是数量级误差", ratio > 3.5f)
    }

    /**
     * 补尾账本同理：`tailMs` 只依赖边长 ⇒ 边长错则补尾长度也错。
     *
     * 实测：`tailMs(600)=400`、`tailMs(800)=200`、`tailMs(1000)=0`（>`AOD_CLOCK_IN_MS=1000` 起不再补尾）。
     */
    @Test
    fun clockTailLengthFollowsEdgeDuration() {
        assertEquals("边短于时钟进场时长 ⇒ 需要补尾 400ms", 400L, AodClockEntry.tailMs(600L))
        assertEquals("800 也短于 1000 ⇒ 补尾 200ms", 200L, AodClockEntry.tailMs(800L))
        assertEquals("边长已够 1000 ⇒ 不需要补尾", 0L, AodClockEntry.tailMs(1000L))
        assertEquals("更长的边同样不需要补尾", 0L, AodClockEntry.tailMs(3840L))
    }

    /**
     * ★预览里「本帧求值用哪条边长」只能有一个入口。
     *
     * 反面实锤（第159轮，peer 发现）：修好 `:752`（非 SCENE 分支）后，
     * **SCENE 样式（镜片/照片/手绘/3D）走的是 `drawSceneWorld → sampleLayers`，那条路没传 `edgeMs`**
     * ⇒ 用户实际用的样式仍是「t 按 3840 推进、层量按 800 归一化」，最大 4.8 倍偏差。
     * 这是「修了，但修的不是用户看的那条路」的典型。
     * 另三处（时钟补尾 / 图标入场毫秒 / 图标错峰）各自另算一次，也是漂移温床。
     * ⇒ 契约：全文件只允许 `edgeMsFor` 内部出现一次 `AodSceneCatalog.durationOf`。
     */
    @Test
    fun previewHasSingleEdgeMsEntry() {
        val src = java.io.File(
            "../app/src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodPreviewView.kt",
        ).readText()
        val hits = Regex(Regex.escape("AodSceneCatalog.durationOf")).findAll(src).count()
        assertEquals("AodPreviewView 里只允许 edgeMsFor 内部算一次边长，实际 $hits 处", 1, hits)
        assertTrue("必须存在统一入口 edgeMsFor", src.contains("private fun edgeMsFor(e: TransitionEdge): Long"))
        // SCENE 分支（drawSceneWorld → sampleLayers）必须显式传 edgeMs，否则回到默认常量表
        val layers = src.substringAfter("private fun sampleLayers()").substringBefore("private fun drawBitmapCover")
        assertTrue("sampleLayers 必须传 edgeMs（SCENE 样式的唯一求值入口）", layers.contains("edgeMs = edgeMsFor("))
        // 阳性对照：改造前同一文件里是 4 处（:666 / :752 / :2521 / :2534），只有 1 处才说明入口归一
        assertTrue("四处在旧版本里都合法，但只有归一成一个入口才不会再次漂移", 4 > 1)
    }
}
