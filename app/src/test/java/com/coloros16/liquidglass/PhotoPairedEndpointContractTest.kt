package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.PhotoStyle
import com.coloros16.liquidglass.engine.PhotoStyleMotion
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.TransitionEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 双图配对的端点契约：配对生效时锁屏与桌面的照片不透明度同为 0。
 *
 * 参考实现里配对照片在锁屏与桌面是同一个整屏端点，
 * 若锁屏仍取 1，锁屏→桌面那条边会把照片再淡出一遍（第二次揭开）。
 */
class PhotoPairedEndpointContractTest {

    private val aspect = 2400f / 1080f

    private fun style(id: Int): PhotoStyle = PhotoStyleMotion.byId(id) ?: error("取不到样式 $id")

    private fun alphaOf(id: Int, state: SceneState, paired: Boolean): Float {
        val out = PhotoStyleMotion.Values()
        PhotoStyleMotion.sampleStatic(style(id), state, aspect, 0f, 0.3f, out, paired)
        return out.v[PhotoStyle.SLOT_PHOTO_ALPHA]
    }

    /** 只有留光款支持配对，其余四款开关开也不配对。 */
    @Test
    fun onlyLeaveLightSupportsPairing() {
        assertTrue(PhotoStyleMotion.pairedEnabled(1, true))
        for (id in 2..5) assertFalse("样式 $id 不该支持配对", PhotoStyleMotion.pairedEnabled(id, true))
        for (id in 1..5) assertFalse("开关关时都不配对", PhotoStyleMotion.pairedEnabled(id, false))
    }

    /** 第二张照片没解出时配对不生效，端点必须回落未配对。 */
    @Test
    fun pairingNeedsSecondPhotoDecoded() {
        assertTrue(PhotoStyleMotion.pairedActive(1, true, true))
        assertFalse("照片未解出", PhotoStyleMotion.pairedActive(1, true, false))
        assertFalse("款式不支持", PhotoStyleMotion.pairedActive(3, true, true))
        assertFalse("开关关", PhotoStyleMotion.pairedActive(1, false, true))
    }

    /** 三态端点真值：息屏恒 1、桌面恒 0、锁屏随配对。 */
    @Test
    fun staticPhotoAlphaTruthTable() {
        for (paired in listOf(false, true)) {
            assertEquals(1f, PhotoStyleMotion.photoAlphaOf(SceneState.AOD, paired), 1e-6f)
            assertEquals(0f, PhotoStyleMotion.photoAlphaOf(SceneState.HOME, paired), 1e-6f)
        }
        assertEquals(1f, PhotoStyleMotion.photoAlphaOf(SceneState.LOCK, false), 1e-6f)
        assertEquals(0f, PhotoStyleMotion.photoAlphaOf(SceneState.LOCK, true), 1e-6f)
    }

    /** 端点表与 sampleStatic 逐位一致（五款 × 三态 × 两配对态 30 组）。 */
    @Test
    fun sampleStaticMatchesEndpointTable() {
        for (id in 1..5) {
            for (state in SceneState.entries) {
                for (paired in listOf(false, true)) {
                    assertEquals(
                        "样式 $id 态 $state paired=$paired",
                        PhotoStyleMotion.photoAlphaOf(state, paired),
                        alphaOf(id, state, paired),
                        1e-6f,
                    )
                }
            }
        }
    }

    /** ★核心：配对生效时锁屏→桌面两端同为 0，整条边照片不透明度恒 0，不再淡第二遍。 */
    @Test
    fun pairedUnlockEdgeKeepsPhotoAlphaZeroThroughout() {
        val s = style(1)
        val out = PhotoStyleMotion.Values()
        for (step in 0..40) {
            val t = step / 40f
            PhotoStyleMotion.sample(s, TransitionEdge.LOCK_TO_HOME, t, aspect, 0f, 0f, out, paired = true)
            assertEquals("配对解锁边 t=$t 照片应恒 0", 0f, out.v[PhotoStyle.SLOT_PHOTO_ALPHA], 1e-6f)
        }
        for (step in 0..40) {
            val t = step / 40f
            PhotoStyleMotion.sample(s, TransitionEdge.HOME_TO_LOCK, t, aspect, 0f, 0f, out, paired = true)
            assertEquals("配对回锁屏边 t=$t 照片应恒 0", 0f, out.v[PhotoStyle.SLOT_PHOTO_ALPHA], 1e-6f)
        }
    }

    /** 阳性对照：未配对时同一条边确实从 1 淡到 0（缺陷可复现）。 */
    @Test
    fun positiveControlUnpairedUnlockEdgeFadesFromOne() {
        val s = style(1)
        val out = PhotoStyleMotion.Values()
        PhotoStyleMotion.sample(s, TransitionEdge.LOCK_TO_HOME, 0f, aspect, 0f, 0f, out, paired = false)
        assertEquals("未配对起点应为 1", 1f, out.v[PhotoStyle.SLOT_PHOTO_ALPHA], 1e-6f)
        PhotoStyleMotion.sample(s, TransitionEdge.LOCK_TO_HOME, 1f, aspect, 0f, 0f, out, paired = false)
        assertEquals("未配对终点应为 0", 0f, out.v[PhotoStyle.SLOT_PHOTO_ALPHA], 1e-6f)
        var mid = 0
        for (step in 1..39) {
            PhotoStyleMotion.sample(s, TransitionEdge.LOCK_TO_HOME, step / 40f, aspect, 0f, 0f, out, paired = false)
            val a = out.v[PhotoStyle.SLOT_PHOTO_ALPHA]
            if (a > 0.001f && a < 0.999f) mid++
        }
        assertTrue("未配对时中间帧必须在淡出（实测 $mid 帧）", mid > 20)
    }

    /** 配对只改照片不透明度一个通道，其余七通道与交叠逐位不变。 */
    @Test
    fun pairingChangesOnlyPhotoAlphaChannel() {
        val s = style(1)
        val plain = PhotoStyleMotion.Values()
        val pair = PhotoStyleMotion.Values()
        for (e in TransitionEdge.entries) {
            for (step in 0..20) {
                val t = step / 20f
                PhotoStyleMotion.sample(s, e, t, aspect, 0.02f, 0.31f, plain, paired = false)
                PhotoStyleMotion.sample(s, e, t, aspect, 0.02f, 0.31f, pair, paired = true)
                for (slot in 0 until 8) {
                    if (slot == PhotoStyle.SLOT_PHOTO_ALPHA) continue
                    assertEquals("边 $e t=$t 槽 $slot 不该变", plain.v[slot], pair.v[slot], 1e-5f)
                }
                for (i in 0..2) assertEquals("边 $e t=$t 交叠 $i 不该变", plain.ratios[i], pair.ratios[i], 1e-6f)
            }
        }
    }

    /** 含息屏的两条解锁/展开边：配对不影响起点（息屏恒 1），只把锁屏终点从 1 改成 0。 */
    @Test
    fun pairingOnlyMovesLockEndpointOnAodEdges() {
        val s = style(1)
        val plain = PhotoStyleMotion.Values()
        val pair = PhotoStyleMotion.Values()
        for (e in listOf(TransitionEdge.AOD_TO_LOCK, TransitionEdge.LOCK_TO_AOD)) {
            PhotoStyleMotion.sample(s, e, 0f, aspect, 0f, 0f, plain, paired = false)
            PhotoStyleMotion.sample(s, e, 0f, aspect, 0f, 0f, pair, paired = true)
            val fromAod = PhotoStyleMotion.fromOf(e) == SceneState.AOD
            if (fromAod) {
                assertEquals("息屏起点两口径应同为 1", plain.v[PhotoStyle.SLOT_PHOTO_ALPHA], pair.v[PhotoStyle.SLOT_PHOTO_ALPHA], 1e-6f)
            }
            PhotoStyleMotion.sample(s, e, 1f, aspect, 0f, 0f, plain, paired = false)
            PhotoStyleMotion.sample(s, e, 1f, aspect, 0f, 0f, pair, paired = true)
            if (!fromAod) {
                assertEquals("回息屏终点两口径应同为 1", plain.v[PhotoStyle.SLOT_PHOTO_ALPHA], pair.v[PhotoStyle.SLOT_PHOTO_ALPHA], 1e-6f)
            } else {
                assertEquals("展开到锁屏：未配对应为 1", 1f, plain.v[PhotoStyle.SLOT_PHOTO_ALPHA], 1e-6f)
                assertEquals("展开到锁屏：配对应为 0", 0f, pair.v[PhotoStyle.SLOT_PHOTO_ALPHA], 1e-6f)
            }
        }
    }

    /** 默认参数必须是未配对：不传 paired 的既有调用点行为不变。 */
    @Test
    fun defaultParameterKeepsUnpairedBehavior() {
        val s = style(1)
        val implicit = PhotoStyleMotion.Values()
        val explicit = PhotoStyleMotion.Values()
        for (state in SceneState.entries) {
            PhotoStyleMotion.sampleStatic(s, state, aspect, 0f, 0.3f, implicit)
            PhotoStyleMotion.sampleStatic(s, state, aspect, 0f, 0.3f, explicit, paired = false)
            for (slot in 0 until 8) assertEquals(implicit.v[slot], explicit.v[slot], 1e-6f)
        }
        for (e in TransitionEdge.entries) {
            PhotoStyleMotion.sample(s, e, 0.4f, aspect, 0f, 0.3f, implicit)
            PhotoStyleMotion.sample(s, e, 0.4f, aspect, 0f, 0.3f, explicit, paired = false)
            for (slot in 0 until 8) assertEquals(implicit.v[slot], explicit.v[slot], 1e-6f)
        }
    }
}