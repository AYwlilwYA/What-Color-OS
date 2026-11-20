package com.coloros16.liquidglass.engine

/**
 * 大卡样式模式（纯 JVM，App 预览与 hook 共用）：
 * 苹果式单卡（左右滑换卡）／OP 式直接拉开所有卡（卡1卡2 竖排）。
 * 候选=调度器按优先级排好的可见活动序（main+左右副岛+排队）。
 */
object SeedlingCardModePolicy {

    enum class Mode { APPLE, OP }

    /** 展开态要渲染的卡序列：OP=全部候选竖排；APPLE=单卡（当前展开者，未展开取首位）。 */
    fun cardStack(mode: Mode, candidates: List<SeedlingActivity>, expandedId: String?): List<SeedlingActivity> {
        if (candidates.isEmpty()) return emptyList()
        return when (mode) {
            Mode.OP -> candidates
            Mode.APPLE -> listOfNotNull(byId(candidates, expandedId) ?: candidates.first())
        }
    }

    /**
     * APPLE 横滑轮换：direction>0 下一张 / <0 上一张，环形 wrap；
     * OP 不轮换（全显）返回原 id；direction=0 不动。空候选返回 null。
     */
    fun cycleExpanded(mode: Mode, candidates: List<SeedlingActivity>, currentId: String?, direction: Int): String? {
        if (candidates.isEmpty()) return null
        if (mode == Mode.OP || direction == 0) return currentId ?: candidates.first().id
        val found = candidates.indexOfFirst { it.id == currentId }
        val idx = if (found < 0) 0 else found
        val n = candidates.size
        val next = ((idx + direction) % n + n) % n
        return candidates[next].id
    }

    /** OP 竖排第 index 张卡顶 = 首卡顶 + index×(卡高+间距)。 */
    fun stackTop(baseTop: Float, index: Int, cardHeight: Float, gap: Float): Float =
        baseTop + index * (cardHeight + gap)

    /** OP 竖排总高 = n×卡高 + (n-1)×间距（n≤1 无间距）。 */
    fun stackHeight(count: Int, cardHeight: Float, gap: Float): Float =
        if (count <= 0) 0f else count * cardHeight + (count - 1) * gap

    /** OP 竖排卡高自适应：n 卡总高超可用高时解 h=(avail-(n-1)*gap)/n（gap 不缩），否则保持原高；下限 minH 防扁卡。 */
    fun opCardHeight(count: Int, baseHeight: Float, gap: Float, availHeight: Float, minHeight: Float): Float {
        if (count <= 1) return baseHeight
        if (stackHeight(count, baseHeight, gap) <= availHeight) return baseHeight
        return ((availHeight - (count - 1) * gap) / count).coerceAtLeast(minHeight)
    }

    private fun byId(list: List<SeedlingActivity>, id: String?): SeedlingActivity? =
        if (id == null) null else list.firstOrNull { it.id == id }
}
