package com.coloros16.liquidglass.hook.capture

/**
 * 静态抓屏档的首帧补抓判定（纯逻辑，无 Android 依赖）。
 *
 * 静态语义 = 不做周期/常规抓屏，但**必须能拿到第一帧**：快照为空时若一律拒绝入队，
 * 冷启动或缓存被清后就永远没有背景源，靠整屏快照的元素全部退回系统模糊。
 *
 * 放行条件三个同时成立：静态档 + 当前无可用快照 + 不是 SF 渲染档
 * （SF 档背景由系统链每帧直供，模块本就不该抓）。
 * 拿到首帧后 hasSnapshot 恒真 → 不再放行，仍然零周期抓屏。
 * 失败上限由调用方的有界计数承担，本判定只回答「该不该抓」。
 */
object StaticCaptureBootstrap {

    /** 首帧补抓的失败上限：达到即放弃，等下一次事件（换壁纸/开关切换）重新开窗。 */
    const val MAX_FAILURES = 3

    /** 是否放行一次首帧补抓；[staticMode] 由调用方传入 Prefs.BG_CAPTURE_MODE_STATIC。 */
    fun shouldBootstrap(
        captureMode: Int,
        staticMode: Int,
        hasSnapshot: Boolean,
        sfRenderMode: Boolean,
        failures: Int,
    ): Boolean {
        if (hasSnapshot) return false
        if (sfRenderMode) return false
        if (captureMode != staticMode) return false
        return failures < MAX_FAILURES
    }

    /** 失败计数是否已达上限（达到 = 本窗口不再尝试）。 */
    fun exhausted(failures: Int): Boolean = failures >= MAX_FAILURES
}
