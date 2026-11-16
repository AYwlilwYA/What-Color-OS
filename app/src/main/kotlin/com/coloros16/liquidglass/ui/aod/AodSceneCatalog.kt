package com.coloros16.liquidglass.ui.aod

import com.coloros16.liquidglass.engine.ClockAnchor
import com.coloros16.liquidglass.engine.ElementTrack
import com.coloros16.liquidglass.engine.Keyframe
import com.coloros16.liquidglass.engine.KeyframeTrack
import com.coloros16.liquidglass.engine.SceneContract
import com.coloros16.liquidglass.engine.SceneState

/** 连续场景样式：一份契约覆盖三态，u=0 息屏、0.5 锁屏、1 桌面。 */
class AodSceneSpec(
    val contract: SceneContract,
    val clockAnchors: Map<SceneState, ClockAnchor>,
)

/** 场景样式目录：只有声明为 SCENE 的样式在这里提供世界与时钟锚点。 */
object AodSceneCatalog {

    private const val U_LOCK = 0.5f

    private fun track(vararg points: Pair<Float, Float>): KeyframeTrack =
        KeyframeTrack(points.map { Keyframe(it.first, it.second) })

    private val moon = ElementTrack(
        id = "moon",
        x = track(0f to 0.63f, U_LOCK to 0.78f, 1f to 0.85f),
        y = track(0f to 0.335f, U_LOCK to 0.23f, 1f to 0.16f),
        scale = track(0f to 0.60f, U_LOCK to 0.40f, 1f to 0.30f),
    )

    private val lockInfo = ElementTrack(
        id = "lockInfo",
        alpha = track(0f to 0f, 0.30f to 0f, 0.42f to 1f, 0.62f to 1f, 0.70f to 0f, 1f to 0f),
    )

    private val homeIcons = ElementTrack(
        id = "homeIcons",
        alpha = track(0f to 0f, 0.64f to 0f, 0.84f to 1f, 1f to 1f),
    )

    private val homeDock = ElementTrack(
        id = "homeDock",
        alpha = track(0f to 0f, 0.78f to 0f, 0.95f to 1f, 1f to 1f),
    )

    private val contract = SceneContract(
        id = "aod.scene.starmoon",
        cameraX = track(0f to 0.60f, U_LOCK to 0.545f, 1f to 0.50f),
        cameraY = track(0f to 0.56f, U_LOCK to 0.47f, 1f to 0.38f),
        cameraScale = track(0f to 1.12f, U_LOCK to 1.06f, 1f to 1.0f),
        light = track(0f to 0.25f, U_LOCK to 0.60f, 1f to 1.0f),
        elements = listOf(moon, lockInfo, homeIcons, homeDock),
    )

    private val clockAnchors = mapOf(
        SceneState.AOD to ClockAnchor(centerX = 0.50f, centerY = 0.600f, scale = 0.85f),
        SceneState.LOCK to ClockAnchor(centerX = 0.36f, centerY = 0.155f, scale = 0.62f),
        SceneState.HOME to ClockAnchor(centerX = 0.42f, centerY = 0.105f, scale = 0.46f),
    )

    fun forStyle(styleId: String): AodSceneSpec? =
        if (styleId == AodStyleCatalog.ID_STARMOON) AodSceneSpec(contract, clockAnchors) else null
}
