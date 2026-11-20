package com.coloros16.liquidglass.hook.spec

/**
 * 引擎全量流水的高频源限流判定（纯逻辑，无 Android 依赖）。
 *
 * 逐帧判定的源（每 TextView 每帧一条）会把 logcat 环形缓冲冲爆，
 * 导致装载期审计被挤掉、读取端 EOF 断流 —— 关键证据被自己的诊断噪音毁掉。
 * 故高频源按「每秒每源前 N 条放行」限流，超限只累计条数，跨秒汇总一条。
 */
object EngineLogRate {

    /** 每个高频源每秒放行条数上限。 */
    const val PER_SEC = 20

    /** 逐帧判定源：对定位渲染问题无价值，但产量极高。 */
    val HOT_SOURCES = setOf(
        "TextColor", "SuppressPolicy", "text.setTextColorContrast", "text.onDrawResample",
    )

    fun isHot(source: String): Boolean = source in HOT_SOURCES

    /**
     * 该条是否放行。
     * @param sameSecond 与上次放行是否处于同一秒窗口
     * @param countInWindow 本秒窗口内已受理条数（含本条）
     */
    fun admit(sameSecond: Boolean, countInWindow: Int): Boolean =
        !sameSecond || countInWindow <= PER_SEC

    /** 低频源（装载审计、hook 挂载、渲染结论）永不限流。 */
    fun alwaysAdmit(source: String): Boolean = !isHot(source)
}
