package com.coloros16.liquidglass.ui.aod

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import com.coloros16.liquidglass.engine.edit.ClockFonts
import com.coloros16.liquidglass.engine.edit.ClockStyle

/**
 * 时钟字型唯一解析件：默认档取包内细体，用户改过字体或字重才走系统族。
 *
 * 两套编辑画布与预览共用，避免同一配置在不同入口画出不同笔画。
 */
object ClockTypefaces {

    private const val THIN_ASSET = "aod/clock_thin.ttf"

    private var thin: Typeface? = null
    private var date: Typeface? = null

    /** 时间字型；默认字体 + 默认字重时返回细体。 */
    fun timeOf(context: Context, style: ClockStyle): Typeface {
        val family = ClockFonts.normalize(style.fontFamily)
        if (family == ClockFonts.SANS && style.weight == ClockStyle.DEFAULT_WEIGHT) {
            return thin(context)
        }
        val base = when (family) {
            ClockFonts.SERIF -> Typeface.SERIF
            ClockFonts.MONO -> Typeface.MONOSPACE
            ClockFonts.CASUAL -> Typeface.create("casual", Typeface.NORMAL)
            else -> Typeface.SANS_SERIF
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return base
        return runCatching { Typeface.create(base, style.weight, false) }.getOrDefault(base)
    }

    /** 日期与附加信息字型。 */
    fun date(): Typeface = date
        ?: Typeface.create("sans-serif-medium", Typeface.NORMAL).also { date = it }

    private fun thin(context: Context): Typeface = thin
        ?: (
            runCatching { Typeface.createFromAsset(context.assets, THIN_ASSET) }.getOrNull()
                ?: Typeface.create("sans-serif-thin", Typeface.NORMAL)
            ).also { thin = it }
}