package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.KeyguardGlassPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyguardWidgetPanelPolicyTest {

    private val cellClass = "com.oplus.keyguard.style.widgets.container.view.WidgetItemContainer"
    private val panelClass = "com.oplus.keyguard.style.widgets.container.view.InstantCardView"
    private val emptyClass = "com.oplus.keyguard.style.widgets.container.view.EmptyCardView"
    private val shadowClass = "com.oplus.keyguard.style.widgets.container.view.WidgetShadowView"
    private val cellPath = listOf("$cellClass#remote_view_container", cellClass, "androidx.constraintlayout.widget.ConstraintLayout")
    private val panelPath = listOf("$panelClass#instant_card", panelClass, "android.widget.FrameLayout") + cellPath

    @Test
    fun cellRootIsNeverCardEvenWhenClickable() {
        assertEquals(
            "外格容器（留白+阴影）不得判 CARD，否则玻璃比可见面板大一圈",
            KeyguardGlassPolicy.NONE,
            KeyguardGlassPolicy.role(cellPath, "remote_view_container", false, true, true, 1f),
        )
        assertEquals(
            KeyguardGlassPolicy.NONE,
            KeyguardGlassPolicy.role(cellPath, "remote_view_container", false, false, true, 1f, trustedRole = KeyguardGlassPolicy.CARD),
        )
    }

    @Test
    fun panelIsCardWithoutBackgroundOrClickable() {
        assertEquals(
            "即时卡面板背景为 null（可见面靠 outline 裁剪），仍须判 CARD",
            KeyguardGlassPolicy.CARD,
            KeyguardGlassPolicy.role(panelPath, "instant_card", false, false, false, 1f),
        )
        assertEquals(
            KeyguardGlassPolicy.CARD,
            KeyguardGlassPolicy.role(panelPath, "instant_card", false, false, false, 1f, trustedRole = KeyguardGlassPolicy.CARD),
        )
    }

    @Test
    fun emptyCardPanelIsCard() {
        val path = listOf("$emptyClass#instant_card", emptyClass) + cellPath
        assertEquals(
            KeyguardGlassPolicy.CARD,
            KeyguardGlassPolicy.role(path, "instant_card", false, true, false, 1f),
        )
    }

    @Test
    fun shadowViewIsNotPanel() {
        val path = listOf("$shadowClass#widget_shadow", shadowClass) + cellPath
        assertFalse(KeyguardGlassPolicy.isWidgetPanel(shadowClass, "widget_shadow"))
        assertEquals(
            "阴影 ImageView 不是面板，不得挂玻璃",
            KeyguardGlassPolicy.NONE,
            KeyguardGlassPolicy.role(path, "widget_shadow", false, false, false, 1f, trustedRole = KeyguardGlassPolicy.CARD),
        )
    }

    @Test
    fun obfuscatedPanelStillRecognizedById() {
        assertEquals(
            "混淆后面板类名失真，资源 id 仍可识别",
            KeyguardGlassPolicy.CARD,
            KeyguardGlassPolicy.role(listOf("d#instant_card", "d") + cellPath, "instant_card", false, false, false, 1f),
        )
        assertTrue(KeyguardGlassPolicy.isWidgetCellRoot("So", "remote_view_container"))
    }

    @Test
    fun genericWidgetCardHeuristicUnchanged() {
        val path = listOf("com.oplus.keyguard.widget.Foo#widget_card", "com.oplus.keyguard.widget.Foo")
        assertEquals(
            KeyguardGlassPolicy.CARD,
            KeyguardGlassPolicy.role(path, "widget_card", false, true, false, 1f),
        )
        assertEquals(
            "无背景不可点击的普通 widget 视图维持原行为",
            KeyguardGlassPolicy.NONE,
            KeyguardGlassPolicy.role(path, "widget_card", false, false, false, 1f),
        )
    }
}
