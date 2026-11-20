package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SeedlingLikeAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 喜欢动作词表契约：正向命中/负向排除/大小写/挑选，含阳性对照。 */
class SeedlingLikeActionContractTest {

    @Test
    fun englishPositive() {
        assertTrue(SeedlingLikeAction.isLikeName("like"))
        assertTrue(SeedlingLikeAction.isLikeName("com.music.LOVE"))
        assertTrue(SeedlingLikeAction.isLikeName("heart_toggle"))
        assertTrue(SeedlingLikeAction.isLikeName("Favorite"))
        assertTrue(SeedlingLikeAction.isLikeName("favourite"))
    }

    @Test
    fun chinesePositive() {
        assertTrue(SeedlingLikeAction.isLikeName("喜欢"))
        assertTrue(SeedlingLikeAction.isLikeName("红心"))
        assertTrue(SeedlingLikeAction.isLikeName("收藏"))
    }

    @Test
    fun negativeExcluded() {
        assertFalse("dislike 含 like 但必须排除", SeedlingLikeAction.isLikeName("dislike"))
        assertFalse(SeedlingLikeAction.isLikeName("unlike"))
        assertFalse(SeedlingLikeAction.isLikeName("不喜欢"))
        assertFalse(SeedlingLikeAction.isLikeName("不感兴趣"))
        assertFalse(SeedlingLikeAction.isLikeName("trash"))
    }

    @Test
    fun unrelatedRejected_positiveControl() {
        assertFalse("阳性对照：普通动作不命中", SeedlingLikeAction.isLikeName("skip_next"))
        assertFalse(SeedlingLikeAction.isLikeName("播放设备"))
        assertFalse(SeedlingLikeAction.isLikeName(null))
        assertFalse(SeedlingLikeAction.isLikeName(""))
    }

    @Test
    fun pickFirstMatch() {
        assertEquals("b_love", SeedlingLikeAction.pick("skip", "b_love", "heart"))
        assertNull(SeedlingLikeAction.pick("skip", "dislike", null))
        assertEquals("喜欢", SeedlingLikeAction.pick(null, "不喜欢", "喜欢"))
    }
}
