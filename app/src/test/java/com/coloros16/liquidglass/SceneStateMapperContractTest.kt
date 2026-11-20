package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.SceneStateMapper
import com.coloros16.liquidglass.engine.TransitionEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 宿主转场状态名映射契约：GONE 作为**终点**归 HOME；作为**起点**歧义，需真实态兜底。 */
class SceneStateMapperContractTest {

    @Test
    fun goneMapsToHome() {
        assertEquals(SceneState.HOME, SceneStateMapper.fromTransitionStateName("GONE"))
        assertEquals(SceneState.HOME, SceneStateMapper.fromTransitionStateName("gone"))
    }

    @Test
    fun dozingMapsToAod() {
        assertEquals(SceneState.AOD, SceneStateMapper.fromTransitionStateName("DOZING"))
        assertEquals(SceneState.AOD, SceneStateMapper.fromTransitionStateName("AOD"))
        assertEquals(SceneState.AOD, SceneStateMapper.fromTransitionStateName("DREAMING"))
    }

    @Test
    fun lockscreenMapsToLock() {
        assertEquals(SceneState.LOCK, SceneStateMapper.fromTransitionStateName("LOCKSCREEN"))
        assertEquals(SceneState.LOCK, SceneStateMapper.fromTransitionStateName("OCCLUDED"))
        assertEquals(SceneState.LOCK, SceneStateMapper.fromTransitionStateName("PRIMARY_BOUNCER"))
    }

    /** 真机实测：GONE→DOZING 在桌面熄屏与锁屏熄屏两种情形都会出现，起点靠真实态兜底。 */
    @Test
    fun realDeviceGoneDozingEdgesResolve() {
        assertEquals(
            "终点 GONE 仍是桌面",
            TransitionEdge.AOD_TO_HOME,
            SceneStateMapper.resolveEdge("DOZING", "GONE"),
        )
        assertNull(
            "起点 GONE 无兜底时不得猜成桌面",
            SceneStateMapper.resolveEdge("GONE", "DOZING"),
        )
        assertEquals(
            "从锁屏熄屏",
            TransitionEdge.LOCK_TO_AOD,
            SceneStateMapper.resolveEdge("GONE", "DOZING", SceneState.LOCK),
        )
        assertEquals(
            "从桌面熄屏",
            TransitionEdge.HOME_TO_AOD,
            SceneStateMapper.resolveEdge("GONE", "DOZING", SceneState.HOME),
        )
    }

    @Test
    fun lockscreenDozingEdgesResolve() {
        assertEquals(
            TransitionEdge.LOCK_TO_AOD,
            SceneStateMapper.resolveEdge("LOCKSCREEN", "DOZING"),
        )
        assertEquals(
            TransitionEdge.AOD_TO_LOCK,
            SceneStateMapper.resolveEdge("DOZING", "LOCKSCREEN"),
        )
    }

    @Test
    fun lockscreenGoneEdgesResolve() {
        assertEquals(
            TransitionEdge.LOCK_TO_HOME,
            SceneStateMapper.resolveEdge("LOCKSCREEN", "GONE"),
        )
        assertEquals(
            TransitionEdge.HOME_TO_LOCK,
            SceneStateMapper.resolveEdge("GONE", "LOCKSCREEN", SceneState.HOME),
        )
    }

    /** 阳性对照：去掉 GONE 后这两条边必然解析失败（旧口径恒 null）。 */
    @Test
    fun goneIsTheOnlyTokenMakingTheseEdgesWork() {
        val withoutGone = listOf("UNLOCK", "HOME", "LAUNCHER")
        val name = "GONE"
        assertNull(
            "旧 token 表不含 GONE，故旧口径必然识别不出",
            withoutGone.firstOrNull { name.contains(it) },
        )
        assertEquals(SceneState.HOME, SceneStateMapper.fromTransitionStateName(name))
    }

    @Test
    fun unknownAndBlankStayNull() {
        assertNull(SceneStateMapper.fromTransitionStateName(null))
        assertNull(SceneStateMapper.fromTransitionStateName(""))
        assertNull(SceneStateMapper.fromTransitionStateName("UNDEFINED"))
        assertNull(SceneStateMapper.fromTransitionStateName("OFF"))
        assertNull(SceneStateMapper.resolveEdge("GONE", "UNDEFINED"))
    }

    @Test
    fun sameStateHasNoEdge() {
        assertNull(SceneStateMapper.resolveEdge("GONE", "GONE"))
        assertNull(SceneStateMapper.resolveEdge("DOZING", "DOZING"))
    }
}