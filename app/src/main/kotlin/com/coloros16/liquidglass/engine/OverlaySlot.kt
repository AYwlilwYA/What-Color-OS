package com.coloros16.liquidglass.engine

/**
 * 单槽位图层的换层协议：写方先键后图、读方先图后键。
 *
 * 只算「取到的这一层能不能用」，位图生命周期由调用方负责（不主动回收，交 GC）。
 */
object OverlaySlot {

    /** 读方判定：键要匹配、层要在、层不能已回收。 */
    fun usable(slotKey: String, wantKey: String, present: Boolean, recycled: Boolean): Boolean =
        present && !recycled && slotKey == wantKey

    /** 写方判定：键相同且层健在则无需重建。 */
    fun skipRebuild(slotKey: String, wantKey: String, present: Boolean, recycled: Boolean): Boolean =
        slotKey == wantKey && present && !recycled

    /**
     * 换层是否可安全回收旧层。
     *
     * 绘制线程可能已取走旧层正在画 ⇒ 恒不主动回收。
     */
    fun mayRecycleOld(): Boolean = false
}