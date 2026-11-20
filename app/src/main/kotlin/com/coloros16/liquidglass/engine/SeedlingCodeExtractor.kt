package com.coloros16.liquidglass.engine

/**
 * 验证码提取（纯 JVM 三段式）：关键词定位 → 紧邻提取 → 前后 40 字符兜底扫描。
 * 负向词表与单位/货币字符守卫防误提（尾号/卡号/金额/日期等）。
 */
object SeedlingCodeExtractor {

    private val KEYWORD = Regex(
        "验证码|校验码|动态码|动态密码|确认码|安全码|登录码|认证码|授权码|一次性密码|短信码|随机码|" +
            "(?i)(?<![A-Za-z])(?:verification\\s+code|security\\s+code|one[-\\s]?time\\s+(?:pass)?code|" +
            "one[-\\s]?time\\s+password|otp|passcode|login\\s+code|sign[-\\s]?in\\s+code|" +
            "auth(?:entication)?\\s+code|confirmation\\s+code)(?![A-Za-z])",
    )
    private val BARE_CODE = Regex("(?i)(?<![A-Za-z])code(?![A-Za-z])")
    private val ADJACENT = Regex(
        "^[\\s:：]*(?:是|为|為|(?i:is)(?![A-Za-z]))?[\\s:：]*" +
            "(?:([0-9]{3})-([0-9]{3})(?![0-9A-Za-z\\-])|([A-Za-z0-9]{4,8})(?![0-9A-Za-z]))",
    )
    private val SCAN = Regex("(?<![0-9A-Za-z.\\-])(?:[A-Z]-)?([0-9]{4,8})(?![0-9A-Za-z\\-]|\\.[0-9])")

    private val NEGATIVE_WORDS = listOf(
        "尾号", "卡号", "账号", "帐号", "手机号", "订单号", "单号", "编号", "工号", "电话", "号码", "热线",
    )
    private const val UNIT_CHARS = "年月日号號元圆角分秒时點点%％"
    private const val CURRENCY_CHARS = "¥￥$€£"
    private const val WINDOW = 40

    /** 依次尝试多段文本（标题/正文/大文本），返回首个提取成功的验证码；无则 null。 */
    fun extract(vararg texts: String?): String? {
        for (raw in texts) {
            if (raw.isNullOrBlank()) continue
            val code = extractOne(raw)
            if (code != null) return code
        }
        return null
    }

    private fun extractOne(text: String): String? {
        val keywords = KEYWORD.findAll(text).map { it.range.first to it.range.last + 1 }.toMutableList()
        keywords += BARE_CODE.findAll(text).map { it.range.first to it.range.last + 1 }
        if (keywords.isEmpty()) return null
        keywords.sortBy { it.first }

        // 第一段：关键词后紧邻提取（123-456 拼接 / 4~8 位字母数字）
        for ((start, end) in keywords) {
            val tail = text.substring(end)
            val m = ADJACENT.find(tail) ?: continue
            val dashed1 = m.groupValues[1]
            val dashed2 = m.groupValues[2]
            if (dashed1.isNotEmpty() && dashed2.isNotEmpty()) {
                val joined = dashed1 + dashed2
                if (guardOk(text, end + m.range.first, end + m.range.last + 1, joined)) return joined
            }
            val plain = m.groupValues[3]
            if (plain.isNotEmpty()) {
                val absStart = end + m.groups[3]!!.range.first
                val absEnd = end + m.groups[3]!!.range.last + 1
                val hasLetter = plain.any { it.isLetter() }
                val fromKeyword = text.substring(start, end)
                val keywordHasLetter = fromKeyword.any { it.isLetter() }
                if (hasLetter && !keywordHasLetter) continue
                if (guardOk(text, absStart, absEnd, plain)) return plain
            }
        }

        // 第二段：兜底扫描——关键词后 40 字符内最近候选，再前 40 字符内最近候选
        val candidates = SCAN.findAll(text).toList()
        if (candidates.isEmpty()) return null
        for ((start, end) in keywords) {
            val after = candidates.firstOrNull { it.range.first > end && it.range.first - end <= WINDOW }
            if (after != null) {
                val v = after.groupValues[1]
                if (guardOk(text, after.range.first, after.range.last + 1, v)) return v
            }
        }
        for ((start, end) in keywords) {
            val before = candidates.lastOrNull { it.range.last + 1 < start && start - (it.range.last + 1) <= WINDOW }
            if (before != null) {
                val v = before.groupValues[1]
                if (guardOk(text, before.range.first, before.range.last + 1, v)) return v
            }
        }
        return null
    }

    /** 候选守卫：后随单位/日期字符、前置货币符、前文（截尾连接词）含负向词 → 拒。 */
    private fun guardOk(text: String, start: Int, end: Int, value: String): Boolean {
        if (end < text.length && text[end] in UNIT_CHARS) return false
        if (start > 0 && text[start - 1] in CURRENCY_CHARS) return false
        val prefix = text.substring(0, start).trimEnd(' ', ':', '：', '为', '是')
        return NEGATIVE_WORDS.none { prefix.contains(it) }
    }
}
