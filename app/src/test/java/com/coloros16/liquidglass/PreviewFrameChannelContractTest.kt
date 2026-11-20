package com.coloros16.liquidglass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 预览侧逐帧通道契约：`preview-frame` 是「不需要 SystemUI 冷启」的 E2 取证来源。
 *
 * 治过的问题：AOD-146 只能用 hook 的 `stage-frame-fast` 量，而系统泵边只跑 253ms
 * （AOD-142 端点直跳）⇒ 120ms 节流只留一条端点帧，归零时刻永远量不到。
 * 预览页是自播、整条边 3840ms 走满 ⇒ 同一份 `SystemSceneMotion.sample` 能在这里量。
 */
class PreviewFrameChannelContractTest {

    private val preview =
        File("src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodPreviewView.kt").readText()

    private fun body(name: String): String {
        val at = preview.indexOf("fun $name(")
        if (at < 0) return ""
        var depth = 0
        var started = false
        val sb = StringBuilder()
        for (i in at until preview.length) {
            val c = preview[i]
            sb.append(c)
            if (c == '{') { depth++; started = true }
            if (c == '}') { depth--; if (started && depth == 0) break }
        }
        return sb.toString()
    }

    /** 通道必须在求值之后、分流之前：三个 SCENE 子分支各自 return，晚于分流就只覆盖默认分支。 */
    @Test
    fun frameLoggedAfterSamplingBeforeDispatch() {
        val world = body("drawSceneWorld")
        assertTrue("drawSceneWorld 抽取为空", world.isNotEmpty())
        val sampleAt = world.indexOf("sampleLayers()")
        val logAt = world.indexOf("logPreviewFrame()")
        val firstReturn = world.indexOf("return")
        assertTrue("必须调用逐帧通道", logAt > 0)
        assertTrue("必须在 sampleLayers 之后（否则量到上一帧的 layers）", logAt > sampleAt)
        assertTrue("必须在分流 return 之前（否则镜片/照片/手绘三条路都量不到）", firstReturn < 0 || logAt < firstReturn)
    }

    /** 字段集必须够拟合：缺 lk 或 p 就判不了归零时刻。 */
    @Test
    fun frameCarriesFitInputs() {
        val b = body("logPreviewFrame")
        assertTrue("必须是 preview-frame 前缀", b.contains("\"preview-frame: edge="))
        for (f in listOf("mode=", "p=", "lk=", "aod=", "home=", "dim=", "wp=")) {
            assertTrue("帧行缺 $f", b.contains(f))
        }
        assertTrue("lk 必须取自本帧 layers", b.contains("layers.lockAlpha"))
        assertTrue("p 必须与 layers 同源（播放取 t）", b.contains("else t"))
        assertTrue("拖动取整轨的局部进度", b.contains("SceneTimeline.localT(scrubP)"))
    }

    /** 边内节流必须比整条边长得多的小，否则 3840ms 只留个位数样本。 */
    @Test
    fun inEdgeThrottleIsShort() {
        assertTrue("边内节流必须是 120ms", preview.contains("const val PREVIEW_FRAME_IN_EDGE_MS = 120L"))
        val b = body("logPreviewFrame")
        assertTrue("边内/静止必须分开取窗", b.contains("PREVIEW_FRAME_IN_EDGE_MS") && b.contains("PREVIEW_FRAME_IDLE_MS"))
        assertTrue("边内判据是播放且 t<1", b.contains("scrubP < 0f && t < 1f"))
        // 3840ms 的边按 120ms 采样 ≈32 条，按 800ms 只有 5 条 ⇒ 前者才够拟合
        assertTrue("120ms 采样不足以拟合", 3840 / 120 >= 20)
    }

    /** 判分脚本的正则必须能匹配真实行格式（不是只断言源码里有这串）。 */
    @Test
    fun scriptRegexMatchesRealLine() {
        val script = File("../scripts/aod_preview_lockalpha.py").readText()
        val pattern = pattern(script, "FRAME")
        val real = "11-20 14:40:00.000 I/AodPreview(1234): " +
            "preview-frame: edge=LOCK_TO_HOME mode=play p=0.052 lk=0.000 " +
            "aod=0.000 home=0.100 dim=0.100 wp=1.100"
        val hit = Regex(pattern).find(real)
        assertTrue("FRAME 必须匹配真实行（$pattern）", hit != null)
        assertEquals("LOCK_TO_HOME", hit!!.groupValues[1])
        assertEquals("play", hit.groupValues[2])
        assertEquals("0.052", hit.groupValues[3])
        assertEquals("0.000", hit.groupValues[4])
        // 阳性对照：少 lk 字段必须匹配不上（否则脚本会拿 0 当实测值）
        assertFalse(Regex(pattern).containsMatchIn(real.replace(" lk=0.000", "")))
        assertTrue("判分器必须能离线自检", script.contains("--self-test") && script.contains("SELFTEST"))
    }

    /** 两个判分脚本各自编译同一行格式时必须逐字节一致（否则一边改了字段顺序，另一边静默错位）。 */
    @Test
    fun bothScriptsShareOneFramePattern() {
        val a = pattern(File("../scripts/aod_preview_lockalpha.py").readText(), "FRAME")
        val b = pattern(File("../scripts/aod_preview_six_edges_e2.py").readText(), "FRAME")
        assertEquals("两份脚本的 FRAME 正则漂移了", a, b)
    }

    /** 取脚本里 `NAME = re.compile(...)` 的 r"…" 片段（可能多段拼接）。 */
    private fun pattern(src: String, name: String): String {
        val start = src.indexOf("$name = re.compile(")
        assertTrue("找不到 $name 定义", start >= 0)
        val end = Regex("\\n(?=\\S)").find(src, start + 1)?.range?.first ?: src.length
        val body = src.substring(start, end)
        val parts = Regex("r\"([^\"]+)\"").findAll(body).map { it.groupValues[1] }.toList()
        assertTrue("$name 里没有 r\"…\" 字面量", parts.isNotEmpty())
        return parts.joinToString("")
    }
}
