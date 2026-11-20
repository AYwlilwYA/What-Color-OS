package com.coloros16.liquidglass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 三态条「画 / 点 / 拖」必须同一套 pad + 净宽。
 *
 * 治过的问题：内边距与净宽公式在三处各写一份（onDraw 画标签、onTouchEvent 算索引、算进度），
 * 任何一处单独改（比如为了好看把 pad 从 3dp 调到 5dp）⇒ 点击落点与标签错位，
 * 而且静止态看不出来（标签还是画在旧位置）。
 */
class StateBarHitTestParityContractTest {

    private val src =
        File("src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodStateBar.kt").readText()

    private fun violations(text: String): List<String> {
        val out = mutableListOf<String>()
        for (fn in listOf("trackPad", "trackSpan", "trackT", "trackX", "indexAt")) {
            if (!text.contains("private fun $fn(")) out += "缺 helper $fn"
        }
        val padHits = Regex(Regex.escape("dp(3f)")).findAll(text).count()
        if (padHits != 1) out += "内边距 dp(3f) 出现 $padHits 次（必须只在 trackPad 一处）"
        val spanHits = Regex(Regex.escape("- trackPad() * 2f")).findAll(text).count()
        if (spanHits != 1) out += "净宽公式出现 $spanHits 次（必须只在 trackSpan 一处）"
        val draw = bodyOf(text, "onDraw")
        if (!draw.contains("trackSpan() / 3f")) out += "标签分段宽必须取自 trackSpan"
        if (!draw.contains("trackX(")) out += "拖动游标/高亮必须走 trackX"
        val touch = bodyOf(text, "onTouchEvent")
        if (!touch.contains("trackT(x)")) out += "拖动进度必须走 trackT"
        if (!touch.contains("indexAt(x)")) out += "点击命中必须走 indexAt"
        return out
    }

    private fun bodyOf(text: String, name: String): String {
        val at = text.indexOf("override fun $name(")
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

    @Test
    fun drawTapAndDragShareOneGeometry() {
        assertTrue("AodStateBar 抽取为空", src.isNotEmpty())
        assertEquals("三态条几何必须单点：${violations(src)}", emptyList<String>(), violations(src))
    }

    /** 阳性对照：把命中改回内联公式，必须被点名（否则本契约是恒真护栏）。 */
    @Test
    fun inlineHitFormulaIsFlagged() {
        val mutated = src.replace(
            "listener?.onTap(indexAt(x))",
            "listener?.onTap(((x - dp(3f)) / ((width - dp(3f) * 2f) / 3f)).toInt().coerceIn(0, 2))",
        )
        assertTrue("变异没生效", mutated != src)
        val v = violations(mutated)
        assertTrue("内联命中公式必须被抓出：$v", v.any { it.contains("indexAt") })
        assertTrue("内联 pad 必须被抓出：$v", v.any { it.contains("dp(3f)") })
    }

    /** 阳性对照：标签分段宽绕开 trackSpan 也必须被抓出。 */
    @Test
    fun inlineSegmentWidthIsFlagged() {
        val mutated = src.replace("val segW = trackSpan() / 3f", "val segW = (w - pad * 2f) / 3f")
        assertTrue("变异没生效", mutated != src)
        val v = violations(mutated)
        assertTrue("绕开 trackSpan 必须被抓出：$v", v.any { it.contains("trackSpan") })
    }
}
