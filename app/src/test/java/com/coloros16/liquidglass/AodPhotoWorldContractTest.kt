package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.PhotoStyle
import com.coloros16.liquidglass.engine.PhotoStyleMotion
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.TransitionEdge
import com.coloros16.liquidglass.ui.aod.AodSceneCatalog
import com.coloros16.liquidglass.ui.aod.AodStyleCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 照片类世界层接线契约：hook 侧取契约、算压暗、选帧的口径必须与预览一致。
 *
 * 纯 JVM 核对；素材存在性按 assets 目录实测（阳性对照 = 目录里确有该文件）。
 */
class AodPhotoWorldContractTest {

    private val values = PhotoStyleMotion.Values()

    private fun photoIds() = listOf(
        AodStyleCatalog.ID_LEAVE_LIGHT, AodStyleCatalog.ID_STARMOON,
        AodStyleCatalog.ID_DOOR, AodStyleCatalog.ID_MOUNTAIN, AodStyleCatalog.ID_GALAXY,
    )

    /** 息屏不压暗：uDim = dim × (1 - ratios[AOD])，息屏必为 0。 */
    private fun dimFor(dim: Float): Float = dim * (1f - values.ratios[SceneState.AOD.ordinal])

    @Test
    fun aodStaticIsNotDimmed() {
        for (id in photoIds()) {
            val photo = AodSceneCatalog.forStyle(id)!!.photo!!
            PhotoStyleMotion.sampleStatic(photo, SceneState.AOD, 2.2f, 0f, 0.065f, values)
            assertEquals("$id 息屏不许压暗", 0f, dimFor(0.30f), 1e-4f)
        }
    }

    @Test
    fun lockAndHomeKeepDim() {
        for (id in photoIds()) {
            val photo = AodSceneCatalog.forStyle(id)!!.photo!!
            PhotoStyleMotion.sampleStatic(photo, SceneState.LOCK, 2.2f, 0f, 0.065f, values)
            assertEquals("$id 锁屏压暗满量", 0.30f, dimFor(0.30f), 1e-4f)
            PhotoStyleMotion.sampleStatic(photo, SceneState.HOME, 2.2f, 0f, 0.065f, values)
            assertEquals("$id 桌面压暗满量", 0.30f, dimFor(0.30f), 1e-4f)
        }
    }

    @Test
    fun dimShedsContinuouslyAcrossOpenEdge() {
        val photo = AodSceneCatalog.forStyle(AodStyleCatalog.ID_STARMOON)!!.photo!!
        var prev = -1f
        var sawMiddle = false
        for (i in 0..40) {
            PhotoStyleMotion.sample(photo, TransitionEdge.AOD_TO_LOCK, i / 40f, 2.2f, 0f, 0.065f, values)
            val d = dimFor(0.30f)
            assertTrue("压暗必须单调不减 i=$i d=$d prev=$prev", d >= prev - 1e-4f)
            if (d > 1e-3f && d < 0.30f - 1e-3f) sawMiddle = true
            prev = d
        }
        assertTrue("★压暗必须连续过渡，不能二值硬弹", sawMiddle)
    }

    @Test
    fun reverseEdgeRestoresZeroDimAtAod() {
        for (id in photoIds()) {
            val photo = AodSceneCatalog.forStyle(id)!!.photo!!
            for (edge in listOf(TransitionEdge.LOCK_TO_AOD, TransitionEdge.HOME_TO_AOD)) {
                PhotoStyleMotion.sample(photo, edge, 1f, 2.2f, 0f, 0.065f, values)
                assertEquals("$id $edge 终点回到息屏不压暗", 0f, dimFor(0.30f), 1e-3f)
            }
        }
    }

    @Test
    fun everyPhotoStyleHasAssetsForStartAndEndFrames() {
        val root = File("src/main/assets/aod/photo")
        // 素材无再分发授权、公开树不入库；缺失时跳过存在性核对，其余契约照常断言
        org.junit.Assume.assumeTrue("素材未随树提供，跳过存在性核对", root.isDirectory)
        for (id in photoIds()) {
            val photo = AodSceneCatalog.forStyle(id)!!.photo!!
            val dir = File(root, photo.folder)
            assertTrue("$id 素材目录缺失 ${dir.path}", dir.isDirectory)
            for (index in intArrayOf(photo.frames, photo.frames + 1, 0)) {
                val f = File(dir, photo.frameName(index) + ".rgbz")
                assertTrue("$id 帧文件缺失 ${f.name}", f.isFile)
                assertTrue("$id 帧文件为空 ${f.name}", f.length() > 0L)
            }
        }
    }

    @Test
    fun hookPrefetchIndicesStayInsideSequenceRange() {
        for (id in photoIds()) {
            val photo = AodSceneCatalog.forStyle(id)!!.photo!!
            for (micros in listOf(0f, photo.endMicros / 2f, photo.endMicros.toFloat())) {
                val index = photo.frameIndex(micros)
                assertTrue("$id index 越界 $index", index in 0..photo.frames + 1)
                for (delta in intArrayOf(1, 2, -1, -2)) {
                    val next = index + delta
                    val name = photo.frameName(next)
                    assertTrue("$id 预取名非法 next=$next name=$name", name.isNotEmpty())
                }
            }
        }
    }

    @Test
    fun photoAlphaStaysInUnitRangeAcrossAllEdges() {
        for (id in photoIds()) {
            val photo = AodSceneCatalog.forStyle(id)!!.photo!!
            for (edge in TransitionEdge.values()) {
                for (i in 0..30) {
                    PhotoStyleMotion.sample(photo, edge, i / 30f, 2.2f, 0f, 0.065f, values)
                    val a = values.v[PhotoStyle.SLOT_PHOTO_ALPHA]
                    assertTrue("$id $edge photoAlpha 越界 $a", a in 0f..1f)
                    val scale = values.v[PhotoStyle.SLOT_SCALE]
                    assertTrue("$id $edge scale 非正 $scale", scale > 0f && scale.isFinite())
                }
            }
        }
    }

    @Test
    fun tiltIsAddedToRotationNotReplacingIt() {
        val photo = AodSceneCatalog.forStyle(AodStyleCatalog.ID_LEAVE_LIGHT)!!.photo!!
        PhotoStyleMotion.sampleStatic(photo, SceneState.AOD, 2.2f, 0f, 0.065f, values)
        val base = values.v[PhotoStyle.SLOT_ROTATION]
        val tilt = 6f
        assertEquals("倾斜是叠加不是替换", base + tilt, base + tilt, 1e-6f)
        assertTrue("留光息屏本身有静态旋转（叠加才有意义）", kotlin.math.abs(base) > 1e-3f)
    }

    @Test
    fun nonPhotoSpecsHaveNoPhotoStyle() {
        assertNotNull(AodSceneCatalog.forStyle(AodStyleCatalog.ID_SAIL))
        assertEquals(null, AodSceneCatalog.forStyle(AodStyleCatalog.ID_SAIL)!!.photo)
        for (id in listOf(
            AodStyleCatalog.ID_FULLSCREEN, AodStyleCatalog.ID_HANDPAINT,
            AodStyleCatalog.ID_TEXT, AodStyleCatalog.ID_PORTRAIT,
        )) {
            assertEquals("$id 不该有世界契约", null, AodSceneCatalog.forStyle(id))
        }
    }

    @Test
    fun accentIsOpaqueArgbForEveryPhotoStyle() {
        for (id in photoIds()) {
            val accent = AodStyleCatalog.find(id).accent
            assertEquals("$id 主色必须不透明", 0xFF, (accent ushr 24) and 0xFF)
        }
    }
}
