package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.AodClockEntry
import com.coloros16.liquidglass.engine.SystemSceneMotion
import com.coloros16.liquidglass.engine.TransitionEdge
import com.coloros16.liquidglass.ui.aod.AodSceneCatalog
import com.coloros16.liquidglass.ui.aod.AodStyleCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 预览时钟补尾必须与起播同口径：都按当前样式取时长，且起播时钉住不中途变。
 *
 * 契约分两半：数值半纯 JVM 复算，源码半直读 AodPreviewView 断言无系统时长残留。
 */
class PreviewClockTailStyleContractTest {

    private fun root(): File {
        var dir = File(System.getProperty("user.dir"))
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        return dir
    }

    private fun previewSource(): String {
        val f = File(root(), "app/src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodPreviewView.kt")
        assertTrue("找不到预览源码", f.isFile)
        val s = f.readText()
        assertTrue("源码读空，判据失效", s.length > 1000)
        return s
    }

    /** 取某函数体（大括号配平），找不到返回空串。 */
    private fun body(src: String, fn: String): String {
        val lines = src.split("\n")
        val start = lines.indexOfFirst { Regex("""^\s*(internal |private )?fun $fn\b""").containsMatchIn(it) }
        if (start < 0) return ""
        var depth = 0
        var opened = false
        val out = StringBuilder()
        for (i in start until lines.size) {
            val l = lines[i]
            out.append(l).append('\n')
            depth += l.count { it == '{' } - l.count { it == '}' }
            if (l.contains('{')) opened = true
            if (opened && depth <= 0) break
        }
        return out.toString()
    }

    @Test
    fun sailLockToAodTailIsShorterThanSystemTail() {
        val edge = TransitionEdge.LOCK_TO_AOD
        val styleMs = AodSceneCatalog.durationOf(AodStyleCatalog.ID_SAIL, edge)
        val sysMs = SystemSceneMotion.durationOf(edge)
        assertEquals(350L, AodClockEntry.tailMs(styleMs))
        assertEquals(500L, AodClockEntry.tailMs(sysMs))
    }

    @Test
    fun positiveControlSystemTailRunsLongerThanStyleTail() {
        val edge = TransitionEdge.LOCK_TO_AOD
        val styleTail = AodClockEntry.tailMs(AodSceneCatalog.durationOf(AodStyleCatalog.ID_SAIL, edge))
        val sysTail = AodClockEntry.tailMs(SystemSceneMotion.durationOf(edge))
        assertTrue("按系统时长补尾会多跑，差=${sysTail - styleTail}ms", sysTail - styleTail >= 150L)
    }

    @Test
    fun tailProgressStartsWhereStyleEdgeEnded() {
        for (style in listOf(AodStyleCatalog.ID_SAIL, AodStyleCatalog.ID_LEAVE_LIGHT, AodStyleCatalog.ID_STARMOON)) {
            for (edge in TransitionEdge.values()) {
                if (!AodClockEntry.entering(edge)) continue
                val ms = AodSceneCatalog.durationOf(style, edge)
                val atEnd = AodClockEntry.progress(1f, ms)
                assertEquals(
                    "补尾起点必须接边末 style=$style edge=$edge",
                    atEnd,
                    AodClockEntry.tailProgress(0L, ms),
                    0.0001f,
                )
            }
        }
    }

    @Test
    fun tailCompletesExactlyAtStyleTailEnd() {
        for (style in listOf(AodStyleCatalog.ID_SAIL, AodStyleCatalog.ID_DOOR, AodStyleCatalog.ID_MOUNTAIN)) {
            for (edge in TransitionEdge.values()) {
                if (!AodClockEntry.entering(edge)) continue
                val ms = AodSceneCatalog.durationOf(style, edge)
                val tail = AodClockEntry.tailMs(ms)
                assertEquals(1f, AodClockEntry.scaleAt(AodClockEntry.tailProgress(tail, ms)), 0.0001f)
                assertTrue(AodClockEntry.tailDone(tail, ms))
            }
        }
    }

    @Test
    fun renderUsesStyleDurationForTail() {
        val b = body(previewSource(), "render")
        assertTrue("render 函数体抽取为空，判据失效", b.isNotEmpty())
        // 钉取值路径不钉字面调用：四处边长已归一到 edgeMsFor，再钉两参 durationOf 会把「已修好」判成红
        assertTrue("补尾时长必须按样式取（经单点 edgeMsFor）", b.contains("edgeMsFor(prevEdge)"))
        assertTrue("补尾时长必须钉进字段", b.contains("clockTailEdgeMs = edgeMs"))
    }

    @Test
    fun tailConsumersReadPinnedDurationNotSystem() {
        val src = previewSource()
        val tick = src.substringAfter("private val clockTailTick").substringBefore("private fun scheduleClockTail")
        assertTrue("帧回调段抽取为空", tick.isNotEmpty())
        assertTrue("帧回调判完必须用钉住的时长", tick.contains("AodClockEntry.tailDone(elapsed, clockTailEdgeMs)"))
        val scale = body(src, "clockEntryScale")
        assertTrue("clockEntryScale 抽取为空", scale.isNotEmpty())
        assertTrue("缩放必须用钉住的时长", scale.contains("AodClockEntry.tailProgress(elapsed, clockTailEdgeMs)"))
    }

    @Test
    fun noSystemDurationLeftInTailPath() {
        val src = previewSource()
        val tick = src.substringAfter("private val clockTailTick").substringBefore("private fun scheduleClockTail")
        val scale = body(src, "clockEntryScale")
        val render = body(src, "render")
        for ((name, seg) in listOf("tick" to tick, "scale" to scale, "render" to render)) {
            assertTrue("$name 段仍残留系统时长", !seg.contains("SystemSceneMotion.durationOf"))
        }
    }

    @Test
    fun positiveControlDetectorCatchesSystemDurationWhenPresent() {
        val synthetic = "fun clockEntryScale(): Float {\n  return AodClockEntry.tailProgress(elapsed, SystemSceneMotion.durationOf(tail))\n}\n"
        val extracted = body(synthetic, "clockEntryScale")
        assertTrue("阳性对照：注入系统时长后必须被检出", extracted.contains("SystemSceneMotion.durationOf"))
    }
}
