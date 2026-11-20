package com.coloros16.liquidglass.hook

/**
 * 桌面时钟跨进程协议：锚点上行（Launcher → SystemUI）与交接下行（SystemUI → Launcher）
 * 复用同一个 action，靠 [EXTRA_DIRECTION] 分流，不另开第二条广播通道。
 */
internal object DesktopClockProtocol {

    const val ACTION = "com.coloros16.liquidglass.action.DESKTOP_CLOCK_ANCHOR"

    const val EXTRA_DIRECTION = "dir"

    /** 上行：桌面时钟矩形（缺省值，旧包不带该 extra 时按此处理）。 */
    const val DIR_ANCHOR = 0

    /** 下行：交接进度，桌面进程据此写小部件可见度。 */
    const val DIR_HANDOFF = 1

    /** 下行交接进度：0 = 系统那颗钟全亮，1 = 桌面小部件全亮。 */
    const val EXTRA_HANDOFF = "handoff"

    /** 下行是否允许桌面进程写小部件（让位档恒 false）。 */
    const val EXTRA_HANDOFF_ENABLED = "handoffOn"

    const val EXTRA_VISIBLE = "visible"
    const val EXTRA_CENTER_X = "cx"
    const val EXTRA_CENTER_Y = "cy"
    const val EXTRA_SCALE = "scale"
    const val EXTRA_WIDGET_W = "widgetW"
    const val EXTRA_WIDGET_H = "widgetH"
    const val EXTRA_SCREEN_W = "screenW"
    const val EXTRA_SCREEN_H = "screenH"
    const val EXTRA_REASON = "reason"

    const val SYSTEMUI_PKG = "com.android.systemui"
    const val LAUNCHER_PKG = "com.android.launcher"
}
