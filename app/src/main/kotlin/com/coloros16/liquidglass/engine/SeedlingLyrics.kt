package com.coloros16.liquidglass.engine

/**
 * 歌词行选择与位置外推（纯 JVM，App 预览与 hook 共用）。
 * 位置基准三元组：positionMs + baseElapsedMs + speed；当前行=时间戳 ≤ 播放位的最后一行。
 */
data class LyricLine(val atMs: Long, val text: String, val translation: String = "")

data class PositionBaseline(val positionMs: Long, val baseElapsedMs: Long, val speed: Float = 1f) {
    fun positionAt(nowElapsedMs: Long): Long {
        val pos = positionMs + ((nowElapsedMs - baseElapsedMs) * speed).toLong()
        return pos.coerceIn(0L, MAX_POSITION_MS)
    }

    companion object {
        const val MAX_POSITION_MS = 604800000L
    }
}

class LyricTimeline(val lines: List<LyricLine>) {

    /** 当前行下标（-1=还没到第一行）；seamless 预取下一行。 */
    fun currentIndex(positionMs: Long): Int {
        var idx = -1
        for (i in lines.indices) {
            if (lines[i].atMs <= positionMs) idx = i else break
        }
        return idx
    }

    fun currentLine(positionMs: Long): LyricLine? = currentIndex(positionMs).takeIf { it >= 0 }?.let { lines[it] }

    fun nextLine(positionMs: Long): LyricLine? = (currentIndex(positionMs) + 1).takeIf { it < lines.size }?.let { lines[it] }

    /** 行内进度 0..1（当前行到下一行之间；末行恒 1）。 */
    fun lineProgress(positionMs: Long): Float {
        val i = currentIndex(positionMs)
        if (i < 0) return 0f
        if (i >= lines.size - 1) return 1f
        val span = (lines[i + 1].atMs - lines[i].atMs).coerceAtLeast(1L)
        return ((positionMs - lines[i].atMs).toFloat() / span).coerceIn(0f, 1f)
    }

    companion object {
        /** LRC 时间标签解析 [mm:ss.xx]（纯文本，无第三方库）。 */
        fun parseLrc(lrc: String): LyricTimeline {
            val re = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
            val out = mutableListOf<LyricLine>()
            lrc.lineSequence().forEach { line ->
                val matches = re.findAll(line).toList()
                if (matches.isEmpty()) return@forEach
                val text = line.substring(matches.last().range.last + 1).trim()
                if (text.isEmpty()) return@forEach
                matches.forEach { m ->
                    val min = m.groupValues[1].toLong()
                    val sec = m.groupValues[2].toLong()
                    val fracRaw = m.groupValues[3]
                    val frac = when (fracRaw.length) {
                        0 -> 0L
                        1 -> fracRaw.toLong() * 100
                        2 -> fracRaw.toLong() * 10
                        else -> fracRaw.take(3).toLong()
                    }
                    out += LyricLine(min * 60000 + sec * 1000 + frac, text)
                }
            }
            return LyricTimeline(out.sortedBy { it.atMs })
        }
    }
}