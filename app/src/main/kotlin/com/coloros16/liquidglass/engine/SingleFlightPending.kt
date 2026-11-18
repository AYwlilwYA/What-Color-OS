package com.coloros16.liquidglass.engine

/**
 * 单飞构建的待办槽：在途期间只留最后一次请求，收尾补做一次。
 *
 * 纯记账，排队与渲染由调用方负责。
 */
class SingleFlightPending<T> {

    private var last: T? = null

    /** 在途时登记；后来者覆盖前者（中间档已被取代，补做也是白画）。 */
    fun offer(value: T) {
        last = value
    }

    /** 收尾取走待办；取后清空，避免同一请求被补做两次。 */
    fun take(): T? {
        val v = last
        last = null
        return v
    }

    fun pending(): Boolean = last != null

    fun clear() {
        last = null
    }

    companion object {
        /**
         * 是否该补做：待办键与当前槽一致就不补，否则会自我循环。
         *
         * 渲染失败时槽未更新，键仍不同 ⇒ 会重试一次；
         * 重试再失败则待办已清空，不会无限循环。
         */
        fun shouldDrain(pendingKey: String, slotKey: String, present: Boolean, recycled: Boolean): Boolean =
            !OverlaySlot.skipRebuild(slotKey, pendingKey, present, recycled)
    }
}