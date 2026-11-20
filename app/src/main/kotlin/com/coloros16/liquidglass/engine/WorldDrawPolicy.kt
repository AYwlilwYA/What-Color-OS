package com.coloros16.liquidglass.engine

/**
 * 世界层绘制门：决定这一帧要不要画壁纸/世界层。
 *
 * 息屏根里只有我们的层，静止期也要画；锁屏面挂在窗口根上时，
 * 静止期若照样画就会盖住锁屏内容 —— 那里只允许转场期画。
 */
object WorldDrawPolicy {

    fun allowWorld(active: Boolean, surface: SceneState): Boolean =
        active || surface == SceneState.AOD
}
