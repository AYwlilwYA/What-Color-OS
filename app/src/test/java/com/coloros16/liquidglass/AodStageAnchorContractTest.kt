package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.AodStageAnchors
import com.coloros16.liquidglass.engine.SceneState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AodStageAnchorContractTest {

    private val roms = listOf(
        "reference/pdem30/decompiled/systemui/sources",
        "reference/dc_sysui_160014/sources",
    )

    private fun root(): File {
        var dir = File(System.getProperty("user.dir"))
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        return dir
    }

    private fun romSource(rel: String): File? {
        val base = root()
        for (r in roms) {
            val f = File(base, "$r/$rel")
            if (f.isFile) return f
        }
        return null
    }

    private fun bothRomsHave(rel: String): Boolean {
        val base = root()
        return roms.all { File(base, "$it/$rel").isFile }
    }

    @Test
    fun aodRootClassesExistInBothRoms() {
        assertTrue(bothRomsHave("com/oplus/systemui/aod/aodclock/off/AodRootLayout.java"))
        assertTrue(bothRomsHave("com/oplus/systemui/aod/aodclock/off/BaseRootLayout.java"))
    }

    @Test
    fun shadeRootClassExistsInBothRoms() {
        assertTrue(bothRomsHave("com/android/systemui/shade/NotificationShadeWindowView.java"))
    }

    @Test
    fun clockClassesExistInBothRoms() {
        assertTrue(bothRomsHave("com/oplus/systemui/aod/aodclock/off/AodClockLayout.java"))
        assertTrue(bothRomsHave("com/oplus/keyguard/OplusKeyguardStyleClock.java"))
    }

    @Test
    fun secureInputClassesExistInBothRoms() {
        val paths = listOf(
            "com/android/keyguard/KeyguardSecurityContainer.java",
            "com/android/keyguard/KeyguardPasswordView.java",
            "com/android/keyguard/KeyguardPinBasedInputView.java",
            "com/android/keyguard/KeyguardPatternView.java",
            "com/oplus/systemui/keyguard/tips/view/FingerprintTipsView.java",
        )
        assertEquals(AodStageAnchors.SECURE_SUFFIXES.size, paths.size)
        for (p in paths) assertTrue(p, bothRomsHave(p))
    }

    @Test
    fun declaredClassConstantsMatchRomPackageAndName() {
        for (fqcn in AodStageAnchors.CLASSES) {
            val rel = fqcn.replace('.', '/') + ".java"
            val f = romSource(rel) ?: error("missing $rel")
            val lines = f.readText().lineSequence().take(200).toList()
            val pkg = fqcn.substringBeforeLast('.')
            val simple = fqcn.substringAfterLast('.')
            assertTrue("$fqcn package", lines.any { it.trim() == "package $pkg;" })
            assertTrue(
                "$fqcn class",
                lines.any { it.contains("class $simple ") || it.contains("class $simple\$") },
            )
        }
    }

    @Test
    fun everyIdNameResolvesInBothRomRFiles() {
        val base = root()
        for (name in AodStageAnchors.IDS) {
            for (r in roms) {
                val rf = File(base, "$r/com/android/systemui/R.java")
                assertTrue("$r R.java", rf.isFile)
                val hit = rf.useLines { lines ->
                    lines.any { it.contains("public static final int $name = 0x") }
                }
                assertTrue("$r id.$name", hit)
            }
        }
    }

    @Test
    fun idResourceValuesDifferAcrossRomsSoNameLookupIsRequired() {
        val base = root()
        val values = roms.map { r ->
            val rf = File(base, "$r/com/android/systemui/R.java")
            rf.useLines { lines ->
                lines.first { it.contains("public static final int ${AodStageAnchors.ID_AOD_ROOT} = 0x") }
                    .substringAfter("= ").trim().removeSuffix(";")
            }
        }
        assertTrue("两代 id 数值应不同，故必须按名解析", values[0] != values[1])
    }

    @Test
    fun aodRootRecognizedByExactName() {
        assertTrue(AodStageAnchors.isAodRoot(AodStageAnchors.CLASS_AOD_ROOT))
        assertTrue(AodStageAnchors.isAodRoot(AodStageAnchors.CLASS_AOD_BASE))
        assertFalse(AodStageAnchors.isAodRoot(AodStageAnchors.CLASS_SHADE_ROOT))
    }

    @Test
    fun aodRootMatchIsExactNotSuffix() {
        assertFalse(AodStageAnchors.isAodRoot("com.other.pkg.AodRootLayout"))
        assertFalse(AodStageAnchors.isAodRoot("AodRootLayout"))
        assertFalse(AodStageAnchors.isAodRoot(AodStageAnchors.CLASS_AOD_ROOT + "Sub"))
    }

    @Test
    fun surfaceMappingCoversEveryStageClass() {
        assertEquals(SceneState.AOD, AodStageAnchors.surfaceOfStage(AodStageAnchors.CLASS_AOD_ROOT))
        assertEquals(SceneState.AOD, AodStageAnchors.surfaceOfStage(AodStageAnchors.CLASS_AOD_BASE))
        assertEquals(SceneState.LOCK, AodStageAnchors.surfaceOfStage(AodStageAnchors.CLASS_SHADE_ROOT))
        assertEquals(SceneState.LOCK, AodStageAnchors.surfaceOfStage("android.widget.FrameLayout"))
    }

    @Test
    fun secureSuffixMatchesRegardlessOfPackage() {
        for (s in AodStageAnchors.SECURE_SUFFIXES) {
            assertTrue(s, AodStageAnchors.isSecureInput("com.android.keyguard$s"))
            assertTrue(s, AodStageAnchors.isSecureInput("com.oplus.systemui.keyguard.tips.view$s"))
        }
    }

    @Test
    fun nonSecureClassesDoNotMatch() {
        assertFalse(AodStageAnchors.isSecureInput(AodStageAnchors.CLASS_SHADE_ROOT))
        assertFalse(AodStageAnchors.isSecureInput(AodStageAnchors.CLASS_AOD_ROOT))
        assertFalse(AodStageAnchors.isSecureInput("com.android.keyguard.KeyguardRootView"))
    }

    @Test
    fun secureSuffixListHasNoDuplicatesAndAllStartWithDot() {
        val list = AodStageAnchors.SECURE_SUFFIXES
        assertEquals(list.size, list.toSet().size)
        for (s in list) assertTrue(s, s.startsWith("."))
    }

    @Test
    fun idAndClassTablesAreCompleteAndUnique() {
        assertEquals(6, AodStageAnchors.IDS.size)
        assertEquals(AodStageAnchors.IDS.size, AodStageAnchors.IDS.toSet().size)
        assertEquals(3, AodStageAnchors.CLASSES.size)
        assertEquals(AodStageAnchors.CLASSES.size, AodStageAnchors.CLASSES.toSet().size)
        assertTrue(AodStageAnchors.IDS.contains(AodStageAnchors.ID_AOD_ROOT))
        assertTrue(AodStageAnchors.IDS.contains(AodStageAnchors.ID_LOCK_ROOT))
        assertTrue(AodStageAnchors.IDS.contains(AodStageAnchors.ID_LOCK_CLOCK))
    }

    @Test
    fun clockSuffixesMatchTheirRomSimpleNames() {
        assertTrue(AodStageAnchors.SUFFIX_AOD_CLOCK.startsWith("."))
        assertTrue(AodStageAnchors.SUFFIX_LOCK_CLOCK.startsWith("."))
        assertTrue(
            "com.oplus.systemui.aod.aodclock.off.AodClockLayout"
                .endsWith(AodStageAnchors.SUFFIX_AOD_CLOCK),
        )
        assertTrue(
            "com.oplus.keyguard.OplusKeyguardStyleClock"
                .endsWith(AodStageAnchors.SUFFIX_LOCK_CLOCK),
        )
    }
}