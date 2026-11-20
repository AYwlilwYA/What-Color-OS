package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.LazyMountPolicy
import com.coloros16.liquidglass.engine.TransitionEdge
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 锁屏面惰性挂载：只有不含息屏的边才另找舞台。
 *
 * 含 AOD 的边由息屏根承载；锁屏↔桌面两条边在息屏根摘掉后没有舞台，
 * 必须挂到窗口根，否则效果一个都不跑。
 */
class LazyMountPolicyTest {

    @Test
    fun lockHomeEdgesNeedLockStage() {
        assertTrue(LazyMountPolicy.needsLockStage(TransitionEdge.LOCK_TO_HOME))
        assertTrue(LazyMountPolicy.needsLockStage(TransitionEdge.HOME_TO_LOCK))
    }

    @Test
    fun aodEdgesDoNotNeedLockStage() {
        assertFalse(LazyMountPolicy.needsLockStage(TransitionEdge.AOD_TO_LOCK))
        assertFalse(LazyMountPolicy.needsLockStage(TransitionEdge.LOCK_TO_AOD))
        assertFalse(LazyMountPolicy.needsLockStage(TransitionEdge.AOD_TO_HOME))
        assertFalse(LazyMountPolicy.needsLockStage(TransitionEdge.HOME_TO_AOD))
    }

    @Test
    fun noEdgeDoesNothing() {
        assertFalse(LazyMountPolicy.needsLockStage(null))
    }

    @Test
    fun mountPathIsSingleFlightAndChecksAttachment() {
        val hook = File("src/main/kotlin/com/coloros16/liquidglass/hook/aod/AodStageHook.kt").readText()
        assertTrue("同一条边只试一次", hook.contains("if (lazyMountEdge == edge) return"))
        assertTrue("挂前必须验根已上屏", hook.contains("if (!root.isAttachedToWindow)"))
        assertTrue("挂载要跳过「优先找息屏根」（否则会挂到隐藏的息屏根里）", hook.contains("forceStage = root"))
        assertTrue("锁屏层插在密码盘之下", hook.contains("else bouncerIndex(stage)"))
        assertTrue("边结束要清单飞记录", hook.contains("if (edge == null) lazyMountEdge = null"))
        assertTrue("挂载日志要带 action=add|reuse（窗口重建重挂合法，不能按条数判重复）",
            hook.contains("action=$action"))
    }
}
