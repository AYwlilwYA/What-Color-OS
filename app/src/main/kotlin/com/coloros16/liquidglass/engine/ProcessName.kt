package com.coloros16.liquidglass.engine

/** 进程名归一为基础包名：`pkg:sub` 子进程（如 smartsidebar:ui/:edgepanel）与主进程同链。 */
object ProcessName {
    @JvmStatic
    fun basePackage(processName: String): String = processName.substringBefore(':')
}
