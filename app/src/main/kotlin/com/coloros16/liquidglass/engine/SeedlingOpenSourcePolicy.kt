package com.coloros16.liquidglass.engine

/**
 * 打开来源 App 决策（纯 JVM）：哪些活动可打开来源、单击走展开还是直开。
 * 参考 §3.3：验证码未展开非锁屏直开来源；不可展开活动单击打开来源；展开卡下滑小窗打开。
 */
object SeedlingOpenSourcePolicy {

    /** 包名是否可启动（排除占位/系统状态/未知来源）。 */
    fun launchablePackage(pkg: String?): Boolean =
        !pkg.isNullOrBlank() && pkg != "unknown" && pkg != "demo" && pkg != "system_status"

    /** 单击胶囊的动作：EXPAND=展开大卡，OPEN_SOURCE=直开来源。 */
    enum class TapAction { EXPAND, OPEN_SOURCE }

    /**
     * 单击决策：不可展开 → 直开；验证码未展开且非锁屏 → 直开；其余 → 展开。
     * @param expanded 当前是否已展开（已展开一律收起，由调用方处理，这里只判未展开态）
     */
    fun tapAction(
        kind: SeedlingKind,
        expandable: Boolean,
        onLockscreen: Boolean,
    ): TapAction = when {
        !expandable -> TapAction.OPEN_SOURCE
        kind == SeedlingKind.CODE && !onLockscreen -> TapAction.OPEN_SOURCE
        else -> TapAction.EXPAND
    }

    /** 下滑是否触发小窗打开（card_swipe_floating 规则 + 有可启动来源）。 */
    fun swipeDownOpens(cardSwipeFloating: Boolean, pkg: String?): Boolean =
        cardSwipeFloating && launchablePackage(pkg)

    /** 直开失败是否回落展开：仅可展开活动回落；不可展开活动（充电/耳机岛）保持胶囊。 */
    fun fallbackExpandOnOpenFail(expandable: Boolean): Boolean = expandable
}
