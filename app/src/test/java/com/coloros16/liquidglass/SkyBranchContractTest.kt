package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.HandPaintSource
import com.coloros16.liquidglass.engine.SceneContract
import com.coloros16.liquidglass.engine.SceneSample
import com.coloros16.liquidglass.engine.SceneSkyMath
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.SceneTimeline
import com.coloros16.liquidglass.engine.SkyBranchPolicy
import com.coloros16.liquidglass.engine.SkyBranchPolicy.Branch
import com.coloros16.liquidglass.engine.TransitionEdge
import com.coloros16.liquidglass.ui.aod.AodSceneCatalog
import com.coloros16.liquidglass.ui.aod.AodSceneSpec
import com.coloros16.liquidglass.ui.aod.AodStyleCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkyBranchContractTest {

    @Test
    fun lensWinsOverPhotoAndSky() {
        assertEquals(Branch.LENS, SkyBranchPolicy.branchOf(true, true, true))
    }

    @Test
    fun photoWinsOverSky() {
        assertEquals(Branch.PHOTO, SkyBranchPolicy.branchOf(false, true, true))
    }

    @Test
    fun skyOnlyWhenNeitherLensNorPhoto() {
        assertEquals(Branch.SKY, SkyBranchPolicy.branchOf(false, false, true))
    }

    @Test
    fun noFlagsMeansNoWorld() {
        assertEquals(Branch.NONE, SkyBranchPolicy.branchOf(false, false, false))
    }

    @Test
    fun oldTwoBranchLogicWouldSendSkySpecToPhoto() {
        val skyOnly = SkyBranchPolicy.branchOf(false, false, true)
        val oldElseIsPhoto = Branch.PHOTO
        assertNotEquals(oldElseIsPhoto, skyOnly)
    }

    @Test
    fun existingCatalogSpecsNeverReachSkyBranch() {
        for (id in AodStyleCatalog.allIds()) {
            val spec = AodSceneCatalog.forStyle(id) ?: continue
            assertFalse("style=$id 不应声明天空", spec.sky)
            assertNotEquals(Branch.SKY, SkyBranchPolicy.branchOf(spec.lens, spec.photo != null, spec.sky))
        }
    }

    @Test
    fun nonSceneStylesHaveNoSpecAtAll() {
        assertNull(AodSceneCatalog.forStyle(AodStyleCatalog.ID_FULLSCREEN))
        assertNull(AodSceneCatalog.forStyle(AodStyleCatalog.ID_TEXT))
        assertNull(AodSceneCatalog.forStyle(AodStyleCatalog.ID_PORTRAIT))
    }

    @Test
    fun handPaintStyleNowHasWorldSpec() {
        val spec = AodSceneCatalog.forStyle(AodStyleCatalog.ID_HANDPAINT)
        assertTrue("手绘应有世界契约", spec != null)
        assertEquals(HandPaintSource.DEFAULT_STYLE, spec!!.handPaintStyle)
        assertFalse(spec.lens)
        assertNull(spec.photo)
        assertEquals(
            Branch.HANDPAINT,
            SkyBranchPolicy.branchOf(spec.lens, spec.photo != null, spec.sky, spec.handPaintStyle),
        )
    }

    @Test
    fun skyFlagDefaultsToFalseOnSpec() {
        val spec = AodSceneSpec(SceneContract("test"), emptyMap())
        assertFalse(spec.sky)
    }

    @Test
    fun handPaintStyleDefaultsToZeroOnSpec() {
        val spec = AodSceneSpec(SceneContract("test"), emptyMap())
        assertEquals(0, spec.handPaintStyle)
        assertEquals(Branch.NONE, SkyBranchPolicy.branchOf(false, false, false, spec.handPaintStyle))
    }

    @Test
    fun handPaintBranchOnlyWhenStylePositive() {
        assertEquals(Branch.HANDPAINT, SkyBranchPolicy.branchOf(false, false, false, 1))
        assertEquals(Branch.NONE, SkyBranchPolicy.branchOf(false, false, false, 0))
        assertEquals(Branch.NONE, SkyBranchPolicy.branchOf(false, false, false, -3))
    }

    @Test
    fun handPaintWinsOverSkyButNotPhotoOrLens() {
        assertEquals(Branch.HANDPAINT, SkyBranchPolicy.branchOf(false, false, true, 5))
        assertEquals(Branch.PHOTO, SkyBranchPolicy.branchOf(false, true, true, 5))
        assertEquals(Branch.LENS, SkyBranchPolicy.branchOf(true, true, true, 5))
    }

    @Test
    fun threeArgOverloadIgnoresHandPaint() {
        assertEquals(Branch.SKY, SkyBranchPolicy.branchOf(false, false, true))
        assertEquals(Branch.NONE, SkyBranchPolicy.branchOf(false, false, false))
    }

    @Test
    fun onlyHandPaintStyleReachesHandPaintBranch() {
        for (id in AodStyleCatalog.allIds()) {
            val spec = AodSceneCatalog.forStyle(id) ?: continue
            val branch = SkyBranchPolicy.branchOf(spec.lens, spec.photo != null, spec.sky, spec.handPaintStyle)
            if (id == AodStyleCatalog.ID_HANDPAINT) {
                assertEquals(Branch.HANDPAINT, branch)
            } else {
                assertNotEquals("style=$id 不该走手绘分支", Branch.HANDPAINT, branch)
                assertEquals("style=$id 不该声明手绘款号", 0, spec.handPaintStyle)
            }
        }
    }

    @Test
    fun moonNeedsPose() {
        assertFalse(SkyBranchPolicy.moonDrawable(false, 1f, 0.002f))
        assertTrue(SkyBranchPolicy.moonDrawable(true, 1f, 0.002f))
    }

    @Test
    fun moonHiddenBelowLayerAlphaThreshold() {
        assertFalse(SkyBranchPolicy.moonDrawable(true, 0.002f, 0.002f))
        assertFalse(SkyBranchPolicy.moonDrawable(true, 0f, 0.002f))
        assertTrue(SkyBranchPolicy.moonDrawable(true, 0.003f, 0.002f))
    }

    @Test
    fun emptyContractYieldsNoMoonPose() {
        val sample = SceneSample()
        sample.sample(SceneContract("aod.scene.sail"), 0f)
        assertNull(sample.elements.getOrNull(0))
        assertFalse(SkyBranchPolicy.moonDrawable(sample.elements.getOrNull(0) != null, 1f, 0.002f))
    }

    @Test
    fun emptyContractPinsCameraAndLight() {
        val sample = SceneSample()
        sample.sample(SceneContract("x"), 0.7f)
        assertEquals(0.5f, sample.cameraX, 0f)
        assertEquals(0.5f, sample.cameraY, 0f)
        assertEquals(1f, sample.light, 0f)
    }

    @Test
    fun floorOnlyKicksInAboveLightOne() {
        assertEquals(1f - SceneSkyMath.STAR_LIGHT_K, SceneSkyMath.starAlphaBase(1f), 0.0001f)
        assertTrue(SceneSkyMath.starAlphaBase(1f) < 1f)
        assertTrue(SceneSkyMath.starAlphaBase(1.05f) > SceneSkyMath.STAR_LIGHT_FLOOR)
        assertEquals(SceneSkyMath.STAR_LIGHT_FLOOR, SceneSkyMath.starAlphaBase(2f), 0.0001f)
        assertEquals(SceneSkyMath.MILKYWAY_LIGHT_FLOOR * SceneSkyMath.MILKYWAY_ALPHA, SceneSkyMath.milkyWayAlpha(2f), 0.0001f)
    }

    @Test
    fun halfAlphaTruncatesTo127Not128() {
        val c = SceneSkyMath.withAlpha(0xFF112233.toInt(), 0.5f)
        assertEquals(0x7F, (c ushr 24) and 0xFF)
        assertNotEquals(0x80, (c ushr 24) and 0xFF)
    }

    @Test
    fun starsAndMoonMarksShareOneRandomSequence() {
        val (stars, marks) = SceneSkyMath.fields()
        assertEquals(SceneSkyMath.STAR_COUNT * SceneSkyMath.STRIDE, stars.size)
        assertEquals(SceneSkyMath.MOON_MARK_COUNT * SceneSkyMath.STRIDE, marks.size)
        val again = SceneSkyMath.fields()
        assertArrayEqualsFloat(stars, again.first)
        assertArrayEqualsFloat(marks, again.second)
        val freshSeedMarks = FloatArray(marks.size)
        val rnd = java.util.Random(SceneSkyMath.SEED)
        var i = 0
        while (i < freshSeedMarks.size) {
            val angle = rnd.nextFloat() * 6.2831855f
            val dist = 0.10f + rnd.nextFloat() * 0.52f
            freshSeedMarks[i] = (kotlin.math.cos(angle) * dist).coerceIn(-0.82f, 0.82f)
            freshSeedMarks[i + 1] = (kotlin.math.sin(angle) * dist).coerceIn(-0.82f, 0.82f)
            freshSeedMarks[i + 2] = 0.018f + rnd.nextFloat() * 0.062f
            freshSeedMarks[i + 3] = 0.06f + rnd.nextFloat() * 0.10f
            i += SceneSkyMath.STRIDE
        }
        assertNotEquals(marks[0], freshSeedMarks[0])
    }

    @Test
    fun sceneUAtStaticAodIsZero() {
        assertEquals(0f, SceneTimeline.uOf(null, 0f, SceneState.AOD), 0.0001f)
        assertEquals(0.5f, SceneTimeline.uOf(null, 0f, SceneState.LOCK), 0.0001f)
        assertEquals(1f, SceneTimeline.uOf(null, 0f, SceneState.HOME), 0.0001f)
    }

    @Test
    fun sceneUMidTransitionIsBetweenEndpoints() {
        val u = SceneTimeline.uOf(TransitionEdge.AOD_TO_LOCK, 0.5f, SceneState.AOD)
        assertTrue("u=$u", u > 0f && u < 0.5f)
    }

    private fun assertArrayEqualsFloat(a: FloatArray, b: FloatArray) {
        assertEquals(a.size, b.size)
        for (i in a.indices) assertEquals(a[i], b[i], 0f)
    }
}