package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SeedlingCoverPolicy
import com.coloros16.liquidglass.engine.SeedlingLaneScroll
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 封面策略 + 长文字滚动契约：白名单/采样/防抖/滚动周期，含阳性对照。 */
class SeedlingCoverLaneContractTest {

    // ---------- 封面 URI 白名单 ----------

    @Test
    fun schemeWhitelist() {
        assertTrue(SeedlingCoverPolicy.schemeAllowed("content"))
        assertTrue(SeedlingCoverPolicy.schemeAllowed("android.resource"))
        assertTrue(SeedlingCoverPolicy.schemeAllowed("file"))
        assertTrue(SeedlingCoverPolicy.schemeAllowed("CONTENT"))
        assertFalse("http 不在白名单（防远程加载）", SeedlingCoverPolicy.schemeAllowed("http"))
        assertFalse(SeedlingCoverPolicy.schemeAllowed("https"))
        assertFalse(SeedlingCoverPolicy.schemeAllowed(null))
    }

    // ---------- 采样倍数（翻倍到 <512） ----------

    @Test
    fun sampleSizeDoubles() {
        assertEquals(1, SeedlingCoverPolicy.sampleSizeFor(500, 300))
        assertEquals(1, SeedlingCoverPolicy.sampleSizeFor(1023, 100))
        assertEquals(2, SeedlingCoverPolicy.sampleSizeFor(1024, 100))
        assertEquals(2, SeedlingCoverPolicy.sampleSizeFor(2047, 2047))
        assertEquals(4, SeedlingCoverPolicy.sampleSizeFor(2048, 100))
        assertEquals(8, SeedlingCoverPolicy.sampleSizeFor(4096, 4096))
    }

    @Test
    fun sampleSizeInvalidDims() {
        assertEquals(1, SeedlingCoverPolicy.sampleSizeFor(0, 100))
        assertEquals(1, SeedlingCoverPolicy.sampleSizeFor(-5, -5))
    }

    @Test
    fun sampledNextDoubleBelow512_positiveControl() {
        // 参考真值：停止条件是「再翻一倍就 <512」，当前采样长边可仍 ≥512（3000/4=750）
        val s = SeedlingCoverPolicy.sampleSizeFor(3000, 2000)
        assertEquals(4, s)
        assertTrue("阳性对照：再翻一倍必 <512（参考停止条件）", 3000 / (s * 2) < 512)
    }

    // ---------- 切歌防抖 ----------

    @Test
    fun debounceWaitsWhenCoverMissing() {
        val w = SeedlingCoverPolicy.coverDebounceMs(true, true, false, true, 100L)
        assertEquals(320L, w)
    }

    @Test
    fun debounceSkipsWhenCoverArrived() {
        assertEquals(0L, SeedlingCoverPolicy.coverDebounceMs(true, true, true, true, 100L))
    }

    @Test
    fun debounceSkipsAfter400ms() {
        assertEquals(0L, SeedlingCoverPolicy.coverDebounceMs(true, true, false, true, 400L))
        assertEquals(0L, SeedlingCoverPolicy.coverDebounceMs(true, true, false, true, 999L))
    }

    @Test
    fun debounceSkipsSameTrack_positiveControl() {
        assertEquals("阳性对照：同曲不防抖", 0L, SeedlingCoverPolicy.coverDebounceMs(true, false, false, true, 0L))
        assertEquals("无旧封面不防抖", 0L, SeedlingCoverPolicy.coverDebounceMs(true, true, false, false, 0L))
    }

    @Test
    fun debounceCapped420() {
        assertTrue(SeedlingCoverPolicy.coverDebounceMs(true, true, false, true, 0L) <= 420L)
    }

    // ---------- 长文字滚动 ----------

    @Test
    fun shortTextNeverScrolls() {
        val lane = SeedlingLaneScroll(100f, 200f, 84f)
        assertFalse(lane.scrollable)
        assertEquals(0f, lane.offsetAt(0L), 1e-4f)
        assertEquals(0f, lane.offsetAt(99999L), 1e-4f)
    }

    @Test
    fun longTextScrollsEndPause() {
        val lane = SeedlingLaneScroll(300f, 100f, 100f)
        assertTrue(lane.scrollable)
        assertEquals("起点停 1s", 0f, lane.offsetAt(0L), 1e-4f)
        assertEquals(0f, lane.offsetAt(999L), 1e-4f)
        assertEquals("终点停", 200f, lane.offsetAt(lane.cycleMs - 1L), 1e-3f)
    }

    @Test
    fun scrollMidpoint() {
        val lane = SeedlingLaneScroll(300f, 100f, 100f)
        // travel=200px @100px/s=2000ms；cycle=1000+2000+1000=4000
        assertEquals(4000L, lane.cycleMs)
        assertEquals("中点走一半", 100f, lane.offsetAt(2000L), 1f)
    }

    @Test
    fun scrollWrapsCycle_positiveControl() {
        val lane = SeedlingLaneScroll(300f, 100f, 100f)
        assertEquals("阳性对照：整周期后回到起点", lane.offsetAt(123L), lane.offsetAt(lane.cycleMs + 123L), 1e-3f)
    }

    @Test
    fun zeroSpeedNotScrollable() {
        assertFalse(SeedlingLaneScroll(300f, 100f, 0f).scrollable)
    }
}
