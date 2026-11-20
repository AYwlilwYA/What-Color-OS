package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.ProcessName
import org.junit.Assert.assertEquals
import org.junit.Test

class EntryProcessNameTest {

    @Test
    fun mainProcessNamesUnchanged() {
        assertEquals("com.android.systemui", ProcessName.basePackage("com.android.systemui"))
        assertEquals("com.coloros.gallery3d", ProcessName.basePackage("com.coloros.gallery3d"))
        assertEquals("com.coloros.smartsidebar", ProcessName.basePackage("com.coloros.smartsidebar"))
    }

    @Test
    fun subProcessNamesNormalizeToBasePackage() {
        assertEquals("com.coloros.smartsidebar", ProcessName.basePackage("com.coloros.smartsidebar:ui"))
        assertEquals("com.coloros.smartsidebar", ProcessName.basePackage("com.coloros.smartsidebar:edgepanel"))
        assertEquals("com.coloros.assistantscreen", ProcessName.basePackage("com.coloros.assistantscreen:watch"))
        assertEquals("com.coloros.assistantscreen", ProcessName.basePackage("com.coloros.assistantscreen:iflows_main"))
        assertEquals("com.android.systemui", ProcessName.basePackage("com.android.systemui:screenshot"))
    }

    @Test
    fun unrelatedProcessStaysUnrelated() {
        assertEquals("com.oplus.dmp", ProcessName.basePackage("com.oplus.dmp:main"))
        assertEquals("", ProcessName.basePackage(":onlysuffix"))
    }
}
