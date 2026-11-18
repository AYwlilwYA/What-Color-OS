package com.coloros16.liquidglass

import com.coloros16.liquidglass.entry.CrashGuardPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 循环崩守卫判定契约：装机宽限只豁免「不是崩溃」的重启，真崩循环必须照拦。
 * 每条豁免都配阳性对照，证明防守能力没被改松。
 */
class CrashGuardPolicyTest {

    private val t0 = 1_000_000L

    private fun ledger(vararg pairs: Pair<Long, Int>): String =
        pairs.joinToString(";") { "${it.first},${it.second}" }

    // ---------- 原有防守能力（不得被宽限削弱） ----------

    /** 阳性对照：无 APK 戳（取不到）时，窗口内 4 个不同 pid 仍必须拦并关总开关。 */
    @Test
    fun realCrashLoopStillBlocksWithoutApkStamp() {
        val d = CrashGuardPolicy.decide(
            now = t0, pid = 4,
            ledger = ledger(t0 - 1000 to 1, t0 - 2000 to 2, t0 - 3000 to 3),
            apkStamp = 0L, lastStamp = 0L, hotReload = false,
        )
        assertTrue(d.block)
        assertTrue(d.forceMasterOff)
        assertEquals("", d.ledger)
    }

    /** ★ 关键阳性对照：装机宽限窗过去之后，同一个包连续崩 4 次仍必须拦。 */
    @Test
    fun crashLoopAfterGraceWindowStillBlocks() {
        val apk = t0 - CrashGuardPolicy.INSTALL_GRACE_MS - 1000
        val d = CrashGuardPolicy.decide(
            now = t0, pid = 4,
            ledger = ledger(t0 - 1000 to 1, t0 - 2000 to 2, t0 - 3000 to 3),
            apkStamp = apk, lastStamp = apk, hotReload = false,
        )
        assertTrue("宽限窗过后真崩循环必须仍被拦", d.block)
        assertTrue(d.forceMasterOff)
    }

    /** 阳性对照：宽限只免计数，不把阈值改松——窗外重启不算，窗内 3 个不拦。 */
    @Test
    fun threePidsBelowThresholdDoesNotBlock() {
        val d = CrashGuardPolicy.decide(
            now = t0, pid = 3,
            ledger = ledger(t0 - 1000 to 1, t0 - 2000 to 2),
            apkStamp = 0L, lastStamp = 0L, hotReload = false,
        )
        assertFalse(d.block)
        assertFalse(d.forceMasterOff)
        assertEquals(3, CrashGuardPolicy.parse(d.ledger, t0).size)
    }

    @Test
    fun samePidRepeatedIsNotALoop() {
        val d = CrashGuardPolicy.decide(
            now = t0, pid = 7,
            ledger = ledger(t0 - 1000 to 7, t0 - 2000 to 7, t0 - 3000 to 7),
            apkStamp = 0L, lastStamp = 0L, hotReload = false,
        )
        assertFalse(d.block)
        assertEquals(1, CrashGuardPolicy.parse(d.ledger, t0).size)
    }

    @Test
    fun entriesOutsideWindowAreDropped() {
        val old = t0 - CrashGuardPolicy.WINDOW_MS - 1
        val d = CrashGuardPolicy.decide(
            now = t0, pid = 9,
            ledger = ledger(old to 1, old to 2, old to 3),
            apkStamp = 0L, lastStamp = 0L, hotReload = false,
        )
        assertFalse(d.block)
        assertEquals(1, CrashGuardPolicy.parse(d.ledger, t0).size)
    }

    @Test
    fun malformedLedgerEntriesAreIgnored() {
        val parsed = CrashGuardPolicy.parse("garbage;;abc,def;${t0 - 100},5;,;123", t0)
        assertEquals(1, parsed.size)
        assertEquals(t0 - 100, parsed[5])
    }

    // ---------- 热重载豁免 ----------

    @Test
    fun hotReloadNeverCountsAndKeepsLedger() {
        val existing = ledger(t0 - 1000 to 1, t0 - 2000 to 2, t0 - 3000 to 3)
        val d = CrashGuardPolicy.decide(
            now = t0, pid = 4, ledger = existing,
            apkStamp = 0L, lastStamp = 0L, hotReload = true,
        )
        assertFalse(d.block)
        assertFalse(d.forceMasterOff)
        assertEquals("热重载不得改动账本", existing, d.ledger)
    }

    /** 阳性对照：同样入参去掉 hotReload 就会被拦 ⇒ 豁免真的来自热重载而非其它条件。 */
    @Test
    fun positiveControlSameInputWithoutHotReloadBlocks() {
        val existing = ledger(t0 - 1000 to 1, t0 - 2000 to 2, t0 - 3000 to 3)
        val hot = CrashGuardPolicy.decide(t0, 4, existing, 0L, 0L, hotReload = true)
        val cold = CrashGuardPolicy.decide(t0, 4, existing, 0L, 0L, hotReload = false)
        assertFalse(hot.block)
        assertTrue(cold.block)
    }

    // ---------- 装机宽限 ----------

    @Test
    fun freshInstallWithinGraceIsNotCounted() {
        val apk = t0 - 30_000
        val d = CrashGuardPolicy.decide(
            now = t0, pid = 4,
            ledger = ledger(t0 - 1000 to 1, t0 - 2000 to 2, t0 - 3000 to 3),
            apkStamp = apk, lastStamp = 0L, hotReload = false,
        )
        assertFalse("装机宽限内不得拦", d.block)
        assertFalse(d.forceMasterOff)
        assertEquals("宽限内账本清空重新起算", "", d.ledger)
        assertEquals(apk, d.apkStamp)
    }

    /** 连续装机调试：每次都换新包，四个 pid 也不该攒满。 */
    @Test
    fun repeatedInstallsEachResetTheLedger() {
        var ledgerStr = ""
        var last = 0L
        for (i in 1..6) {
            val now = t0 + i * 20_000L
            val apk = now - 5_000
            val d = CrashGuardPolicy.decide(now, i, ledgerStr, apk, last, hotReload = false)
            assertFalse("第 $i 次装机不该被拦", d.block)
            ledgerStr = d.ledger
            last = d.apkStamp
        }
    }

    /** 边界：恰好等于宽限长度即出窗（不再豁免）。 */
    @Test
    fun graceBoundaryIsExclusive() {
        val apk = t0 - CrashGuardPolicy.INSTALL_GRACE_MS
        val d = CrashGuardPolicy.decide(
            now = t0, pid = 4,
            ledger = ledger(t0 - 1000 to 1, t0 - 2000 to 2, t0 - 3000 to 3),
            apkStamp = apk, lastStamp = apk, hotReload = false,
        )
        assertTrue("恰好到点必须出宽限", d.block)
    }

    /** 边界：宽限内最后一毫秒仍豁免。 */
    @Test
    fun lastMillisecondInsideGraceStillExempt() {
        val apk = t0 - CrashGuardPolicy.INSTALL_GRACE_MS + 1
        val d = CrashGuardPolicy.decide(
            now = t0, pid = 4,
            ledger = ledger(t0 - 1000 to 1, t0 - 2000 to 2, t0 - 3000 to 3),
            apkStamp = apk, lastStamp = apk, hotReload = false,
        )
        assertFalse(d.block)
    }

    /** APK 戳在未来（时钟回拨）不得当成宽限，避免永久豁免。 */
    @Test
    fun futureApkStampDoesNotGrantGrace() {
        val d = CrashGuardPolicy.decide(
            now = t0, pid = 4,
            ledger = ledger(t0 - 1000 to 1, t0 - 2000 to 2, t0 - 3000 to 3),
            apkStamp = t0 + 60_000, lastStamp = t0 + 60_000, hotReload = false,
        )
        assertTrue("未来戳不得豁免", d.block)
    }

    /** 换了新包但已超出宽限窗：清旧账本重新起算，本次只算 1 个。 */
    @Test
    fun newApkOutsideGraceResetsButStillCounts() {
        val apk = t0 - CrashGuardPolicy.INSTALL_GRACE_MS - 5_000
        val d = CrashGuardPolicy.decide(
            now = t0, pid = 4,
            ledger = ledger(t0 - 1000 to 1, t0 - 2000 to 2, t0 - 3000 to 3),
            apkStamp = apk, lastStamp = apk - 100_000, hotReload = false,
        )
        assertFalse(d.block)
        assertEquals(1, CrashGuardPolicy.parse(d.ledger, t0).size)
    }

    /** 取不到 APK 戳时不得清空既有账本（fail-open 不等于失忆）。 */
    @Test
    fun unavailableApkStampKeepsExistingLedger() {
        val d = CrashGuardPolicy.decide(
            now = t0, pid = 3,
            ledger = ledger(t0 - 1000 to 1, t0 - 2000 to 2),
            apkStamp = 0L, lastStamp = 555L, hotReload = false,
        )
        assertFalse(d.block)
        assertEquals(3, CrashGuardPolicy.parse(d.ledger, t0).size)
    }

    @Test
    fun ledgerRoundTripsThroughParseAndFormat() {
        val src = ledger(t0 - 1000 to 1, t0 - 2000 to 2, t0 - 3000 to 3)
        val parsed = CrashGuardPolicy.parse(src, t0)
        val again = CrashGuardPolicy.parse(CrashGuardPolicy.format(parsed), t0)
        assertEquals(parsed, again)
    }
}