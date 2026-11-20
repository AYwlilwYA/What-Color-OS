package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.statusbar.pickIconTint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 暗色分发回调取色：两个回调的图标色位置不同，必须各取各的。 */
class StatusBarDuoTintSourceTest {

    private val iconTint = 0xFFFFFFFF.toInt()
    private val contrastTint = 0xFF000000.toInt()
    private val areas = ArrayList<Any?>()

    @Test
    fun onDarkChanged_takesThirdArgAsIconTint() {
        val args = arrayOf<Any?>(areas, 1.0f, iconTint)
        assertEquals(iconTint, pickIconTint("onDarkChanged", args))
    }

    @Test
    fun onDarkChanged_ignoresContrastTintPosition() {
        val args = arrayOf<Any?>(areas, 1.0f, contrastTint)
        assertEquals(contrastTint, pickIconTint("onDarkChanged", args))
    }

    @Test
    fun onDarkChangedWithContrast_takesSecondArgNotContrastTint() {
        val args = arrayOf<Any?>(areas, iconTint, contrastTint)
        assertEquals(iconTint, pickIconTint("onDarkChangedWithContrast", args))
    }

    @Test
    fun unknownMethod_returnsNull() {
        assertNull(pickIconTint("onSomethingElse", arrayOf<Any?>(areas, iconTint)))
    }

    @Test
    fun shortArgs_returnsNullInsteadOfThrowing() {
        assertNull(pickIconTint("onDarkChanged", arrayOf<Any?>(areas)))
        assertNull(pickIconTint("onDarkChangedWithContrast", arrayOf<Any?>(areas)))
        assertNull(pickIconTint("onDarkChanged", null))
    }
}
