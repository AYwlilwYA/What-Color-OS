package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.WorldDrawPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 世界层绘制门：锁屏面静止期不得画世界层，否则挂在窗口根上会盖住锁屏内容。
 *
 * 息屏根里只有我们的层，静止期必须照画 —— 所以门不能只认 `active`。
 */
class WorldDrawPolicyTest {

    @Test
    fun aodSurfaceDrawsEvenWhenIdle() {
        assertTrue("息屏面静止期必须画世界层", WorldDrawPolicy.allowWorld(active = false, surface = SceneState.AOD))
    }

    @Test
    fun lockSurfaceDrawsOnlyWhileActive() {
        assertTrue("锁屏面转场期要画", WorldDrawPolicy.allowWorld(active = true, surface = SceneState.LOCK))
        assertFalse("锁屏面静止期不许画（会盖住锁屏）", WorldDrawPolicy.allowWorld(active = false, surface = SceneState.LOCK))
    }

    @Test
    fun lastFrameIsDeliveredWhileActive() {
        val hook = File("src/main/kotlin/com/coloros16/liquidglass/hook/aod/AodStageHook.kt").readText()
        assertTrue("正常帧路径必须 active=true（含 t=1.000 末帧），否则落位那一帧会消失",
            hook.contains("layer.bind(params, sceneLayers, playing = true)"))
    }

    @Test
    fun layerUsesSurfaceAwareGate() {
        val layer = File("src/main/kotlin/com/coloros16/liquidglass/hook/aod/AodTransitionLayer.kt").readText()
        assertTrue("世界层绘制必须过 WorldDrawPolicy（不是无条件先画）",
            layer.contains("WorldDrawPolicy.allowWorld(active, surface)"))
        val gateAt = layer.indexOf("val allowWorld = WorldDrawPolicy.allowWorld(")
        val drawAt = layer.indexOf("drawWorld(canvas, w, h)")
        assertTrue("drawWorld 必须在门之后（先取门再画）", gateAt >= 0 && drawAt > gateAt)
        assertTrue("门关分支要写回状态，否则日志会停在 true（看不到「关掉」）",
            layer.contains("worldDrawn = false") && layer.contains("world gated off"))
    }
}
