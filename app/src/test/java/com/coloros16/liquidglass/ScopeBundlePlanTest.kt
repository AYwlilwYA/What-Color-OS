package com.coloros16.liquidglass

import com.coloros16.liquidglass.diagnostics.ScopeBundlePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopeBundlePlanTest {

    @Test
    fun parseScopeSkipsCommentsAndBlankAndDedupes() {
        val text = """
            # 注释
            com.android.systemui

              com.android.launcher
            # 又一行注释
            com.android.systemui
        """.trimIndent()
        assertEquals(listOf("com.android.systemui", "com.android.launcher"), ScopeBundlePlan.parseScope(text))
    }

    @Test
    fun parseScopeEmptyTextYieldsEmpty() {
        assertEquals(emptyList<String>(), ScopeBundlePlan.parseScope(""))
        assertEquals(emptyList<String>(), ScopeBundlePlan.parseScope("# only comment\n\n"))
    }

    @Test
    fun parsePmPathTakesAllSplitsBaseFirst() {
        val out = """
            package:/system_ext/priv-app/SystemUI/split_config.arm64_v8a.apk
            package:/system_ext/priv-app/SystemUI/base.apk
        """.trimIndent()
        val paths = ScopeBundlePlan.parsePmPath(out)
        assertEquals(2, paths.size)
        assertTrue("base.apk 必须排最前", paths.first().endsWith("/base.apk"))
    }

    @Test
    fun parsePmPathIgnoresNoiseAndDedupes() {
        val out = "Failure: not found\npackage:/a/base.apk\npackage:/a/base.apk\n\n"
        assertEquals(listOf("/a/base.apk"), ScopeBundlePlan.parsePmPath(out))
        assertEquals(emptyList<String>(), ScopeBundlePlan.parsePmPath("Failure: not found"))
    }

    @Test
    fun entryNameKeepsSplitFileNamesDistinct() {
        assertEquals("com.x/base.apk", ScopeBundlePlan.entryName("com.x", "/system/app/X/base.apk"))
        assertEquals(
            "com.x/split_config.arm64_v8a.apk",
            ScopeBundlePlan.entryName("com.x", "/system/app/X/split_config.arm64_v8a.apk"),
        )
        assertTrue(
            "同包不同 split 不能撞条目名",
            ScopeBundlePlan.entryName("com.x", "/p/base.apk") !=
                ScopeBundlePlan.entryName("com.x", "/p/split_config.arm64_v8a.apk"),
        )
    }

    @Test
    fun summaryCountsOkAndFailedSeparately() {
        val items = listOf(
            ScopeBundlePlan.Item("a", 2, 100, null),
            ScopeBundlePlan.Item("b", 0, 0, "未安装"),
            ScopeBundlePlan.Item("c", 1, 50, null),
            ScopeBundlePlan.Item("d", 0, 0, null),
        )
        val s = ScopeBundlePlan.summarize(items)
        assertEquals(2, s.okPackages)
        assertEquals(listOf("b", "d"), s.failedPackages)
        assertEquals(3, s.entries)
        assertEquals(150L, s.bytes)
    }

    @Test
    fun positiveControlAllFailedMeansNoOkPackages() {
        val s = ScopeBundlePlan.summarize(
            listOf(ScopeBundlePlan.Item("a", 0, 0, "读取失败"), ScopeBundlePlan.Item("b", 0, 0, "未安装")),
        )
        assertEquals(0, s.okPackages)
        assertEquals(0, s.entries)
        assertEquals(2, s.failedPackages.size)
    }
}
