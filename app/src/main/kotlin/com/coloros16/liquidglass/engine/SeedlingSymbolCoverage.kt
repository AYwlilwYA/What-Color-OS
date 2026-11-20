package com.coloros16.liquidglass.engine

/**
 * 岛符号绘制覆盖契约（纯 JVM）：所有可能产出的符号名必须有专属绘制分支，
 * 不能落 else 圆点兜底（通话接听/挂断/验证码/消息等按钮需可区分）。
 */
object SeedlingSymbolCoverage {

    /** drawSymbol 已实现专属绘制的符号名集合。 */
    val DRAWN_SYMBOLS: Set<String> = setOf(
        "PLAY", "PAUSE", "SKIP_NEXT", "SKIP_PREV", "HEART", "MIC", "TIMER",
        "BATTERY", "HEADSET", "PHONE", "PHONE_END", "SPEAKER", "CLOSE",
        "LOCK_OPEN", "MESSAGE", "NAVIGATION", "MUSIC",
    )

    /** 通知动作词表可能产出的全部符号。 */
    fun actionSymbols(): Set<String> = setOf(
        "PHONE", "PHONE_END", "SPEAKER", "PAUSE", "CLOSE", "PLAY", "TIMER", "MESSAGE", "INFO",
    )

    /** kind 胶囊左符号可能产出的全部符号。 */
    fun kindSymbols(): Set<String> = setOf(
        "PHONE", "TIMER", "MIC", "MESSAGE", "LOCK_OPEN", "NAVIGATION", "MUSIC", "BATTERY", "HEADSET", "INFO",
    )

    /** 是否有专属绘制（INFO 是有意兜底圆点，不算缺口）。 */
    fun hasDistinctGlyph(name: String): Boolean = name in DRAWN_SYMBOLS || name == "INFO"
}
