package com.coloros16.liquidglass.engine

/**
 * 属性写入的原值记账：写前存一次、撤时按存回写、销毁时清账。
 *
 * 泛型承载具体属性组，本件只管账不碰平台类型。
 */
class OriginLedger<T> {

    private val origins = HashMap<String, T>()

    /** 该键是否已记过原值。 */
    fun recorded(key: String): Boolean = origins.containsKey(key)

    /**
     * 首次写入前记原值；已记过则不覆盖。
     *
     * 覆盖会把本件上一轮的输出当成「系统原值」，还原时就回不去了。
     */
    fun record(key: String, origin: T): Boolean {
        if (origins.containsKey(key)) return false
        origins[key] = origin
        return true
    }

    /** 取出并移除记账；无记账返回 null 表示本件没写过、不得乱撤。 */
    fun take(key: String): T? = origins.remove(key)

    /** 只清账不取值（视图已销毁，不再写属性）。 */
    fun forget(key: String) {
        origins.remove(key)
    }

    fun size(): Int = origins.size

    fun clear() = origins.clear()

    companion object {
        /** 记账键：视图身份 + 态，两面互不干扰。 */
        fun keyOf(viewId: Int, state: SceneState): String = "$viewId|$state"

        /** 该视图的全部记账键（销毁清账用）。 */
        fun keysOf(viewId: Int): List<String> = SceneState.values().map { keyOf(viewId, it) }
    }
}