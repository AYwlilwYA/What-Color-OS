package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.ReplayLedger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayLedgerContractTest {

    @Test
    fun firstStartIsNotSuperseded() {
        assertFalse(ReplayLedger.superseded(ReplayLedger.NO_TOKEN, 0L))
        assertFalse(ReplayLedger.superseded(ReplayLedger.NO_TOKEN, 7L))
    }

    @Test
    fun sameGenerationIsSameTransition() {
        assertFalse(ReplayLedger.superseded(3L, 3L))
        assertFalse(ReplayLedger.superseded(0L, 0L))
    }

    @Test
    fun screenFlipSupersedesPreviousStart() {
        assertTrue(ReplayLedger.superseded(3L, 4L))
        assertTrue(ReplayLedger.superseded(0L, 1L))
    }

    @Test
    fun olderTokenStillCountsAsSuperseded() {
        assertTrue(ReplayLedger.superseded(9L, 2L))
    }

    @Test
    fun selfCompareNeverDetectsExpiry() {
        val current = 5L
        assertFalse(ReplayLedger.superseded(current, current))
    }
}