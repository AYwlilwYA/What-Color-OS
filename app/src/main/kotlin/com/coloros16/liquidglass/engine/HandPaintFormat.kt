package com.coloros16.liquidglass.engine

/**
 * 手绘笔画数据的文本解析：把厂商 json 还原成 [HandPaintSilk] 全量轨迹。
 *
 * 纯 JVM 手写扫描器（不引 json 库，engine 零外部依赖）；
 * 只认已知字段，未知字段跳过，字段缺失走缺省，任何一处坏数据只丢该条不整篇失败。
 */
object HandPaintFormat {

    const val TAG_VERSION = "HandPaintedKaleidoscope"
    const val TAG_SILK_NUM = "SilkNum"
    const val TAG_SILKS = "Silks"

    /** 解析结果：版本号 + 丝线列表；解析不出任何丝线时列表为空。 */
    data class Document(val version: String, val silks: List<HandPaintSilk>)

    fun parse(text: String): Document {
        val version = stringOf(text, TAG_VERSION) ?: ""
        val silks = ArrayList<HandPaintSilk>()
        for (block in objectsOfArray(text, TAG_SILKS)) {
            parseSilk(block)?.let { silks.add(it) }
        }
        return Document(version, silks)
    }

    private fun parseSilk(block: String): HandPaintSilk? {
        val steps = intOf(block, "StepsPerFrame") ?: 0
        if (steps <= 0) return null
        val raw = ArrayList<HandPaintPoint>()
        for (frame in objectsOfArray(block, "Frames")) {
            appendFrame(frame, steps, raw)
        }
        if (raw.isEmpty()) return null
        return HandPaintSilk(
            id = intOf(block, "SilkID") ?: 0,
            enabled = boolOf(block, "Enable") ?: true,
            mirror = boolOf(block, "Mirror") ?: false,
            rotates = intOf(block, "Rotates") ?: 1,
            spirals = intOf(block, "Spirals") ?: 1,
            brushLineWidth = floatOf(block, "BrushLineWidth")
                ?: HandPaintMotion.DEFAULT_BRUSH_WIDTH,
            centerX = floatOf(block, "CentralPointX") ?: 0f,
            centerY = floatOf(block, "CentralPointY") ?: 0f,
            points = HandPaintMotion.dedupe(raw),
        )
    }

    /**
     * 一帧内按**点下标外层、子步内层**交错追加点。
     *
     * 子步是同一条笔画在帧内的多次细分采样（实测相邻子步同下标两点相距不到 1 单位），
     * 按子步外层顺序拼会让轨迹在子步之间来回跳；按下标外层交错才还原采集原序。
     * 每帧三数组长度都等于 StepsPerFrame，长度不齐按最短取，避免越界丢整条丝线。
     */
    private fun appendFrame(frame: String, steps: Int, out: MutableList<HandPaintPoint>) {
        val colors = objectsOfArray(frame, "Colors")
        val counts = objectsOfArray(frame, "PointsCounts")
        val stepsArr = objectsOfArray(frame, "Steps")
        val n = minOf(steps, colors.size, counts.size, stepsArr.size)
        if (n <= 0) return
        val lanes = ArrayList<Lane>(n)
        var width = Int.MAX_VALUE
        for (k in 0 until n) {
            val c = colors[k]
            val declared = intOf(counts[k], "PointsCount") ?: 0
            val pts = objectsOfArray(stepsArr[k], "Points")
            val take = if (declared in 1..pts.size) declared else pts.size
            if (take <= 0) return
            lanes.add(
                Lane(
                    r = floatOf(c, "R") ?: 0f,
                    g = floatOf(c, "G") ?: 0f,
                    b = floatOf(c, "B") ?: 0f,
                    a = floatOf(c, "A") ?: 0f,
                    points = pts,
                    size = take,
                ),
            )
            if (take < width) width = take
        }
        for (j in 0 until width) {
            for (lane in lanes) {
                val p = lane.points[j]
                val x = floatOf(p, "X") ?: continue
                val y = floatOf(p, "Y") ?: continue
                out.add(HandPaintPoint(x, y, lane.r, lane.g, lane.b, lane.a))
            }
        }
    }

    private class Lane(
        val r: Float,
        val g: Float,
        val b: Float,
        val a: Float,
        val points: List<String>,
        val size: Int,
    )

    /** 取字符串字段值；找不到返回 null。 */
    internal fun stringOf(text: String, key: String): String? {
        val at = valueStart(text, key) ?: return null
        if (at >= text.length || text[at] != '"') return null
        val end = text.indexOf('"', at + 1)
        if (end < 0) return null
        return text.substring(at + 1, end)
    }

    internal fun floatOf(text: String, key: String): Float? =
        numberToken(text, key)?.toFloatOrNull()

    internal fun intOf(text: String, key: String): Int? =
        numberToken(text, key)?.toFloatOrNull()?.toInt()

    internal fun boolOf(text: String, key: String): Boolean? =
        when (numberToken(text, key)) {
            "true" -> true
            "false" -> false
            else -> null
        }

    /** 数字/布尔字面量：读到分隔符为止。 */
    private fun numberToken(text: String, key: String): String? {
        val at = valueStart(text, key) ?: return null
        var end = at
        while (end < text.length && text[end] !in ",}] \t\r\n") end++
        if (end == at) return null
        return text.substring(at, end)
    }

    /** 定位 "key" 后的冒号之后第一个非空白字符下标。 */
    private fun valueStart(text: String, key: String): Int? {
        val needle = "\"$key\""
        var from = 0
        while (true) {
            val hit = text.indexOf(needle, from)
            if (hit < 0) return null
            var i = hit + needle.length
            while (i < text.length && text[i].isWhitespace()) i++
            if (i < text.length && text[i] == ':') {
                i++
                while (i < text.length && text[i].isWhitespace()) i++
                return i
            }
            from = hit + needle.length
        }
    }

    /**
     * 取数组里的一层对象文本（含花括号）。
     *
     * 只按花括号配平切分，不做完整语法分析；嵌套对象由调用方逐层再取。
     */
    internal fun objectsOfArray(text: String, key: String): List<String> {
        val at = valueStart(text, key) ?: return emptyList()
        if (at >= text.length || text[at] != '[') return emptyList()
        val out = ArrayList<String>()
        var i = at + 1
        while (i < text.length) {
            val ch = text[i]
            if (ch == ']') break
            if (ch == '{') {
                val end = matchBrace(text, i)
                if (end < 0) break
                out.add(text.substring(i, end + 1))
                i = end + 1
            } else {
                i++
            }
        }
        return out
    }

    /** 从 open 处的 '{' 找到配平 '}'；字符串内的花括号不计数。 */
    private fun matchBrace(text: String, open: Int): Int {
        var depth = 0
        var inString = false
        var i = open
        while (i < text.length) {
            val ch = text[i]
            when {
                inString -> {
                    if (ch == '\\') i++
                    else if (ch == '"') inString = false
                }
                ch == '"' -> inString = true
                ch == '{' -> depth++
                ch == '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }
}