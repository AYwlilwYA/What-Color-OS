package com.coloros16.liquidglass.hook.aod

import android.os.SystemClock
import android.view.View
import com.coloros16.liquidglass.engine.AodDrift
import com.coloros16.liquidglass.engine.ClockAnchor
import com.coloros16.liquidglass.engine.ClockAnchorMapper
import com.coloros16.liquidglass.engine.ClockPlacementMath
import com.coloros16.liquidglass.engine.OriginLedger
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.WidgetRectPx
import com.coloros16.liquidglass.hook.DesktopClockAnchorBridge
import com.coloros16.liquidglass.hook.LockClockHeightHook
import com.coloros16.liquidglass.log.Lg
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 时钟锚点消费：把采集链算出的三态锚点应用到目标时钟视图。
 *
 * 采集在 [DesktopClockAnchorBridge] / [LockClockHeightHook]，本件只负责落地：
 * 锁屏时钟按 LOCK 锚点、AOD 时钟按 AOD 锚点做平移与缩放，桌面态交给系统。
 */
object ClockAnchorApply {

    private const val TAG = "LgClockApply"
    private const val THROTTLE_MS = 2000L
    private const val EPS = 0.0005f

    private val throttleAt = ConcurrentHashMap<String, Long>()
    private val applied = ConcurrentHashMap<String, FloatArray>()

    /** 记账：写入前的原值，还原时逐项回写（系统时钟未必是 0/1）。 */
    private class Origin(
        val tx: Float,
        val ty: Float,
        val sx: Float,
        val sy: Float,
        val alpha: Float,
        val pivotX: Float,
        val pivotY: Float,
        val rotation: Float,
    )

    private val origins = OriginLedger<Origin>()

    private fun logThrottled(key: String, msg: String) {
        val now = SystemClock.uptimeMillis()
        val last = throttleAt[key]
        if (last != null && now - last < THROTTLE_MS) return
        throttleAt[key] = now
        if (throttleAt.size > 64) throttleAt.clear()
        Lg.i(TAG, "$key: $msg")
    }

    /**
     * 把某态锚点落到视图上：中心对齐 + 等比缩放 + 透明度。
     *
     * 锚点坐标是屏幕归一化值，视图坐标是父容器本地值，需要减掉父容器在屏幕上的偏移。
     */
    fun apply(view: View, state: SceneState, anchor: ClockAnchor?, reason: String) {
        if (anchor == null) {
            logThrottled("apply-null", "锚点为空 state=$state reason=$reason")
            return
        }
        val root = view.rootView ?: return
        val screenW = root.width
        val screenH = root.height
        if (screenW <= 0 || screenH <= 0) {
            logThrottled("apply-screen", "屏幕尺寸未知 ${screenW}x$screenH")
            return
        }
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) {
            logThrottled("apply-size", "时钟未布局 ${w}x$h state=$state")
            return
        }
        val parent = view.parent as? View ?: return
        val parentLoc = IntArray(2)
        parent.getLocationOnScreen(parentLoc)
        val targetCx = anchor.centerX * screenW - parentLoc[0]
        val targetCy = anchor.centerY * screenH - parentLoc[1]
        val dx = targetCx - (view.left + w * 0.5f)
        val dy = targetCy - (view.top + h * 0.5f)
        val scale = anchor.scale.coerceIn(ClockAnchorMapper.SCALE_MIN, ClockAnchorMapper.SCALE_MAX)
        val sx = scale * anchor.stretchX
        val sy = scale * anchor.stretchY

        val rot = anchor.rotationDeg
        val key = keyOf(view, state)
        val prev = applied[key]
        // 去重必须连「视图当前实际属性」一起比：系统转场会改写 translation/scale，
        // 只比我们上次写入的目标值会永久跳过重写，锚点就此丢失。
        if (prev != null &&
            near(prev[0], dx) && near(prev[1], dy) && near(prev[2], sx) &&
            near(prev[3], anchor.alpha) && near(prev[4], sy) && near(prev[5], rot) &&
            near(view.translationX, dx) && near(view.translationY, dy) &&
            near(view.scaleX, sx) && near(view.scaleY, sy) &&
            near(view.alpha, anchor.alpha) && near(view.rotation, rot)
        ) return

        origins.record(
            key,
            Origin(
                view.translationX, view.translationY, view.scaleX, view.scaleY,
                view.alpha, view.pivotX, view.pivotY, view.rotation,
            ),
        )
        view.pivotX = w * 0.5f
        view.pivotY = h * 0.5f
        view.translationX = dx
        view.translationY = dy
        view.scaleX = sx
        view.scaleY = sy
        view.alpha = anchor.alpha.coerceIn(0f, 1f)
        view.rotation = rot
        applied[key] = floatArrayOf(dx, dy, sx, anchor.alpha, sy, rot)
        logThrottled(
            "apply-set",
            String.format(
                Locale.US,
                "state=%s d=(%.1f,%.1f) sx=%.3f sy=%.3f a=%.2f rot=%.2f reason=%s",
                state, dx, dy, sx, sy, anchor.alpha, rot, reason,
            ),
        )
        logGeometry(view, state, anchor, w, h, parentLoc, dx, dy, screenW, screenH, reason)
    }

    /**
     * 落位几何五值：视图左上/尺寸、父容器屏幕偏移、锚点与漂移分量。
     *
     * 有了这五项就能把「屏幕上实际中心」反算出来，与截屏亮像素包围盒对账，
     * 分辨差值来自锚点、父容器偏移还是字形在视图内偏心。
     */
    private fun logGeometry(
        view: View,
        state: SceneState,
        anchor: ClockAnchor,
        w: Int,
        h: Int,
        parentLoc: IntArray,
        dx: Float,
        dy: Float,
        screenW: Int,
        screenH: Int,
        reason: String,
    ) {
        val center = ClockPlacementMath.screenCenter(
            view.left.toFloat(), view.top.toFloat(), w.toFloat(), h.toFloat(),
            parentLoc[0].toFloat(), parentLoc[1].toFloat(), dx, dy,
        )
        val drift = if (state == SceneState.AOD) AodDrift.offsetY(SystemClock.uptimeMillis()) else 0f
        logThrottled(
            "apply-geo",
            String.format(
                Locale.US,
                "state=%s reason=%s lt=(%d,%d) wh=(%dx%d) parent=(%d,%d) anchor=(%.4f,%.4f) " +
                    "drift=%.5f screen=%dx%d center=(%.1f,%.1f) impliedY=%.4f",
                state, reason, view.left, view.top, w, h, parentLoc[0], parentLoc[1],
                anchor.centerX, anchor.centerY, drift, screenW, screenH,
                center[0], center[1],
                ClockPlacementMath.impliedAnchorY(center[1], screenH.toFloat()),
            ),
        )
    }

    private fun near(a: Float, b: Float): Boolean = kotlin.math.abs(a - b) < EPS

    /** 还原：按记账回写原值（含 pivot），只撤本件写过的视图与态。 */
    fun restore(view: View?, state: SceneState) {
        if (view == null) return
        val key = keyOf(view, state)
        applied.remove(key)
        val origin = origins.take(key) ?: return
        view.translationX = origin.tx
        view.translationY = origin.ty
        view.scaleX = origin.sx
        view.scaleY = origin.sy
        view.alpha = origin.alpha
        view.pivotX = origin.pivotX
        view.pivotY = origin.pivotY
        view.rotation = origin.rotation
        logThrottled("apply-restore", "已还原 state=$state ${view.javaClass.simpleName}")
    }

    /** 视图销毁时清账，不再写属性。 */
    fun forget(view: View?) {
        if (view == null) return
        for (key in OriginLedger.keysOf(System.identityHashCode(view))) {
            applied.remove(key)
            origins.forget(key)
        }
    }

    private fun keyOf(view: View, state: SceneState): String =
        OriginLedger.keyOf(System.identityHashCode(view), state)

    /** 桌面态锚点：桌面大时钟小部件优先，其次状态栏时钟，皆无则淡出。 */
    fun homeAnchor(): ClockAnchor =
        DesktopClockAnchorBridge.anchorOrNull() ?: ClockAnchor(alpha = 0f)

    /** 由矩形直接求锚点（供采集方复用同一套换算）。 */
    fun anchorFromRect(rect: WidgetRectPx, screenW: Int, screenH: Int): ClockAnchor? =
        ClockAnchorMapper.fromTargetRect(rect, screenW, screenH, LockClockHeightHook.lastHeightPx())
}
