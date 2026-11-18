package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.PhotoStyle
import com.coloros16.liquidglass.engine.PhotoStyleMotion
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.TransitionEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoStyleMotionTest {

    private fun values() = PhotoStyleMotion.Values()

    private fun moon(): PhotoStyle = PhotoStyleMotion.byId(2)!!

    @Test
    fun styleTableMatchesReference() {
        val expected = listOf(
            // id, folder, size, frames, fps, end, rotation, blur, scale, content
            intArrayOf(1, 720, 61, 120, 483667),
            intArrayOf(2, 720, 32, 60, 516667),
            intArrayOf(3, 720, 41, 60, 616667),
            intArrayOf(4, 1080, 43, 60, 716667),
            intArrayOf(5, 1080, 56, 60, 916667),
        )
        for (row in expected) {
            val style = PhotoStyleMotion.byId(row[0])
            assertNotNull("style ${row[0]}", style)
            assertEquals(row[1], style!!.size)
            assertEquals(row[2], style.frames)
            assertEquals(row[3], style.fps)
            assertEquals(row[4], style.endMicros)
        }
        assertEquals("leave_light", PhotoStyleMotion.byId(1)!!.folder)
        assertEquals("moon", PhotoStyleMotion.byId(2)!!.folder)
        assertEquals("door", PhotoStyleMotion.byId(3)!!.folder)
        assertEquals("mountain", PhotoStyleMotion.byId(4)!!.folder)
        assertEquals("galaxy", PhotoStyleMotion.byId(5)!!.folder)
        assertEquals(-5f, PhotoStyleMotion.byId(1)!!.rotation, 1e-6f)
        assertEquals(22f, PhotoStyleMotion.byId(2)!!.blur, 1e-6f)
        assertEquals(100f, PhotoStyleMotion.byId(3)!!.blur, 1e-6f)
        assertEquals(0.986f, PhotoStyleMotion.byId(2)!!.scale, 1e-6f)
        assertEquals(2.34f, PhotoStyleMotion.byId(2)!!.content, 1e-6f)
    }

    @Test
    fun staticAodSitsOnAnchor() {
        val v = values()
        PhotoStyleMotion.sampleStatic(moon(), SceneState.AOD, 2.16f, -0.02f, 0.06f, v)
        assertEquals(22f, v.v[PhotoStyle.SLOT_BLUR], 1e-6f)
        assertEquals(0f, v.v[PhotoStyle.SLOT_ROTATION], 1e-6f)
        assertEquals(0.986f, v.v[PhotoStyle.SLOT_SCALE], 1e-6f)
        assertEquals(2.34f, v.v[PhotoStyle.SLOT_CONTENT], 1e-6f)
        assertEquals(-0.02f, v.v[PhotoStyle.SLOT_X], 1e-6f)
        assertEquals(0.06f, v.v[PhotoStyle.SLOT_Y], 1e-6f)
        assertEquals(0f, v.v[PhotoStyle.SLOT_VIDEO], 1e-6f)
        assertEquals(1f, v.v[PhotoStyle.SLOT_PHOTO_ALPHA], 1e-6f)
        assertEquals(1f, v.ratios[0], 1e-6f)
    }

    @Test
    fun staticLockExpandsToFullScreen() {
        val v = values()
        val aspect = 19.5f / 9f
        PhotoStyleMotion.sampleStatic(moon(), SceneState.LOCK, aspect, 0f, 0f, v)
        assertEquals(PhotoStyle.EXPANDED_SCALE, v.v[PhotoStyle.SLOT_SCALE], 1e-6f)
        assertEquals(aspect / PhotoStyle.EXPANDED_SCALE, v.v[PhotoStyle.SLOT_CONTENT], 1e-5f)
        assertEquals(0f, v.v[PhotoStyle.SLOT_X], 1e-6f)
        assertEquals(0f, v.v[PhotoStyle.SLOT_Y], 1e-6f)
        assertEquals(0f, v.v[PhotoStyle.SLOT_BLUR], 1e-6f)
        assertEquals(516667f, v.v[PhotoStyle.SLOT_VIDEO], 1f)
        assertEquals(1f, v.ratios[1], 1e-6f)

        PhotoStyleMotion.sampleStatic(moon(), SceneState.HOME, aspect, 0f, 0f, v)
        assertEquals(0f, v.v[PhotoStyle.SLOT_PHOTO_ALPHA], 1e-6f)
        assertEquals(1f, v.ratios[2], 1e-6f)
    }

    @Test
    fun openEdgeHoldsStillDuringPause() {
        val v = values()
        // 150ms 停顿 / 683ms 整段 ≈ 0.22 之前保持息屏状态。
        PhotoStyleMotion.sample(moon(), TransitionEdge.AOD_TO_LOCK, 0.10f, 2.16f, 0f, 0.06f, v)
        assertEquals(0.986f, v.v[PhotoStyle.SLOT_SCALE], 1e-5f)
        assertEquals(22f, v.v[PhotoStyle.SLOT_BLUR], 1e-5f)
        assertEquals(0.06f, v.v[PhotoStyle.SLOT_Y], 1e-5f)
        assertEquals(1f, v.ratios[0], 1e-6f)
    }

    @Test
    fun openEdgeEndsExpanded() {
        val v = values()
        PhotoStyleMotion.sample(moon(), TransitionEdge.AOD_TO_LOCK, 1f, 2.16f, 0f, 0.06f, v)
        assertEquals(PhotoStyle.EXPANDED_SCALE, v.v[PhotoStyle.SLOT_SCALE], 1e-4f)
        assertEquals(0f, v.v[PhotoStyle.SLOT_X], 1e-4f)
        assertEquals(0f, v.v[PhotoStyle.SLOT_Y], 1e-4f)
        assertEquals(0f, v.v[PhotoStyle.SLOT_BLUR], 1e-4f)
        assertEquals(516667f, v.v[PhotoStyle.SLOT_VIDEO], 1f)
        assertEquals(1f, v.ratios[1], 1e-6f)
    }

    @Test
    fun reversePlaysVideoBackwards() {
        val v = values()
        PhotoStyleMotion.sample(moon(), TransitionEdge.LOCK_TO_AOD, 0f, 2.16f, 0f, 0.06f, v)
        assertEquals(516667f, v.v[PhotoStyle.SLOT_VIDEO], 1f)
        assertEquals(1f, v.ratios[1], 1e-6f)

        PhotoStyleMotion.sample(moon(), TransitionEdge.LOCK_TO_AOD, 1f, 2.16f, 0f, 0.06f, v)
        assertEquals(0f, v.v[PhotoStyle.SLOT_VIDEO], 1f)
        assertEquals(0.986f, v.v[PhotoStyle.SLOT_SCALE], 1e-4f)
        assertEquals(22f, v.v[PhotoStyle.SLOT_BLUR], 1e-4f)
        assertEquals(1f, v.ratios[0], 1e-6f)
    }

    @Test
    fun durationIncludesPauseAndLongestTrack() {
        // 息屏→锁屏：150ms 停顿 + 最长通道 533ms（交叠同为 533）。
        assertEquals(683L, PhotoStyleMotion.durationMs(moon(), TransitionEdge.AOD_TO_LOCK))
        // 锁屏→息屏：无停顿，最长通道为槽位 2 的 567+176=743。
        assertEquals(743L, PhotoStyleMotion.durationMs(moon(), TransitionEdge.LOCK_TO_AOD))
        // 银河：交叠 950ms 且视频 917ms，最长取 950。
        val galaxy = PhotoStyleMotion.byId(5)!!
        assertEquals(1100L, PhotoStyleMotion.durationMs(galaxy, TransitionEdge.AOD_TO_LOCK))
    }

    @Test
    fun blendDurationUsesStyleTable() {
        assertEquals(533L, moon().blendDuration(0))
        assertEquals(743L, moon().blendDuration(1))
        assertEquals(800L, moon().blendDuration(4))
        // 留光锁屏↔桌面只走公共时长。
        assertEquals(500L, PhotoStyleMotion.byId(1)!!.blendDuration(4))
    }

    @Test
    fun frameIndexMapsStartAndEnd() {
        assertEquals(32, moon().frameIndex(0f))
        assertEquals(33, moon().frameIndex(516667f))
        assertEquals(0, moon().frameIndex(1f))
        assertEquals(16, moon().frameIndex(266667f))
        assertEquals(32, moon().frameIndex(-5f))
    }

    @Test
    fun frameNameMatchesAssetLayout() {
        assertEquals("start", moon().frameName(moon().frames))
        assertEquals("end", moon().frameName(moon().frames + 1))
        assertEquals("000", moon().frameName(0))
        assertEquals("031", moon().frameName(31))
    }

    @Test
    fun ratiosBlendAcrossOpenEdge() {
        val v = values()
        PhotoStyleMotion.sample(moon(), TransitionEdge.AOD_TO_LOCK, 0.6f, 2.16f, 0f, 0.06f, v)
        assertTrue(v.ratios[0] > 0f && v.ratios[0] < 1f)
        assertTrue(v.ratios[1] > 0f && v.ratios[1] < 1f)
        assertEquals(1f, v.ratios[0] + v.ratios[1], 1e-5f)
    }

    @Test
    fun leaveLightRotatesBackToUpright() {
        val style = PhotoStyleMotion.byId(1)!!
        val v = values()
        PhotoStyleMotion.sampleStatic(style, SceneState.AOD, 2.16f, 0f, 0.06f, v)
        assertEquals(-5f, v.v[PhotoStyle.SLOT_ROTATION], 1e-6f)
        PhotoStyleMotion.sample(style, TransitionEdge.AOD_TO_LOCK, 1f, 2.16f, 0f, 0.06f, v)
        assertEquals(0f, v.v[PhotoStyle.SLOT_ROTATION], 1e-4f)
    }
}
