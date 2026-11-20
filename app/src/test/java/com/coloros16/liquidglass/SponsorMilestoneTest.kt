package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SponsorMilestone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SponsorMilestoneTest {

    @Test
    fun fixedMilestonesHitExactly() {
        assertEquals(10, SponsorMilestone.forCount(10))
        assertEquals(20, SponsorMilestone.forCount(20))
        assertEquals(50, SponsorMilestone.forCount(50))
        assertEquals(100, SponsorMilestone.forCount(100))
        assertEquals(200, SponsorMilestone.forCount(200))
        assertEquals(400, SponsorMilestone.forCount(400))
    }

    @Test
    fun nonMilestoneCountsDoNotHit() {
        assertNull(SponsorMilestone.forCount(9))
        assertNull(SponsorMilestone.forCount(11))
        assertNull(SponsorMilestone.forCount(19))
        assertNull(SponsorMilestone.forCount(21))
        assertNull(SponsorMilestone.forCount(399))
        assertNull(SponsorMilestone.forCount(401))
        assertNull(SponsorMilestone.forCount(500))
    }

    @Test
    fun afterLastMilestoneStepsEveryFourHundred() {
        assertEquals(800, SponsorMilestone.forCount(800))
        assertEquals(1200, SponsorMilestone.forCount(1200))
        assertNull(SponsorMilestone.forCount(700))
        assertNull(SponsorMilestone.forCount(799))
    }

    @Test
    fun nonPositiveNeverHits() {
        assertNull(SponsorMilestone.forCount(0))
        assertNull(SponsorMilestone.forCount(-10))
        assertNull(SponsorMilestone.forCount(Int.MIN_VALUE))
    }
}
