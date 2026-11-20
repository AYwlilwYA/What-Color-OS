package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.SceneStateMapper
import com.coloros16.liquidglass.engine.TransitionEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 起点名不具判定力时的兜底真值：`GONE` 既可能是桌面、也可能是「锁屏被熄屏取代」。
 *
 * 兜底只用上一次确定的真实态；没有确定来源就不猜边（宁可不播，不按错的曲线播）。
 */
class SceneStateMapperFallbackTest {

    private fun handoff(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/hook/AodHandoffHook.kt",
    ).readText()

    private fun keyguard(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/hook/aod/KeyguardEdgeHook.kt",
    ).readText()

    @Test
    fun goneIsAmbiguousOnlyAsStart() {
        assertEquals("作为终点仍是桌面（锁屏界面已消失）", SceneState.HOME,
            SceneStateMapper.fromTransitionStateName("GONE"))
        assertNull("作为起点不具判定力", SceneStateMapper.startingStateOf("GONE"))
        assertTrue(SceneStateMapper.isAmbiguousStart("GONE"))
        assertFalse(SceneStateMapper.isAmbiguousStart("LOCKSCREEN"))
        assertFalse(SceneStateMapper.isAmbiguousStart(null))
        assertEquals("非歧义名字起点照常", SceneState.LOCK, SceneStateMapper.startingStateOf("LOCKSCREEN"))
    }

    @Test
    fun ambiguousStartUsesLastRealScene() {
        assertEquals(
            TransitionEdge.LOCK_TO_AOD,
            SceneStateMapper.resolveEdge("GONE", "DOZING", SceneState.LOCK),
        )
        assertEquals(
            TransitionEdge.HOME_TO_AOD,
            SceneStateMapper.resolveEdge("GONE", "DOZING", SceneState.HOME),
        )
    }

    @Test
    fun ambiguousStartWithoutFallbackDoesNotGuess() {
        assertNull(SceneStateMapper.resolveEdge("GONE", "DOZING"))
        assertNull(SceneStateMapper.resolveEdge("GONE", "DOZING", null))
    }

    @Test
    fun fallbackEqualToDestinationYieldsNoEdge() {
        assertNull(
            "兜底与终点同态不得造自环",
            SceneStateMapper.resolveEdge("GONE", "DOZING", SceneState.AOD),
        )
    }

    @Test
    fun explicitNamesIgnoreFallback() {
        assertEquals(
            TransitionEdge.LOCK_TO_AOD,
            SceneStateMapper.resolveEdge("LOCKSCREEN", "DOZING", SceneState.HOME),
        )
        assertEquals(
            TransitionEdge.AOD_TO_LOCK,
            SceneStateMapper.resolveEdge("DOZING", "LOCKSCREEN", SceneState.HOME),
        )
        assertEquals(
            TransitionEdge.LOCK_TO_HOME,
            SceneStateMapper.resolveEdge("LOCKSCREEN", "GONE", SceneState.AOD),
        )
    }

    @Test
    fun unrelatedUnknownStartStillReturnsNull() {
        assertNull(
            "未知起点不得拿兜底硬凑",
            SceneStateMapper.resolveEdge("WHATEVER", "DOZING", SceneState.LOCK),
        )
    }

    @Test
    fun unlockTokenStillMeansHome() {
        assertEquals(SceneState.HOME, SceneStateMapper.fromTransitionStateName("UNLOCKED"))
        assertEquals(SceneState.HOME, SceneStateMapper.fromTransitionStateName("launcher"))
    }

    @Test
    fun handoffKeepsAndConsumesLastRealScene() {
        val s = handoff()
        assertTrue("必须把真实态传给解析", s.contains("resolveEdge(lastFrom, lastTo, lastRealScene)"))
        assertTrue("必须提供确定性记录入口", s.contains("fun noteScene(state: SceneState)"))
        assertTrue("兜底生效时日志要能看出来", s.contains("fb=\$lastRealScene"))
        val shut = s.substringAfter("fun shutdownForHotReload(")
        assertTrue("热重载必须清掉跨代真实态", shut.take(600).contains("lastRealScene = null"))
    }

    @Test
    fun keyguardEdgesFeedTheRealScene() {
        val s = keyguard()
        assertTrue(
            "锁屏边沿是确定来源，必须回报真实态",
            s.contains("TransitionEdge.HOME_TO_LOCK -> AodHandoffHook.noteScene(SceneState.LOCK)") &&
                s.contains("TransitionEdge.LOCK_TO_HOME -> AodHandoffHook.noteScene(SceneState.HOME)"),
        )
    }
}
