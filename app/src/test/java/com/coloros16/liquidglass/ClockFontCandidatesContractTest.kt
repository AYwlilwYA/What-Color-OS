package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.ClockFontCandidate
import com.coloros16.liquidglass.engine.ClockFontCandidates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 时钟字型设备候选契约：厂商原件不随包分发，候选顺序与形态必须稳定。 */
class ClockFontCandidatesContractTest {

    @Test
    fun apkCandidatesComeBeforeDeviceFiles() {
        val all = ClockFontCandidates.all()
        val firstFile = all.indexOfFirst { !it.fromApk }
        val lastApk = all.indexOfLast { it.fromApk }
        assertTrue("必须存在两类候选", firstFile >= 0 && lastApk >= 0)
        assertTrue("APK 候选必须全部排在设备字库之前", lastApk < firstFile)
    }

    @Test
    fun clockAssetOrderIsMonospaceFirst() {
        assertEquals(
            listOf("assets/OPlusSans2.0_ClockGX.ttf", "assets/OPlusSans2.0_ClockPGX.ttf"),
            ClockFontCandidates.CLOCK_ASSETS,
        )
    }

    @Test
    fun everyApkDirIsPairedWithEveryClockAsset() {
        val apkOnes = ClockFontCandidates.all().filter { it.fromApk }
        val dirs = apkOnes.map { it.apkPath }.distinct()
        assertEquals(dirs.size * ClockFontCandidates.CLOCK_ASSETS.size, apkOnes.size)
        for (dir in dirs) {
            val assets = apkOnes.filter { it.apkPath == dir }.map { it.path }
            assertEquals(ClockFontCandidates.CLOCK_ASSETS, assets)
        }
    }

    @Test
    fun allPathsAreAbsoluteAndNonBlank() {
        for (c in ClockFontCandidates.all()) {
            assertTrue("候选必须可用: ${c.path}", ClockFontCandidates.usable(c))
            if (c.fromApk) {
                assertTrue("APK 内 asset 是相对路径: ${c.path}", c.path.startsWith("assets/"))
                assertTrue("APK 路径必须绝对: ${c.apkPath}", c.apkPath!!.startsWith("/"))
            } else {
                assertTrue("设备字库路径必须绝对: ${c.path}", c.path.startsWith("/"))
            }
        }
    }

    @Test
    fun deviceFileCandidatesCarryNoApk() {
        val files = ClockFontCandidates.all().filter { !it.fromApk }
        assertTrue(files.isNotEmpty())
        for (c in files) assertEquals(null, c.apkPath)
    }

    @Test
    fun usableRejectsBlankAndDirectoryLikePaths() {
        assertFalse(ClockFontCandidates.usable(ClockFontCandidate(null, "")))
        assertFalse(ClockFontCandidates.usable(ClockFontCandidate(null, "   ")))
        assertFalse(ClockFontCandidates.usable(ClockFontCandidate(null, "/system/fonts/")))
        assertFalse(ClockFontCandidates.usable(ClockFontCandidate("", "assets/a.ttf")))
    }

    /** 阳性对照：包内不再保留厂商原件路径，候选表也不许指向模块 assets。 */
    @Test
    fun noCandidatePointsAtModuleAssets() {
        for (c in ClockFontCandidates.all()) {
            assertFalse("不得回退到包内 assets: ${c.path}", c.path.startsWith("aod/"))
            assertFalse("不得指向模块 asset 前缀: ${c.path}", c.path.contains("clock_thin.ttf"))
            assertFalse("不得指向模块 asset 前缀: ${c.path}", c.path.contains("clock_prop.ttf"))
        }
    }

    @Test
    fun aodPackageListIsNonEmpty() {
        assertTrue(ClockFontCandidates.AOD_PACKAGES.isNotEmpty())
        assertTrue(ClockFontCandidates.AOD_PACKAGES.all { it.contains('.') })
    }

    /** 阳性对照：设备细体候选与 APK 候选路径集合不相交（两条独立退化路径）。 */
    @Test
    fun apkAndFilePathsDoNotOverlap() {
        val all = ClockFontCandidates.all()
        val apkPaths = all.filter { it.fromApk }.map { it.path }.toSet()
        val filePaths = all.filter { !it.fromApk }.map { it.path }.toSet()
        assertTrue(apkPaths.intersect(filePaths).isEmpty())
    }
}