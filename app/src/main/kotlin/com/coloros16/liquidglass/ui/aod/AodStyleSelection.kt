package com.coloros16.liquidglass.ui.aod

import com.coloros16.liquidglass.config.Prefs

/**
 * 当前应用款样式的解析口径：配置快照优先，其次样式键，最后默认款。
 *
 * 预览、独立编辑页、设置页必须同口径 —— 各读各的存储会出现
 * 「抽屉换了款、编辑页仍按旧款取锚点」的分叉（锚点差一整段位移）。
 * 读取通道由调用方注入：设置页走框架侧优先，控制器走本地 XML。
 */
object AodStyleSelection {
    fun resolve(
        configId: String?,
        styleIdOfConfig: (String) -> String?,
        styleId: String?,
    ): String =
        configId?.takeIf { it.isNotBlank() }?.let(styleIdOfConfig)
            ?: styleId?.takeIf { it.isNotBlank() }
            ?: Prefs.DEFAULT_ALIVE_AOD_STYLE_ID
}
