package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.StatusTintSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 取色来源身份契约：只认系统状态图标与状态栏自身文字。
 * 每条排除都配阳性对照，证明判据不是恒真也不是恒假。
 */
class StatusTintSourceTest {

    // ---------- 系统状态图标 ----------

    /** 真机实测的正确来源必须认。 */
    @Test
    fun acceptsRealSystemIconClasses() {
        assertTrue(StatusTintSource.isSystemStatusIconChain(listOf("com.android.systemui.statusbar.StatusBarIconView")))
        assertTrue(StatusTintSource.isSystemStatusIconChain(listOf("com.oplus.systemui.statusbar.StatIconView")))
        assertTrue(StatusTintSource.isSystemStatusIconChain(listOf("com.oplus.systemui.statusbar.phone.OplusStatusBarIcon")))
    }

    /** 沿继承链命中：子类自身不含关键字，父类含。 */
    @Test
    fun acceptsViaSuperclass() {
        val chain = listOf(
            "com.oplus.systemui.statusbar.MyIcon",
            "android.widget.ImageView",
            "com.android.systemui.statusbar.StatusBarIconView",
        )
        assertTrue(StatusTintSource.isSystemStatusIconChain(chain))
    }

    /** ★阳性对照：真机实测误取来源 EffectiveAnimationView 必须拒（它 tintList 恒白）。 */
    @Test
    fun rejectsAnimationIconThatCausedTheBug() {
        val chain = listOf(
            "com.oplus.systemui.EffectiveAnimationView",
            "android.widget.ImageView",
            "android.view.View",
        )
        assertFalse(StatusTintSource.isSystemStatusIconChain(chain))
    }

    /** 普通 ImageView / 通知图标不误认。 */
    @Test
    fun rejectsPlainImageViews() {
        assertFalse(StatusTintSource.isSystemStatusIconChain(listOf("android.widget.ImageView", "android.view.View")))
        assertFalse(StatusTintSource.isSystemStatusIconChain(listOf("com.android.systemui.statusbar.notification.NotificationIconView")))
        assertFalse(StatusTintSource.isSystemStatusIconChain(emptyList()))
    }

    /** 超出上溯层数不再认（防病态深链）。 */
    @Test
    fun stopsAtMaxDepth() {
        val deep = ArrayList<String>()
        for (i in 0 until StatusTintSource.MAX_DEPTH) deep.add("android.widget.Layer$i")
        deep.add("com.android.systemui.statusbar.StatusBarIconView")
        assertFalse("第 MAX_DEPTH+1 层不该被认", StatusTintSource.isSystemStatusIconChain(deep))
        val atLimit = deep.subList(1, deep.size)
        assertTrue("恰在层数内应被认", StatusTintSource.isSystemStatusIconChain(atLimit))
    }

    // ---------- 状态栏自身文字 ----------

    /** 时钟 / 运营商 / 电量百分比类名认。 */
    @Test
    fun acceptsStatusTextClassNames() {
        assertTrue(StatusTintSource.isStatusTextClassName("com.oplus.systemui.qs.widget.OplusQSClock"))
        assertTrue(StatusTintSource.isStatusTextClassName("com.oplus.systemui.qs.widget.SimpleQsClock"))
        assertTrue(StatusTintSource.isStatusTextClassName("com.android.systemui.statusbar.OplusSecondCarrierText"))
        assertTrue(StatusTintSource.isStatusTextClassName("com.android.systemui.battery.BatteryPercentView"))
    }

    /** ★阳性对照：面板内容文字必须拒（它们不随图标色）。 */
    @Test
    fun rejectsPanelContentText() {
        assertFalse(StatusTintSource.isStatusTextClassName("android.widget.TextView"))
        assertFalse(StatusTintSource.isStatusTextClassName("com.android.systemui.statusbar.notification.PrecomputedImageFloatingTextView"))
        assertFalse(StatusTintSource.isStatusTextClassName("com.oplus.systemui.notification.OplusNotificationDateTimeView"))
    }

    /** 资源名通道：无 id 时靠类名，有 id 时资源名也能认。 */
    @Test
    fun resNameChannelWorks() {
        assertTrue(StatusTintSource.isStatusTextResName("clock"))
        assertTrue(StatusTintSource.isStatusTextResName("battery_percent_view"))
        assertTrue(StatusTintSource.isStatusTextResName("carrier_text"))
        assertFalse(StatusTintSource.isStatusTextResName("notification_title"))
        assertFalse(StatusTintSource.isStatusTextResName(null))
        assertFalse(StatusTintSource.isStatusTextResName(""))
    }

    /** 综合判定：类名不中但资源名中 → 认；两者都不中 → 拒。 */
    @Test
    fun combinedPredicate() {
        assertTrue(StatusTintSource.isStatusText("android.widget.TextView", "clock"))
        assertTrue(StatusTintSource.isStatusText("com.x.OplusQSClock", null))
        assertFalse(StatusTintSource.isStatusText("android.widget.TextView", "notification_title"))
        assertFalse(StatusTintSource.isStatusText("android.widget.TextView", null))
    }
}