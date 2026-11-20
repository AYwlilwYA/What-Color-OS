package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.LyricLine
import com.coloros16.liquidglass.engine.LyricTimeline
import com.coloros16.liquidglass.engine.PositionBaseline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 歌词时间轴契约：行选择/位置外推/LRC 解析，含阳性对照。 */
class SeedlingLyricsContractTest {

    private val tl = LyricTimeline(
        listOf(
            LyricLine(1000, "第一句"),
            LyricLine(5000, "第二句", "translation"),
            LyricLine(9000, "第三句"),
        ),
    )

    @Test
    fun beforeFirstLine() {
        assertEquals(-1, tl.currentIndex(500))
        assertNull(tl.currentLine(500))
        assertEquals("第二句之前预取第一句", "第一句", tl.nextLine(500)?.text)
    }

    @Test
    fun midSecondLine() {
        assertEquals(1, tl.currentIndex(6000))
        assertEquals("第二句", tl.currentLine(6000)?.text)
        assertEquals("第三句", tl.nextLine(6000)?.text)
        assertEquals(0.25f, tl.lineProgress(6000), 1e-3f)
    }

    @Test
    fun lastLineNoNext() {
        assertEquals(2, tl.currentIndex(20000))
        assertNull(tl.nextLine(20000))
        assertEquals(1f, tl.lineProgress(20000), 1e-4f)
    }

    @Test
    fun baselineExtrapolates() {
        val b = PositionBaseline(10000, 100000, 1f)
        assertEquals(13000L, b.positionAt(103000))
        val fast = PositionBaseline(10000, 100000, 2f)
        assertEquals(16000L, fast.positionAt(103000))
    }

    @Test
    fun baselineClamped() {
        val b = PositionBaseline(0, 100000, 1f)
        assertEquals("过去不外推为负", 0L, b.positionAt(99000))
        assertEquals(PositionBaseline.MAX_POSITION_MS, b.positionAt(100000 + 604800001L))
    }

    @Test
    fun lrcParseStandard() {
        val t = LyricTimeline.parseLrc(
            """
            [00:01.00]第一句
            [00:05.50]第二句
            [01:00.000]第三句
            """.trimIndent(),
        )
        assertEquals(3, t.lines.size)
        assertEquals(1000L, t.lines[0].atMs)
        assertEquals(5500L, t.lines[1].atMs)
        assertEquals(60000L, t.lines[2].atMs)
    }

    @Test
    fun lrcMultiTimestampSameLine() {
        val t = LyricTimeline.parseLrc("[00:01.00][00:10.00]重复句")
        assertEquals(2, t.lines.size)
        assertEquals("重复句", t.lines[1].text)
    }

    @Test
    fun lrcIgnoresMetadata_positiveControl() {
        val t = LyricTimeline.parseLrc("[ti:歌名]\n[ar:歌手]\n[00:01.00]正文")
        assertEquals("阳性对照：元数据行不产生歌词行", 1, t.lines.size)
        assertEquals("正文", t.lines[0].text)
    }

    @Test
    fun lrcSorted() {
        val t = LyricTimeline.parseLrc("[00:10.00]后\n[00:01.00]前")
        assertEquals("前", t.lines[0].text)
    }

    @Test
    fun lineAdvancesWithPosition_positiveControl() {
        // 阳性对照：播放位推进歌词行必跟着换（逐帧重选的依据）
        assertEquals("第一句", tl.currentLine(1500)?.text)
        assertEquals("第二句", tl.currentLine(5500)?.text)
        assertEquals("第三句", tl.currentLine(9500)?.text)
        val b = PositionBaseline(1000, 100000, 1f)
        assertEquals("位置外推驱动行选择", "第二句", tl.currentLine(b.positionAt(104500))?.text)
    }

    @Test
    fun emptyTimelineNoLine() {
        val empty = LyricTimeline(emptyList())
        assertNull(empty.currentLine(5000))
        assertEquals(-1, empty.currentIndex(5000))
        assertEquals(0f, empty.lineProgress(5000), 1e-4f)
    }
}