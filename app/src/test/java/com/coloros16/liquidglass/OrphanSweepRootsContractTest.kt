package com.coloros16.liquidglass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.ref.WeakReference

/**
 * 孤儿层清扫的取根契约：热重载后新类账本为空，扫根不能依赖它。
 *
 * 纯 JVM 模拟 seenRoots 的弱引用账本语义（宿主视图由平台提供，此处只核算法）。
 */
class OrphanSweepRootsContractTest {

    private class Root(val name: String)

    private val seen = ArrayList<WeakReference<Root>>()

    private fun note(r: Root?) {
        if (r == null) return
        seen.removeAll { it.get() == null || it.get() === r }
        seen.add(WeakReference(r))
        if (seen.size > 8) seen.removeAt(0)
    }

    private fun rootsFromLedger(): List<Root> = seen.mapNotNull { it.get() }.distinct()

    @Test
    fun ledgerSurvivesEmptyMountedMap() {
        val a = Root("aod-window")
        note(a)
        val mountedIsEmpty = true
        assertTrue(mountedIsEmpty)
        assertEquals("★账本为空时最需要清扫，取根不能恒空", 1, rootsFromLedger().size)
        assertEquals(a, rootsFromLedger()[0])
    }

    @Test
    fun positiveControlMountedOnlyYieldsNothingWhenEmpty() {
        note(Root("aod-window"))
        val mountedKeys = emptyList<Root>()
        val fromMountedOnly = mountedKeys.distinct()
        assertEquals("阳性对照：只按 mounted 取根必空", 0, fromMountedOnly.size)
        assertTrue("而账本路径拿得到", rootsFromLedger().isNotEmpty())
    }

    @Test
    fun sameRootNotDuplicated() {
        val a = Root("w")
        note(a)
        note(a)
        note(a)
        assertEquals(1, rootsFromLedger().size)
        assertEquals("重复登记不该堆账", 1, seen.size)
    }

    @Test
    fun distinctRootsAllKept() {
        for (i in 0 until 5) note(Root("w$i"))
        assertEquals(5, rootsFromLedger().size)
    }

    @Test
    fun ledgerIsCappedAtEight() {
        for (i in 0 until 20) note(Root("w$i"))
        assertTrue("账本必须有上限，不能无限长", seen.size <= 8)
        assertEquals("w19", rootsFromLedger().last().name)
    }

    /** 显式清空的弱引用等价于「已被回收」，不依赖 GC 时机（否则测试不确定）。 */
    private fun clearedRef(): WeakReference<Root> = WeakReference(Root("temp")).also { it.clear() }

    @Test
    fun collectedRootsAreInvisibleToSweep() {
        val keep = Root("keep")
        note(keep)
        seen.add(clearedRef())
        assertEquals("★弱引用被回收后取根必须跳过它，不能拿 null 当起点", 1, rootsFromLedger().size)
        assertEquals("keep", rootsFromLedger()[0].name)
    }

    @Test
    fun deadEntriesArePrunedOnNextNote() {
        val keep = Root("keep")
        note(keep)
        seen.add(clearedRef())
        note(keep)
        assertTrue("再次登记时顺手清死引用", seen.none { it.get() == null })
        assertEquals(1, seen.size)
    }

    @Test
    fun nullRootIsIgnored() {
        note(null)
        assertEquals(0, seen.size)
        assertFalse(rootsFromLedger().isNotEmpty())
    }

    @Test
    fun ledgerOrderKeepsMostRecentLast() {
        val a = Root("a")
        val b = Root("b")
        note(a)
        note(b)
        note(a)
        assertEquals(listOf("b", "a"), rootsFromLedger().map { it.name })
    }
}
