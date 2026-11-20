package com.coloros16.liquidglass.engine

/** 冷启动次数 → 赞助提示档位（10/20/50/100/200/400，之后每 400 次一档）。 */
object SponsorMilestone {

    private val STEPS = intArrayOf(10, 20, 50, 100, 200, 400)
    private const val TAIL_STEP = 400

    /** 命中的档位值；未达首档、非档位点、非负输入返回 null。 */
    fun forCount(count: Int): Int? {
        if (count <= 0) return null
        for (m in STEPS) if (count == m) return m
        val last = STEPS.last()
        if (count > last && count % TAIL_STEP == 0) return count
        return null
    }
}
