package com.coloros16.liquidglass.hook

/** 桌面时钟锚点跨进程协议：Launcher 进程发，SystemUI 进程收。 */
internal object DesktopClockProtocol {

    const val ACTION = "com.coloros16.liquidglass.action.DESKTOP_CLOCK_ANCHOR"

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
