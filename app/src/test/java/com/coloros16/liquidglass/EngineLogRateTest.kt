package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.spec.EngineLogRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineLogRateTest {

    @Test
    fun textColorIsHot() {
        assertTrue(EngineLogRate.isHot("TextColor"))
    }

    @Test
    fun allFourFrameSourcesAreHot() {
        for (s in listOf("TextColor", "SuppressPolicy", "text.setTextColorContrast", "text.onDrawResample")) {
            assertTrue("$s 应被限流", EngineLogRate.isHot(s))
        }
    }

    @Test
    fun installAuditSourcesAreNeverThrottled() {
        for (s in listOf(
            "posteffect.drawBlurShader", "posteffect.onDrawContent.base",
            "autoBlur.map", "notif.cardMap", "cc.maskBlurMap", "CaptureEngine",
        )) {
            assertFalse("$s 不该被限流", EngineLogRate.isHot(s))
            assertTrue(EngineLogRate.alwaysAdmit(s))
        }
    }

    @Test
    fun newSecondAlwaysAdmits() {
        assertTrue(EngineLogRate.admit(sameSecond = false, countInWindow = 1))
        assertTrue("跨秒即使计数很大也放行", EngineLogRate.admit(sameSecond = false, countInWindow = 9999))
    }

    @Test
    fun withinLimitAdmits() {
        assertTrue(EngineLogRate.admit(sameSecond = true, countInWindow = 1))
        assertTrue(EngineLogRate.admit(sameSecond = true, countInWindow = EngineLogRate.PER_SEC))
    }

    @Test
    fun overLimitSuppresses() {
        assertFalse(EngineLogRate.admit(sameSecond = true, countInWindow = EngineLogRate.PER_SEC + 1))
        assertFalse(EngineLogRate.admit(sameSecond = true, countInWindow = 3000))
    }

    @Test
    fun limitIsSmallEnoughToProtectRingBuffer() {
        assertTrue("四源合计每秒上限须远低于实测 2628 行/秒", EngineLogRate.PER_SEC * 4 <= 200)
        assertTrue(EngineLogRate.PER_SEC > 0)
    }

    @Test
    fun hotSetSizeMatchesDocumentedFour() {
        assertEquals(4, EngineLogRate.HOT_SOURCES.size)
    }

    @Test
    fun positiveControlSameCountDifferentWindowFlips() {
        val n = EngineLogRate.PER_SEC + 1
        assertFalse("同一秒超限 → 压制", EngineLogRate.admit(sameSecond = true, countInWindow = n))
        assertTrue("同样计数但跨秒 → 放行", EngineLogRate.admit(sameSecond = false, countInWindow = n))
    }

    @Test
    fun positiveControlHotVsColdSourceFlips() {
        assertTrue(EngineLogRate.isHot("TextColor"))
        assertFalse(EngineLogRate.isHot("posteffect.drawBlurShader"))
    }

    @Test
    fun unknownSourceIsCold() {
        assertFalse(EngineLogRate.isHot("some.new.spec"))
        assertTrue(EngineLogRate.alwaysAdmit("some.new.spec"))
    }
}
