package com.coloros16.liquidglass.wallpaper

import android.content.Context
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.engine.CameraPath
import com.coloros16.liquidglass.engine.CameraPaths
import com.coloros16.liquidglass.log.Lg
import java.io.File

/** 壁纸进程读取配置的集中入口，带越界夹紧与损坏回退。 */
object WallpaperSettings {

    const val TAG = "lg-wallpaper"

    const val MODE_IMAGE = "image"
    const val MODE_VIDEO = "video"
    const val MODE_EFFECT = "effect"

    const val EFFECT_AURORA = "aurora"
    const val EFFECT_STARFIELD = "starfield"
    const val EFFECT_FLOW = "flow"

    /** 三态场景 id：空串表示仍走相机曲线。 */
    const val SCENE_NONE = ""
    const val SCENE_SNOW_MOUNTAIN = "snowmountain"

    fun validScene(id: String?): Boolean = id == SCENE_SNOW_MOUNTAIN

    data class Scene3D(
        val curveId: String,
        val customText: String?,
        val camera: CameraPath,
        val cycleMs: Long,
        val speed: Float,
        val parallax: Float,
        val sceneId: String = SCENE_NONE,
    )

    data class Scene2D(
        val mode: String,
        val effect: String,
        val sourcePath: String?,
        val parallax: Float,
    )

    fun read3D(context: Context): Scene3D {
        val prefs = try {
            Prefs.read(context)
        } catch (t: Throwable) {
            Lg.e(TAG, "read3d prefs failed", t)
            return defaults3D()
        }
        val curveId = prefs.getString(Prefs.KEY_WALLPAPER_3D_CURVE, Prefs.DEFAULT_WALLPAPER_3D_CURVE)
            ?.takeIf { it in CameraPaths.presetIds || it == CameraPaths.CUSTOM }
            ?: Prefs.DEFAULT_WALLPAPER_3D_CURVE
        val customText = prefs.getString(Prefs.KEY_WALLPAPER_3D_CUSTOM, null)
        val cycle = prefs.getInt(Prefs.KEY_WALLPAPER_3D_CYCLE_MS, Prefs.DEFAULT_WALLPAPER_3D_CYCLE_MS)
            .coerceIn(Prefs.WALLPAPER_3D_CYCLE_MIN_MS, Prefs.WALLPAPER_3D_CYCLE_MAX_MS).toLong()
        val speed = prefs.getFloat(Prefs.KEY_WALLPAPER_3D_SPEED, Prefs.DEFAULT_WALLPAPER_3D_SPEED)
            .coerceIn(Prefs.WALLPAPER_3D_SPEED_MIN, Prefs.WALLPAPER_3D_SPEED_MAX)
        val parallax = prefs.getFloat(Prefs.KEY_WALLPAPER_3D_PARALLAX, Prefs.DEFAULT_WALLPAPER_3D_PARALLAX)
            .coerceIn(0f, 1f)
        val camera = if (curveId == CameraPaths.CUSTOM) {
            CameraPaths.custom(customText) ?: CameraPaths.orbit()
        } else {
            CameraPaths.byId(curveId)
        }
        val sceneRaw = prefs.getString(Prefs.KEY_WALLPAPER_3D_SCENE, Prefs.DEFAULT_WALLPAPER_3D_SCENE)
        val sceneId = if (validScene(sceneRaw)) sceneRaw!! else SCENE_NONE
        return Scene3D(curveId, customText, camera, cycle, speed, parallax, sceneId)
    }

    fun read2D(context: Context): Scene2D {
        val prefs = try {
            Prefs.read(context)
        } catch (t: Throwable) {
            Lg.e(TAG, "read2d prefs failed", t)
            return defaults2D()
        }
        val mode = prefs.getString(Prefs.KEY_WALLPAPER_2D_MODE, Prefs.DEFAULT_WALLPAPER_2D_MODE)
            ?.takeIf { it == MODE_IMAGE || it == MODE_VIDEO || it == MODE_EFFECT }
            ?: Prefs.DEFAULT_WALLPAPER_2D_MODE
        val effect = prefs.getString(Prefs.KEY_WALLPAPER_2D_EFFECT, Prefs.DEFAULT_WALLPAPER_2D_EFFECT)
            ?.takeIf { it == EFFECT_AURORA || it == EFFECT_STARFIELD || it == EFFECT_FLOW }
            ?: Prefs.DEFAULT_WALLPAPER_2D_EFFECT
        val path = prefs.getString(Prefs.KEY_WALLPAPER_2D_SOURCE, null)
        val parallax = prefs.getFloat(Prefs.KEY_WALLPAPER_2D_PARALLAX, Prefs.DEFAULT_WALLPAPER_2D_PARALLAX)
            .coerceIn(0f, 1f)
        return Scene2D(mode, effect, path?.takeIf { File(it).isFile }, parallax)
    }

    fun defaults3D(): Scene3D = Scene3D(
        curveId = Prefs.DEFAULT_WALLPAPER_3D_CURVE,
        customText = null,
        camera = CameraPaths.orbit(),
        cycleMs = Prefs.DEFAULT_WALLPAPER_3D_CYCLE_MS.toLong(),
        speed = Prefs.DEFAULT_WALLPAPER_3D_SPEED,
        parallax = Prefs.DEFAULT_WALLPAPER_3D_PARALLAX,
        sceneId = SCENE_NONE,
    )

    fun defaults2D(): Scene2D = Scene2D(
        mode = Prefs.DEFAULT_WALLPAPER_2D_MODE,
        effect = Prefs.DEFAULT_WALLPAPER_2D_EFFECT,
        sourcePath = null,
        parallax = Prefs.DEFAULT_WALLPAPER_2D_PARALLAX,
    )
}
