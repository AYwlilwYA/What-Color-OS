package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.CaptureRev
import com.coloros16.liquidglass.hook.MatchKind
import com.coloros16.liquidglass.hook.MinorPoint
import com.coloros16.liquidglass.hook.PosteffectArch
import com.coloros16.liquidglass.hook.Profiles
import com.coloros16.liquidglass.hook.TiltPath
import com.coloros16.liquidglass.hook.VersionPool
import com.coloros16.liquidglass.hook.VersionSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionPoolTest {

    private val launcherPkg = "com.android.launcher"
    private val galleryPkg = "com.coloros.gallery3d"
    private val qsbPkg = "com.heytap.quicksearchbox"

    private fun specOf(pkg: String): VersionSpec =
        VersionPool.specs.first { it.pkg == pkg }

    @Test
    fun registeredHostsCoverAllInjectedPackages() {
        val pkgs = VersionPool.specs.map { it.pkg }.toSet()
        val expected = setOf(
            "com.android.systemui",
            "com.android.launcher",
            "com.coloros.gallery3d",
            "com.coloros.calculator",
            "com.coloros.smartsidebar",
            "com.coloros.assistantscreen",
            "com.heytap.quicksearchbox",
        )
        assertEquals(expected, pkgs)
    }

    @Test
    fun exactHitOnKnownLauncherPoint() {
        val d = VersionPool.query(launcherPkg, 170030012L)
        assertEquals(MatchKind.Exact, d.match)
        assertEquals(17, d.major)
        assertEquals(170030012L, d.point)
        assertSame(Profiles.LAUNCHER_17PLUS, d.profile)
        assertFalse(d.profile.launcherAnimHook)
        assertSame(TiltPath.NATIVE_ZUYQA, d.profile.tiltPath)
    }

    @Test
    fun exactHitOnLegacyLauncherPoint() {
        val d = VersionPool.query(launcherPkg, 160060011L)
        assertEquals(MatchKind.Exact, d.match)
        assertEquals(16, d.major)
        assertSame(Profiles.LAUNCHER_PRE17, d.profile)
        assertTrue(d.profile.launcherAnimHook)
        assertTrue(d.profile.launcherMainHook)
        assertSame(TiltPath.LEGACY_16, d.profile.tiltPath)
    }

    @Test
    fun majorSegmentFirstBelowSegmentMinUsesSegmentProfile() {
        val d = VersionPool.query(launcherPkg, 170000000L)
        assertEquals(MatchKind.Segment, d.match)
        assertEquals(17, d.major)
        assertEquals(0L, d.point)
        assertSame(Profiles.LAUNCHER_17PLUS, d.profile)
    }

    @Test
    fun systemui160003StaysInMajor16() {
        val d = VersionPool.query(VersionPool.PKG_SYSTEMUI, 160003L)
        assertEquals(16, d.major)
        assertEquals(MatchKind.Segment, d.match)
        assertSame(Profiles.SYSTEMUI_16, d.profile)
        assertSame(PosteffectArch.LEGACY, d.profile.posteffectArch)
    }

    @Test
    fun systemuiEarlyC15StillFallsBackToC15Point() {
        val d = VersionPool.query(VersionPool.PKG_SYSTEMUI, 150000L)
        assertEquals(15, d.major)
        assertEquals(MatchKind.Nearest, d.match)
        assertEquals(159913L, d.point)
        assertSame(Profiles.SYSTEMUI_15, d.profile)
    }

    @Test
    fun aboveMaxPointClampsToMaxPoint() {
        val d = VersionPool.query(launcherPkg, 179999999L)
        assertEquals(MatchKind.Nearest, d.match)
        assertEquals(170030012L, d.point)
        assertSame(Profiles.LAUNCHER_17PLUS, d.profile)
    }

    @Test
    fun belowMinPointClampsToMinPoint() {
        val d = VersionPool.query(launcherPkg, 150000000L)
        assertEquals(MatchKind.Nearest, d.match)
        assertEquals(160040028L, d.point)
        assertSame(Profiles.LAUNCHER_PRE17, d.profile)
    }

    @Test
    fun verUnknownTakesMinPoint() {
        val d = VersionPool.query(launcherPkg, -1L)
        assertEquals(MatchKind.VerUnknown, d.match)
        assertEquals(160040028L, d.point)
        assertEquals(16, d.major)
        assertSame(Profiles.LAUNCHER_PRE17, d.profile)
    }

    @Test
    fun systemuiExactPointsResolvePerPoint() {
        val d16 = VersionPool.query("com.android.systemui", 169912L)
        assertEquals(MatchKind.Exact, d16.match)
        assertEquals(16, d16.major)
        assertSame(Profiles.SYSTEMUI_16_MODERN, d16.profile)

        val d17 = VersionPool.query("com.android.systemui", 179902L)
        assertEquals(MatchKind.Exact, d17.match)
        assertEquals(17, d17.major)
        assertSame(Profiles.SYSTEMUI_17, d17.profile)
    }

    @Test
    fun systemui160012IsLegacyArch() {
        val d = VersionPool.query(VersionPool.PKG_SYSTEMUI, 160012L)
        assertEquals(MatchKind.Exact, d.match)
        assertEquals(16, d.major)
        assertEquals(160012L, d.point)
        assertSame(Profiles.SYSTEMUI_16, d.profile)
        assertSame(PosteffectArch.LEGACY, d.profile.posteffectArch)
        assertEquals(CaptureRev.LEGACY, d.profile.captureRev)
    }

    @Test
    fun systemuiFloorAcrossPointGap() {
        val d = VersionPool.query("com.android.systemui", 165000L)
        assertEquals(MatchKind.Nearest, d.match)
        assertEquals(160014L, d.point)
        assertSame(Profiles.SYSTEMUI_16, d.profile)
    }

    @Test
    fun emptyPointTableWalksSegment() {
        val d = VersionPool.query(galleryPkg, 16040008L)
        assertEquals(MatchKind.Segment, d.match)
        assertEquals(16, d.major)
        assertEquals(0L, d.point)
        assertSame(Profiles.APPGLASS_16, d.profile)
    }

    @Test
    fun segmentMissFallsBackToDefaultProfile() {
        val d = VersionPool.query(qsbPkg, 1234567L)
        assertEquals(MatchKind.Segment, d.match)
        assertEquals(12, d.major)
        assertSame(Profiles.APPGLASS_DEFAULT, d.profile)
    }

    @Test
    fun quicksearchboxUsesOwnMajorDivisor() {
        val d = VersionPool.query(qsbPkg, 1162011L)
        assertEquals(MatchKind.Segment, d.match)
        assertEquals(11, d.major)
        assertSame(Profiles.APPGLASS_11, d.profile)
    }

    @Test
    fun emptyTableVerUnknownUsesDefaultProfile() {
        val d = VersionPool.query(galleryPkg, -1L)
        assertEquals(MatchKind.VerUnknown, d.match)
        assertEquals(0L, d.point)
        assertSame(Profiles.APPGLASS_DEFAULT, d.profile)
    }

    @Test
    fun posteffectArchPerSystemUiPoint() {
        val pre = VersionPool.query(VersionPool.PKG_SYSTEMUI, 159913L)
        assertEquals(MatchKind.Exact, pre.match)
        assertSame(PosteffectArch.PRE_AGSL, pre.profile.posteffectArch)
        for (ver in listOf(160009L, 160014L, 169906L)) {
            val d = VersionPool.query(VersionPool.PKG_SYSTEMUI, ver)
            assertEquals("ver=$ver", MatchKind.Exact, d.match)
            assertSame("ver=$ver", PosteffectArch.LEGACY, d.profile.posteffectArch)
        }
        for (ver in listOf(169912L, 179902L)) {
            val d = VersionPool.query(VersionPool.PKG_SYSTEMUI, ver)
            assertEquals("ver=$ver", MatchKind.Exact, d.match)
            assertSame("ver=$ver", PosteffectArch.MODERN, d.profile.posteffectArch)
        }
        assertSame(PosteffectArch.UNKNOWN, Profiles.LAUNCHER_PRE17.posteffectArch)
    }

    @Test
    fun captureRevPerHostPoint() {
        for (ver in listOf(159913L, 160009L, 160014L, 169906L, 169912L)) {
            val d = VersionPool.query(VersionPool.PKG_SYSTEMUI, ver)
            assertEquals("ver=$ver", CaptureRev.LEGACY, d.profile.captureRev)
        }
        assertEquals(
            CaptureRev.INTERNAL,
            VersionPool.query(VersionPool.PKG_SYSTEMUI, 179902L).profile.captureRev,
        )
        assertEquals(
            CaptureRev.LEGACY,
            VersionPool.query(launcherPkg, 160040028L).profile.captureRev,
        )
        assertEquals(
            CaptureRev.INTERNAL,
            VersionPool.query(launcherPkg, 170030012L).profile.captureRev,
        )
        assertEquals(
            CaptureRev.UNKNOWN,
            VersionPool.query(qsbPkg, 1162011L).profile.captureRev,
        )
    }

    @Test
    fun launcherOldPointCovers16428() {
        val d = VersionPool.query(launcherPkg, 160040028L)
        assertEquals(MatchKind.Exact, d.match)
        assertEquals(16, d.major)
        assertSame(Profiles.LAUNCHER_PRE17, d.profile)
    }

    @Test
    fun structureValidationAcceptsRegisteredSpecs() {
        for (spec in VersionPool.specs) {
            assertEquals("${spec.pkg} errors", emptyList<String>(), VersionPool.validate(spec))
        }
    }

    @Test
    fun structureValidationRejectsBrokenTables() {
        val unsorted = VersionSpec(
            pkg = "test.unsorted",
            majorDivisor = 10_000_000L,
            segmentProfiles = emptyMap(),
            defaultProfile = Profiles.LAUNCHER_PRE17,
            minorPoints = listOf(
                MinorPoint(200L, Profiles.LAUNCHER_PRE17),
                MinorPoint(100L, Profiles.LAUNCHER_PRE17),
            ),
        )
        assertTrue(VersionPool.validate(unsorted).isNotEmpty())

        val duplicated = unsorted.copy(
            pkg = "test.duplicated",
            minorPoints = listOf(
                MinorPoint(100L, Profiles.LAUNCHER_PRE17),
                MinorPoint(100L, Profiles.LAUNCHER_PRE17),
            ),
        )
        assertTrue(VersionPool.validate(duplicated).isNotEmpty())

        val badDivisor = VersionSpec(
            pkg = "test.baddivisor",
            majorDivisor = 0L,
            segmentProfiles = emptyMap(),
            defaultProfile = Profiles.LAUNCHER_PRE17,
            minorPoints = emptyList(),
        )
        assertTrue(VersionPool.validate(badDivisor).isNotEmpty())
    }

    @Test
    fun statusBarExactHitOnKnownPoint() {
        val d = VersionPool.statusBar(VersionPool.PKG_SYSTEMUI, 169912L)
        assertEquals(MatchKind.Exact, d.match)
        assertEquals(1, d.profile.layoutRev)
        assertTrue(d.profile.hasCompositeMobile)
        assertEquals("mobile", d.profile.mobileSlot)
    }

    @Test
    fun statusBarUnknownVersionFallsBackToFirstPoint() {
        val d = VersionPool.statusBar(VersionPool.PKG_SYSTEMUI, -1L)
        assertEquals(MatchKind.VerUnknown, d.match)
        assertEquals(VersionPool.statusBarPoints.first().profile, d.profile)
    }

    @Test
    fun statusBarNearestFloorsToLowerPoint() {
        val d = VersionPool.statusBar(VersionPool.PKG_SYSTEMUI, 169911L)
        assertEquals(MatchKind.Nearest, d.match)
        assertEquals(1, d.profile.layoutRev)
        val high = VersionPool.statusBar(VersionPool.PKG_SYSTEMUI, 200000L)
        assertEquals(MatchKind.Nearest, high.match)
        assertEquals(VersionPool.statusBarPoints.last().profile, high.profile)
    }

    @Test
    fun statusBarPointsAscendingAndCoverExtractedVersions() {
        val points = VersionPool.statusBarPoints
        assertEquals(6, points.size)
        for (i in 1 until points.size) {
            assertTrue(points[i - 1].versionCode < points[i].versionCode)
        }
        for (ver in listOf(159913L, 160009L, 160014L, 169906L, 169912L)) {
            val d = VersionPool.statusBar(VersionPool.PKG_SYSTEMUI, ver)
            assertEquals("ver=$ver", MatchKind.Exact, d.match)
            assertTrue(d.profile.hasFragmentEntry)
            assertTrue(d.profile.hasClockSeconds)
        }
    }

    @Test
    fun statusBarComposeEntryProfileDegradesFragmentAndClockSeconds() {
        val d = VersionPool.statusBar(VersionPool.PKG_SYSTEMUI, 179902L)
        assertEquals(MatchKind.Exact, d.match)
        assertEquals(1, d.profile.layoutRev)
        assertFalse(d.profile.hasFragmentEntry)
        assertFalse(d.profile.hasClockSeconds)
        assertTrue(d.profile.hasCompositeMobile)
        assertTrue(d.profile.hasCompositeWifi)
    }

    @Test
    fun runtimePatchDoesNotOverrideStaticMatch() {
        VersionPool.notePatch(
            VersionPool.PKG_SYSTEMUI,
            VersionPool.PATCH_LEGACY_POSTEFFECT_ARCH,
            true,
        )
        val d = VersionPool.query("com.android.systemui", 169912L)
        assertEquals(MatchKind.Exact, d.match)
        assertSame(Profiles.SYSTEMUI_16_MODERN, d.profile)
        assertEquals(true, d.patch[VersionPool.PATCH_LEGACY_POSTEFFECT_ARCH])

        val launcherPatch = VersionPool.query(launcherPkg, 170030012L).patch
        assertTrue(launcherPatch.isEmpty())
    }

    @Test
    fun unregisteredHostGetsSafeProfile() {
        val d = VersionPool.query("com.foo.unknown", 42L)
        assertSame(Profiles.UNREGISTERED, d.profile)
        assertTrue(d.profile.launcherMainHook)
        assertTrue(d.profile.launcherAnimHook)
    }
}
