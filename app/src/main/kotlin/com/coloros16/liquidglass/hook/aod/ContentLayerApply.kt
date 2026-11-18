package com.coloros16.liquidglass.hook.aod

import android.os.SystemClock
import android.view.View
import com.coloros16.liquidglass.engine.ContentLayerMapper
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.log.Lg
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 内容层变换落地：把引擎分派好的三态姿态写到对应容器上。
 *
 * 只写 alpha / scale / translation 四项，pivot 固定容器中心；
 * 不参与的态一律不碰，撤效果时按记账还原（只还原自己写过的）。
 */
object ContentLayerApply {

    private const val TAG = "LgContentApply"
    private const val THROTTLE_MS = 2000L
    private const val EPS = 0.0005f
    private const val PX_EPS = 0.25f

    private val throttleAt = ConcurrentHashMap<String, Long>()

    /** 记账：视图身份 → 本件写过的原值，用于精确还原。 */
    private class Origin(
        val alpha: Float,
        val sx: Float,
        val sy: Float,
        val tx: Float,
        val ty: Float,
        val pivotX: Float,
        val pivotY: Float,
    )

    private val origins = HashMap<Int, Origin>()
    private val last = HashMap<Int, FloatArray>()

    private fun logThrottled(key: String, msg: String) {
        val now = SystemClock.uptimeMillis()
        val prev = throttleAt[key]
        if (prev != null && now - prev < THROTTLE_MS) return
        throttleAt[key] = now
        if (throttleAt.size > 64) throttleAt.clear()
        Lg.i(TAG, "$key: $msg")
    }

    /**
     * 应用一个态的姿态。
     *
     * identity 或数值中性时走还原路径，保证转场结束后容器回到系统原样。
     */
    fun apply(view: View?, state: SceneState, pose: ContentLayerMapper.Pose) {
        if (view == null) {
            logThrottled("content-noview", "容器为空 state=$state")
            return
        }
        if (pose.identity || ContentLayerMapper.isNeutral(pose)) {
            restore(view, state)
            return
        }
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) {
            logThrottled("content-size", "容器未布局 ${w}x$h state=$state")
            return
        }
        val key = System.identityHashCode(view)
        val next = floatArrayOf(pose.alpha, pose.scale, pose.dx, pose.dy)
        val prev = last[key]
        if (prev != null &&
            kotlin.math.abs(prev[0] - next[0]) < EPS &&
            kotlin.math.abs(prev[1] - next[1]) < EPS &&
            kotlin.math.abs(prev[2] - next[2]) < PX_EPS &&
            kotlin.math.abs(prev[3] - next[3]) < PX_EPS
        ) return

        if (!origins.containsKey(key)) {
            origins[key] = Origin(
                view.alpha, view.scaleX, view.scaleY, view.translationX, view.translationY,
                view.pivotX, view.pivotY,
            )
        }
        view.pivotX = w * 0.5f
        view.pivotY = h * 0.5f
        view.alpha = pose.alpha
        view.scaleX = pose.scale
        view.scaleY = pose.scale
        view.translationX = pose.dx
        view.translationY = pose.dy
        last[key] = next
        logThrottled(
            "content-set-$state",
            String.format(
                Locale.US,
                "a=%.3f s=%.3f d=(%.1f,%.1f) view=%s",
                pose.alpha, pose.scale, pose.dx, pose.dy, view.javaClass.simpleName,
            ),
        )
    }

    /** 还原：只撤本件写过的属性，回到写入前的原值。 */
    fun restore(view: View?, state: SceneState) {
        if (view == null) return
        val key = System.identityHashCode(view)
        val origin = origins.remove(key) ?: return
        last.remove(key)
        view.alpha = origin.alpha
        view.scaleX = origin.sx
        view.scaleY = origin.sy
        view.translationX = origin.tx
        view.translationY = origin.ty
        view.pivotX = origin.pivotX
        view.pivotY = origin.pivotY
        logThrottled("content-restore-$state", "已还原 ${view.javaClass.simpleName}")
    }

    /** 舞台摘除时清账（视图已销毁，不再写属性）。 */
    fun forget(view: View?) {
        if (view == null) return
        val key = System.identityHashCode(view)
        origins.remove(key)
        last.remove(key)
    }
}