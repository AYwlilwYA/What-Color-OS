package com.coloros16.liquidglass.ui.aod

import com.coloros16.liquidglass.engine.ClockAnchor
import com.coloros16.liquidglass.engine.HandPaintSource
import com.coloros16.liquidglass.engine.PhotoStyle
import com.coloros16.liquidglass.engine.PhotoStyleMotion
import com.coloros16.liquidglass.engine.SceneContract
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.SailLensMotion
import com.coloros16.liquidglass.engine.SystemSceneMotion
import com.coloros16.liquidglass.engine.HomeTransitionOverride
import com.coloros16.liquidglass.engine.TransitionEdge
import com.coloros16.liquidglass.engine.effect.EffectSettings

/** 连续场景样式：一份契约覆盖三态，u=0 息屏、0.5 锁屏、1 桌面。 */
class AodSceneSpec(
    val contract: SceneContract,
    val clockAnchors: Map<SceneState, ClockAnchor>,
    val lens: Boolean = false,
    val photo: PhotoStyle? = null,
    val sky: Boolean = false,
    val handPaintStyle: Int = 0,
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

    /** 手绘：花在中心偏上，时钟落在花下方，展开后与锁屏同位。 */
    private val handPaintClockAnchors = mapOf(
        SceneState.AOD to ClockAnchor(centerX = 0.50f, centerY = 0.72f, scale = 0.86f),
        SceneState.LOCK to ClockAnchor(centerX = 0.36f, centerY = 0.155f, scale = 0.62f),
        SceneState.HOME to ClockAnchor(centerX = 0.42f, centerY = 0.105f, scale = 0.46f, alpha = 0f),
    )

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
        if (handPaintOf(styleId) > 0) return SystemSceneMotion.durationOf(edge)
        val photo = photoOf(styleId) ?: return SystemSceneMotion.durationOf(edge)
        return PhotoStyleMotion.durationMs(photo, edge)
    }

    /** 桌面过渡接管时用选项时长，否则按样式真值；作用域与判定走共用策略件。 */
    fun durationOf(styleId: String, edge: TransitionEdge, settings: EffectSettings): Long {
        val override = HomeTransitionOverride.durationMs(edge, settings)
        if (override > 0L) return override
        return durationOf(styleId, edge)
    }

    /** 手绘款号：增强版完整重放，轨迹运行时从设备息屏应用取。 */
    fun handPaintOf(styleId: String): Int =
        if (styleId == AodStyleCatalog.ID_HANDPAINT) HandPaintSource.DEFAULT_STYLE else 0

    fun forStyle(styleId: String): AodSceneSpec? {
        if (styleId == AodStyleCatalog.ID_SAIL) {
            return AodSceneSpec(sailContract, sailClockAnchors, lens = true)
        }
        val handPaint = handPaintOf(styleId)
        if (handPaint > 0) {
            return AodSceneSpec(
                contract = SceneContract("aod.handpaint.$handPaint"),
                clockAnchors = handPaintClockAnchors,
                handPaintStyle = handPaint,
            )
        }
        val photo = photoOf(styleId) ?: return null
        return AodSceneSpec(
            contract = SceneContract("aod.photo.${photo.folder}"),
            clockAnchors = photoClockAnchors,
            photo = photo,
        )
    }
}
