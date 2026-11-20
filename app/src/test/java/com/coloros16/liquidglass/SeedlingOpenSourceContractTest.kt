package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SeedlingKind
import com.coloros16.liquidglass.engine.SeedlingOpenSourcePolicy
import com.coloros16.liquidglass.engine.SeedlingOpenSourcePolicy.TapAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 打开来源 App 决策契约，含阳性对照。 */
class SeedlingOpenSourceContractTest {

    // ---------- 包名可启动判定 ----------

    @Test
    fun launchableRealPackage() {
        assertTrue(SeedlingOpenSourcePolicy.launchablePackage("com.tencent.mm"))
        assertTrue(SeedlingOpenSourcePolicy.launchablePackage("com.sankuai.meituan"))
    }

    @Test
    fun placeholderPackagesBlocked_positiveControl() {
        assertFalse("阳性对照：占位来源不可启动", SeedlingOpenSourcePolicy.launchablePackage("demo"))
        assertFalse(SeedlingOpenSourcePolicy.launchablePackage("unknown"))
        assertFalse(SeedlingOpenSourcePolicy.launchablePackage("system_status"))
        assertFalse(SeedlingOpenSourcePolicy.launchablePackage(null))
        assertFalse(SeedlingOpenSourcePolicy.launchablePackage(""))
        assertFalse(SeedlingOpenSourcePolicy.launchablePackage("  "))
    }

    // ---------- 单击决策 ----------

    @Test
    fun normalMediaExpands() {
        assertEquals(TapAction.EXPAND, SeedlingOpenSourcePolicy.tapAction(SeedlingKind.MEDIA, expandable = true, onLockscreen = false))
        assertEquals(TapAction.EXPAND, SeedlingOpenSourcePolicy.tapAction(SeedlingKind.MESSAGE, true, false))
    }

    @Test
    fun nonExpandableOpensSource() {
        assertEquals("不可展开活动单击直开来源", TapAction.OPEN_SOURCE, SeedlingOpenSourcePolicy.tapAction(SeedlingKind.STATUS, expandable = false, onLockscreen = false))
        assertEquals(TapAction.OPEN_SOURCE, SeedlingOpenSourcePolicy.tapAction(SeedlingKind.MEDIA, false, false))
    }

    @Test
    fun codeOpensSourceOffLockscreen() {
        assertEquals("验证码未展开非锁屏直开", TapAction.OPEN_SOURCE, SeedlingOpenSourcePolicy.tapAction(SeedlingKind.CODE, true, onLockscreen = false))
    }

    @Test
    fun codeOnLockscreenExpands_positiveControl() {
        assertEquals("阳性对照：锁屏上验证码不直开（防误触泄露）", TapAction.EXPAND, SeedlingOpenSourcePolicy.tapAction(SeedlingKind.CODE, true, onLockscreen = true))
    }

    // ---------- 下滑小窗 ----------

    @Test
    fun swipeDownOpensWhenRuleAndPackage() {
        assertTrue(SeedlingOpenSourcePolicy.swipeDownOpens(cardSwipeFloating = true, pkg = "com.tencent.mm"))
    }

    @Test
    fun swipeDownBlocked_positiveControl() {
        assertFalse("阳性对照：规则关不触发", SeedlingOpenSourcePolicy.swipeDownOpens(false, "com.tencent.mm"))
        assertFalse("阳性对照：占位来源不触发", SeedlingOpenSourcePolicy.swipeDownOpens(true, "demo"))
        assertFalse(SeedlingOpenSourcePolicy.swipeDownOpens(true, null))
    }

    @Test
    fun fallbackExpandOnlyWhenExpandable() {
        assertTrue("可展开活动直开失败回落展开", SeedlingOpenSourcePolicy.fallbackExpandOnOpenFail(expandable = true))
        assertFalse("阳性对照：不可展开活动（充电/耳机岛）直开失败不展开", SeedlingOpenSourcePolicy.fallbackExpandOnOpenFail(expandable = false))
    }
}
