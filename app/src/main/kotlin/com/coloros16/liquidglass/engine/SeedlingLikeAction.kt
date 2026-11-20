package com.coloros16.liquidglass.engine

/**
 * 媒体「喜欢」自定义动作识别（纯 JVM）：按名称匹配喜欢/收藏词表，
 * 排除不喜欢/不感兴趣类负向词；产出动作 id 供回传 sendCustomAction。
 */
object SeedlingLikeAction {

    private val POSITIVE = listOf("like", "love", "heart", "favorite", "favourite", "喜欢", "红心", "收藏")
    private val NEGATIVE = listOf("dislike", "unlike", "不喜欢", "不感兴趣", "trash", "垃圾")

    /** 名称是否「喜欢」类动作（负向词优先排除）。 */
    fun isLikeName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val n = name.lowercase()
        if (NEGATIVE.any { n.contains(it) }) return false
        return POSITIVE.any { n.contains(it) }
    }

    /** 从 (actionName, extrasName) 候选里挑首个喜欢动作，返回其原始 action 名；无则 null。 */
    fun pick(vararg names: String?): String? = names.firstOrNull { isLikeName(it) }
}
