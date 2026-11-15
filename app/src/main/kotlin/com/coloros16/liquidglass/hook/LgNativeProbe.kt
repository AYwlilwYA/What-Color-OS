package com.coloros16.liquidglass.hook

import android.view.View

/**
 * 模块 native 探针声明：JNI 直读 View 隐藏字段，验证注入进程内 native 路径的可用性。
 */
object LgNativeProbe {
    external fun probe(view: View, radius: Int): String

    external fun dumpStatics(className: String): String

    external fun blurFirstWindow(radius: Int): String
}
