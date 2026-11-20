package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.SeekbarHostPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekbarHostPolicyTest {

    private val volume = "com.oplus.systemui.volume.OplusVolumeSeekBar"
    private val qs = "com.oplus.systemui.qs.base.seek.OplusQsVerticalSeekBar"
    private val coui = "com.coui.appcompat.seekbar.COUIVerticalSeekBar"

    @Test
    fun volumeAlwaysSelfDraws() {
        assertTrue(SeekbarHostPolicy.isSelfDrawHost(volume, qsFactoryAvailable = true))
        assertTrue(SeekbarHostPolicy.isSelfDrawHost(volume, qsFactoryAvailable = false))
    }

    @Test
    fun qsSliderSelfDrawsOnlyWhenFactoryMissing() {
        assertTrue("无进度层工厂 → 自绘腿", SeekbarHostPolicy.isSelfDrawHost(qs, qsFactoryAvailable = false))
    }

    @Test
    fun qsSliderSkippedWhenFactoryPresent() {
        assertFalse("有工厂走既有漏斗，避免双腿叠加", SeekbarHostPolicy.isSelfDrawHost(qs, qsFactoryAvailable = true))
    }

    @Test
    fun parentClassNeverSelfDraws() {
        assertFalse(SeekbarHostPolicy.isSelfDrawHost(coui, qsFactoryAvailable = false))
        assertFalse(SeekbarHostPolicy.isSelfDrawHost(coui, qsFactoryAvailable = true))
    }

    @Test
    fun unrelatedViewNeverSelfDraws() {
        assertFalse(SeekbarHostPolicy.isSelfDrawHost("android.widget.TextView", false))
        assertFalse(SeekbarHostPolicy.isSelfDrawHost("com.oplus.systemui.qs.OplusQSTileView", false))
    }

    @Test
    fun suffixMatchIsEndsWithNotContains() {
        assertFalse(
            "中间出现同名不算命中",
            SeekbarHostPolicy.isSelfDrawHost("x.OplusVolumeSeekBarWrapper", false),
        )
    }

    @Test
    fun positiveControlFactoryFlagFlipsQsOnly() {
        val withFactory = SeekbarHostPolicy.isSelfDrawHost(qs, true)
        val withoutFactory = SeekbarHostPolicy.isSelfDrawHost(qs, false)
        assertFalse(withFactory)
        assertTrue(withoutFactory)
        assertEquals(
            "音量条不受该标志影响",
            SeekbarHostPolicy.isSelfDrawHost(volume, true),
            SeekbarHostPolicy.isSelfDrawHost(volume, false),
        )
    }

    @Test
    fun suffixConstantsMatchRomNames() {
        assertEquals("OplusVolumeSeekBar", SeekbarHostPolicy.SUFFIX_VOLUME)
        assertEquals("OplusQsVerticalSeekBar", SeekbarHostPolicy.SUFFIX_QS_VERTICAL)
    }
}
