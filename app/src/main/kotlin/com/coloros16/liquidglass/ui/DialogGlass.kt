package com.coloros16.liquidglass.ui

import android.app.Dialog
import android.view.View
import android.view.ViewTreeObserver
import com.coloros16.liquidglass.App
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.log.Lg
import com.coloros16.liquidglass.sf.SfGlassChannel

/**
 * 设置页 Dialog 玻璃：show 后把 Dialog 窗口几何认领给 SF 通道画玻璃，
 * 窗口销毁时撤层。门控 = 渲染模式 SF 档 + 玻璃总开关开 + 框架已就绪；
 * 叠开引用计数，最后一个 Dialog 关闭才真正撤层。
 */
object DialogGlass {
    private const val TAG = "DialogGlass"

    private var activeCount = 0

    /** Dialog show 之后接线；门控不满足只记日志返回。 */
    fun arm(dialog: Dialog) {
        val prefs = App.serviceRef?.let { service ->
            try {
                Prefs.remote(service)
            } catch (t: Throwable) {
                Lg.w(TAG, "prefs read failed: ${t.javaClass.simpleName}: ${t.message}")
                null
            }
        }
        if (prefs == null) {
            Lg.i(TAG, "skip: framework not ready")
            return
        }
        val mode = prefs.getString(Prefs.KEY_RENDER_MODE, Prefs.DEFAULT_RENDER_MODE)
            ?: Prefs.DEFAULT_RENDER_MODE
        if (mode != Prefs.RENDER_MODE_SF) {
            Lg.i(TAG, "skip: mode=$mode")
            return
        }
        if (!prefs.getBoolean(Prefs.KEY_GLASS_ENABLE, Prefs.DEFAULT_GLASS_ENABLE)) {
            Lg.i(TAG, "skip: glass switch off")
            return
        }
        val decor = dialog.window?.decorView
        if (decor == null) {
            Lg.i(TAG, "skip: no decor view")
            return
        }
        activeCount++
        val id = System.identityHashCode(dialog)
        declareWhenLaidOut(decor, id)
        armWithdraw(decor)
        Lg.i(TAG, "armed id=$id count=$activeCount")
    }

    /** 等首帧布局完成（拿到真实尺寸与屏幕位置）后申报一次。 */
    private fun declareWhenLaidOut(decor: View, id: Int) {
        if (decor.width > 0 && decor.height > 0) {
            declare(decor, id)
            return
        }
        lateinit var listener: ViewTreeObserver.OnGlobalLayoutListener
        listener = ViewTreeObserver.OnGlobalLayoutListener {
            if (decor.width > 0 && decor.height > 0) {
                decor.viewTreeObserver.removeOnGlobalLayoutListener(listener)
                declare(decor, id)
            }
        }
        decor.viewTreeObserver.addOnGlobalLayoutListener(listener)
    }

    /** 屏幕坐标认领：claimForDraw 内部换算窗口内坐标并走 250ms 节流下发。 */
    private fun declare(decor: View, id: Int) {
        try {
            val loc = intArrayOf(0, 0)
            decor.getLocationOnScreen(loc)
            val w = decor.width
            val h = decor.height
            val corner = minOf(w, h) / 8f
            SfGlassChannel.claimForDraw(
                id,
                loc[0].toFloat(), loc[1].toFloat(),
                w.toFloat(), h.toFloat(),
                corner,
                host = decor,
            )
            Lg.i(TAG, "declare id=$id pos=(${loc[0]},${loc[1]}) size=${w}x$h corner=$corner")
        } catch (t: Throwable) {
            Lg.w(TAG, "declare failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** 窗口销毁撤层；不用 Dialog 的 dismiss listener 以免覆盖调用方自己的监听。 */
    private fun armWithdraw(decor: View) {
        decor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit

            override fun onViewDetachedFromWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                withdraw()
            }
        })
    }

    private fun withdraw() {
        activeCount = (activeCount - 1).coerceAtLeast(0)
        if (activeCount == 0) {
            SfGlassChannel.sessionGlassOff("dialog-dismiss")
            Lg.i(TAG, "withdraw: last dialog closed, retract")
        } else {
            Lg.i(TAG, "withdraw: $activeCount dialog(s) still open")
        }
    }
}
