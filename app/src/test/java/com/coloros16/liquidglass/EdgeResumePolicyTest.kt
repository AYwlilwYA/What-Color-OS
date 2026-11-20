package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.ClockSource
import com.coloros16.liquidglass.engine.ClockState
import com.coloros16.liquidglass.engine.EdgeResumePolicy
import com.coloros16.liquidglass.engine.TransitionEdge
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 补武装判定：息屏时钟每 ~2s 失效一次，每次都会走一遍挂载判定链。
 *
 * 边已落位（t≥1 且没在播）时补武装只会白起一帧；不判就会反复起帧 + 每 2s 一条日志。
 */
class EdgeResumePolicyTest {

    private fun st(edge: TransitionEdge?, t: Float, playing: Boolean) =
        ClockState(edge, ClockSource.SYSTEM_PUMP, t, playing, 0L)

    @Test
    fun finishedEdgeDoesNotResume() {
        assertFalse(
            "已落位（t=1 且没在播）不得再起帧",
            EdgeResumePolicy.needsResume(TransitionEdge.HOME_TO_AOD, st(TransitionEdge.HOME_TO_AOD, 1f, false)),
        )
    }

    @Test
    fun runningEdgeResumes() {
        assertTrue(
            "边还在跑必须补武装",
            EdgeResumePolicy.needsResume(TransitionEdge.HOME_TO_AOD, st(TransitionEdge.HOME_TO_AOD, 0.4f, false)),
        )
        assertTrue(
            "自播中必须补武装",
            EdgeResumePolicy.needsResume(TransitionEdge.LOCK_TO_HOME, st(TransitionEdge.LOCK_TO_HOME, 0.9f, true)),
        )
    }

    @Test
    fun edgeSwitchResumes() {
        assertTrue(
            "时钟还停在别的边 ⇒ 当前边需要补",
            EdgeResumePolicy.needsResume(TransitionEdge.LOCK_TO_HOME, st(TransitionEdge.AOD_TO_LOCK, 1f, false)),
        )
    }

    @Test
    fun noEdgeOrNoState() {
        assertFalse("没有系统边不起帧", EdgeResumePolicy.needsResume(null, st(TransitionEdge.LOCK_TO_HOME, 0.2f, true)))
        assertTrue("桥未就绪时保守补一次", EdgeResumePolicy.needsResume(TransitionEdge.LOCK_TO_HOME, null))
    }
}
