package com.coloros16.liquidglass.ui.aod

import com.coloros16.liquidglass.engine.ClockAnchor
import com.coloros16.liquidglass.engine.PhotoStyle
import com.coloros16.liquidglass.engine.PhotoStyleMotion
import com.coloros16.liquidglass.engine.SceneContract
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.SailLensMotion
import com.coloros16.liquidglass.engine.SystemSceneMotion
import com.coloros16.liquidglass.engine.TransitionEdge

/** 连续场景样式：一份契约覆盖三态，u=0 息屏、0.5 锁屏、1 桌面。 */
class AodSceneSpec(
    val contract: SceneContract,
    val clockAnchors: Map<SceneState, ClockAnchor>,
    val lens: Boolean = false,
    val photo: PhotoStyle? = null,
)

/** 场景样式目录：只有声明为 SCENE 的样式在这里提供世界与时钟锚点。 */
object AodSceneCatalog {

    private val sailContract = SceneContract(id = "aod.scene.sail")

    /** 启航镜片在息屏时位于中心偏上，纵向再叠加防烧屏漂移。 */
    private val sailClockAnchors = mapOf(
        SceneState.AOD to ClockAnchor(centerX = 0.50f, centerY = 0.435f, scale = 1.0f),
        SceneState.LOCK to ClockAnchor(centerX = 0.36f, centerY = 0.155f, scale = 0.62f),
        SceneState.HOME to ClockAnchor(centerX = 0.42f, centerY = 0.105f, scale = 0.46f, alpha = 0f),
    )

    /** 照片类样式：特效以息屏时钟为圆心，展开后铺满整屏。 */
    private val photoClockAnchors = sailClockAnchors

    fun photoOf(styleId: String): PhotoStyle? = when (styleId) {
        AodStyleCatalog.ID_LEAVE_LIGHT -> PhotoStyleMotion.byId(1)
        AodStyleCatalog.ID_STARMOON -> PhotoStyleMotion.byId(2)
        AodStyleCatalog.ID_DOOR -> PhotoStyleMotion.byId(3)
        AodStyleCatalog.ID_MOUNTAIN -> PhotoStyleMotion.byId(4)
        AodStyleCatalog.ID_GALAXY -> PhotoStyleMotion.byId(5)
        else -> null
    }

    fun durationOf(styleId: String, edge: TransitionEdge): Long {
        if (styleId == AodStyleCatalog.ID_SAIL) return SailLensMotion.durationMs(edge)
        val photo = photoOf(styleId) ?: return SystemSceneMotion.durationOf(edge)
        return PhotoStyleMotion.durationMs(photo, edge)
    }

    fun forStyle(styleId: String): AodSceneSpec? {
        if (styleId == AodStyleCatalog.ID_SAIL) {
            return AodSceneSpec(sailContract, sailClockAnchors, lens = true)
        }
        val photo = photoOf(styleId) ?: return null
        return AodSceneSpec(
            contract = SceneContract("aod.photo.${photo.folder}"),
            clockAnchors = photoClockAnchors,
            photo = photo,
        )
    }
}
