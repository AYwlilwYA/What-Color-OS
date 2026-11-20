package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.PhotoStyle
import com.coloros16.liquidglass.engine.PhotoStyleMotion
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.TransitionEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 照片类整段时长的视频轨分母契约：视频轨时长取两端视频时间之差，不是视频全长。
 *
 * 锁屏与桌面的视频都停在末帧，两端相等 ⇒ 该轨瞬时、不参与最长轨；
 * 只有含息屏的四条边一端在起始帧，才走全长。
 */
class PhotoStyleVideoSpanContractTest {

    private val aspect = 2400f / 1080f

    private fun style(id: Int): PhotoStyle = PhotoStyleMotion.byId(id) ?: error("取不到样式 $id")

    /** 含息屏的四条边走视频全长（整除后的毫秒）。 */
    @Test
    fun aodEdgesUseFullVideoLength() {
        for (id in 1..5) {
            val s = style(id)
            val full = s.endMicros.toLong() / 1000L
            for (e in listOf(
                TransitionEdge.AOD_TO_LOCK, TransitionEdge.LOCK_TO_AOD,
                TransitionEdge.AOD_TO_HOME, TransitionEdge.HOME_TO_AOD,
            )) {
                assertEquals("样式 $id 边 $e 视频轨应走全长", full, PhotoStyleMotion.videoSpanMs(s, e))
            }
        }
    }

    /** 锁屏↔桌面两端视频都在末帧 ⇒ 视频轨瞬时。 */
    @Test
    fun lockHomeEdgesHaveZeroVideoSpan() {
        for (id in 1..5) {
            val s = style(id)
            assertEquals("样式 $id 解锁边视频轨应瞬时", 0L, PhotoStyleMotion.videoSpanMs(s, TransitionEdge.LOCK_TO_HOME))
            assertEquals("样式 $id 回锁屏边视频轨应瞬时", 0L, PhotoStyleMotion.videoSpanMs(s, TransitionEdge.HOME_TO_LOCK))
        }
    }

    /** 分母同源：videoSpanMs 必须等于 sample 内用的两端差。 */
    @Test
    fun spanMatchesSampleEndpointDifference() {
        for (id in 1..5) {
            val s = style(id)
            for (e in TransitionEdge.entries) {
                val from = PhotoStyleMotion.Values()
                val to = PhotoStyleMotion.Values()
                PhotoStyleMotion.sampleStatic(s, PhotoStyleMotion.fromOf(e), aspect, 0f, 0.3f, from)
                PhotoStyleMotion.sampleStatic(s, PhotoStyleMotion.toOf(e), aspect, 0f, 0.3f, to)
                val expected = (kotlin.math.abs(
                    from.v[PhotoStyle.SLOT_VIDEO] - to.v[PhotoStyle.SLOT_VIDEO],
                ) / 1000f).toLong()
                assertEquals("样式 $id 边 $e 两处视频分母必须同源", expected, PhotoStyleMotion.videoSpanMs(s, e))
            }
        }
    }

    /** 五款 × 六边整段时长的真值全表（手算复核，非现状抄录）。 */
    @Test
    fun allStyleEdgeDurationsMatchTruth() {
        val truth = arrayOf(
            longArrayOf(717, 530, 800, 500, 717, 530),
            longArrayOf(683, 743, 800, 500, 703, 743),
            longArrayOf(867, 733, 800, 500, 867, 733),
            longArrayOf(900, 716, 800, 500, 900, 716),
            longArrayOf(1100, 916, 800, 500, 1100, 916),
        )
        for (id in 1..5) {
            for ((i, e) in TransitionEdge.entries.withIndex()) {
                assertEquals(
                    "样式 $id 边 $e 整段时长", truth[id - 1][i], PhotoStyleMotion.durationMs(style(id), e),
                )
            }
        }
    }

    /** 解锁两条边整段时长不得超过 800ms：视频轨瞬时，最长轨只能是交叠或照片透明度轨。 */
    @Test
    fun lockHomeEdgesBoundedByBlendAndPhotoAlpha() {
        for (id in 1..5) {
            assertEquals("样式 $id 解锁边应恰为 800ms", 800L, PhotoStyleMotion.durationMs(style(id), TransitionEdge.LOCK_TO_HOME))
            assertEquals("样式 $id 回锁屏边应恰为公共 500ms", 500L, PhotoStyleMotion.durationMs(style(id), TransitionEdge.HOME_TO_LOCK))
        }
    }

    /** 视频轨在解锁边确实瞬时：t=0 就已经在末帧，不等任何时间。 */
    @Test
    fun videoReachesEndFrameAtEdgeStartOnUnlock() {
        for (id in 1..5) {
            val s = style(id)
            val out = PhotoStyleMotion.Values()
            PhotoStyleMotion.sample(s, TransitionEdge.LOCK_TO_HOME, 0f, aspect, 0f, 0f, out)
            assertEquals("样式 $id 解锁起点视频必须已在末帧", s.endMicros.toFloat(), out.v[PhotoStyle.SLOT_VIDEO], 1f)
            PhotoStyleMotion.sample(s, TransitionEdge.HOME_TO_LOCK, 0f, aspect, 0f, 0f, out)
            assertEquals("样式 $id 回锁屏起点视频必须已在末帧", s.endMicros.toFloat(), out.v[PhotoStyle.SLOT_VIDEO], 1f)
        }
    }

    /** 阳性对照：按旧口径（视频轨取全长）重算，五款里三款被虚增，最大银河回锁屏 83.2%。 */
    @Test
    fun positiveControlFullLengthInflatesThreeStyles() {
        val inflated = mapOf(
            2 to (516L to 16L),
            3 to (616L to 116L),
            4 to (716L to 216L),
            5 to (916L to 416L),
        )
        for (id in 1..5) {
            val s = style(id)
            val full = s.endMicros.toLong() / 1000L
            var old = s.blendDuration(5)
            for (slot in 0 until 8) {
                val d = if (slot == PhotoStyle.SLOT_VIDEO) full else 400L
                old = maxOf(old, d)
            }
            val now = PhotoStyleMotion.durationMs(s, TransitionEdge.HOME_TO_LOCK)
            val expect = inflated[id]
            if (expect == null) {
                assertEquals("样式 $id 视频短于公共时长，两口径应相等", old, now)
            } else {
                assertEquals("样式 $id 旧口径应复现 $expect", expect.first, old)
                assertEquals("样式 $id 虚增量", expect.second, old - now)
                assertTrue("样式 $id 必须真被虚增", old > now)
            }
        }
        val galaxyOld = 916L
        val galaxyNew = PhotoStyleMotion.durationMs(style(5), TransitionEdge.HOME_TO_LOCK)
        assertTrue(
            "银河回锁屏虚增比例应超 80%（实测 ${(galaxyOld - galaxyNew) * 100f / galaxyNew}）",
            (galaxyOld - galaxyNew) * 100f / galaxyNew > 80f,
        )
    }

    /** 留光款视频最短（483ms）短于公共 500ms ⇒ 两口径恒等，缺陷藏在长视频款。 */
    @Test
    fun shortestVideoStyleMasksTheDefect() {
        val leave = style(1)
        assertTrue("留光视频必须短于公共交叠时长", leave.endMicros / 1000L < leave.blendDuration(5))
        for (e in TransitionEdge.entries) {
            var old = leave.blendDuration(PhotoStyleMotion.transitionOf(e))
            val tr = PhotoStyleMotion.transitionOf(e)
            for (slot in 0 until 8) {
                var d = 400L
                if (slot == PhotoStyle.SLOT_VIDEO) d = leave.endMicros.toLong() / 1000L
                if (slot == PhotoStyle.SLOT_PHOTO_ALPHA && tr == 4) d = 800L
                old = maxOf(old, d)
            }
            val pause = if (PhotoStyleMotion.fromOf(e) == SceneState.AOD) PhotoStyle.OPEN_DELAY_MS else 0L
            assertEquals("留光边 $e 两口径必须相等（缺陷不可见）", pause + old, PhotoStyleMotion.durationMs(leave, e))
        }
    }
}