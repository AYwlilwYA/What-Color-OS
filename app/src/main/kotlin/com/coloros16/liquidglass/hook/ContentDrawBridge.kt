package com.coloros16.liquidglass.hook

/**
 * `onDrawContent` 接管判据与三段调度。
 *
 * 系统 shader 可用不等于系统会画它（C17 原生图像为空时走 placeholder 分支），
 * 所以判据换向成「模块自家背景源/几何/场景开关/shader 齐了就直接录玻璃」。
 */
object ContentDrawBridge {

    /**
     * 判据换向入口。
     *
     * @param isBackgroundScrim 整屏 scrim 拦截（scrim 有独立策略，命中即视为接管）
     * @param ownedReady 模块自家背景源/几何/场景开关/shader 是否齐
     */
    inline fun shouldRenderOwned(
        isBackgroundScrim: () -> Boolean,
        ownedReady: () -> Boolean,
    ): Boolean = isBackgroundScrim() || ownedReady()

    /**
     * 三段定死：自家绘制 → 不满足才让原生分支恰好执行一次。
     *
     * 原生调用放在 catch 之外，宿主原返回值与异常原样保留。
     */
    inline fun dispatch(
        ownedDraw: () -> Boolean,
        original: () -> Any?,
        onOwnedFailure: (Throwable) -> Unit,
    ): Any? {
        val handled = try {
            ownedDraw()
        } catch (failure: Throwable) {
            try {
                onOwnedFailure(failure)
            } catch (_: Throwable) {
            }
            false
        }
        return if (handled) null else original()
    }
}
