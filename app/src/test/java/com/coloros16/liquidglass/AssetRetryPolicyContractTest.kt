package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.AssetRetryPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 素材通道失败账契约：同一代接口短路，换代必须重试（热重载不能连坐）。 */
class AssetRetryPolicyContractTest {

    @Test
    fun neverAttemptedAlwaysTries() {
        assertTrue(AssetRetryPolicy.shouldAttempt(failed = false, sameApi = true))
        assertTrue(AssetRetryPolicy.shouldAttempt(failed = false, sameApi = false))
    }

    @Test
    fun failedSameApiShortCircuits() {
        assertFalse(AssetRetryPolicy.shouldAttempt(failed = true, sameApi = true))
    }

    /** 阳性对照：换代接口必须重试，否则热重载后永久不可用。 */
    @Test
    fun failedNewApiRetries() {
        assertTrue(AssetRetryPolicy.shouldAttempt(failed = true, sameApi = false))
    }

    @Test
    fun successClearsFailureLedger() {
        assertFalse(AssetRetryPolicy.failureAfter(success = true))
        assertTrue(AssetRetryPolicy.failureAfter(success = false))
    }

    /** 阳性对照：旧口径（只看 failed）会在换代后仍短路，与新口径必须不同。 */
    @Test
    fun newPolicyDiffersFromFailedOnlyRule() {
        val old = !true
        val new = AssetRetryPolicy.shouldAttempt(failed = true, sameApi = false)
        assertTrue("换代场景两条口径必须不同，否则是假修复", old != new)
    }
}