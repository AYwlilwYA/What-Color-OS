package com.coloros16.liquidglass.hook

import android.view.View
import com.coloros16.liquidglass.log.Lg
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.util.WeakHashMap

/**
 * 状态栏时钟矩形采集（SystemUI 进程）：hook `com.android.systemui.statusbar.policy.Clock`
 * 的 attach / detach / 可见性回调，命中后加布局监听，把矩形直接交给同进程的
 * [DesktopClockAnchorBridge]（锁屏→桌面时钟平移的第二优先级终点）。
 *
 * - 事件驱动、无轮询；同一视图只加一次布局监听。
 * - 全链 fail-open：类/方法解析失败只降级本功能并打日志，不炸宿主。
 */
object StatusBarClockAnchorHook {

    private const val TAG = "LgClockAnchor"
    private const val CLASS_CLOCK = "com.android.systemui.statusbar.policy.Clock"

    private val watched = WeakHashMap<View, Boolean>()
    @Volatile private var installed = false

    fun install(api: XposedInterface, classLoader: ClassLoader) {
        if (installed) return
        val cls = try {
            Class.forName(CLASS_CLOCK, false, classLoader)
        } catch (t: Throwable) {
            Lg.e(TAG, "dc-status 类缺失 ${t.javaClass.simpleName}: ${t.message}", t)
            return
        }
        var hook = 0
        if (mount(api, cls, "onAttachedToWindow")) hook++
        if (mount(api, cls, "onDetachedFromWindow")) hook++
        if (mount(api, cls, "updateClockVisibility")) hook++
        installed = hook > 0
        Lg.i(TAG, "dc-status install hook=$hook/3 installed=$installed")
    }

    private fun mount(api: XposedInterface, cls: Class<*>, name: String): Boolean {
        val m = try {
            cls.getDeclaredMethod(name)
        } catch (t: Throwable) {
            Lg.e(TAG, "dc-status 方法缺失 $name ${t.javaClass.simpleName}: ${t.message}")
            return false
        }
        return try {
            api.hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val result = chain.proceed()
                try {
                    val view = chain.getThisObject() as? View
                    if (view != null) {
                        when (name) {
                            "onDetachedFromWindow" -> DesktopClockAnchorBridge.onStatusBarClock(view, name, hidden = true)
                            "onAttachedToWindow" -> {
                                ensureLayoutWatcher(view)
                                DesktopClockAnchorBridge.onStatusBarClock(view, name)
                            }
                            else -> DesktopClockAnchorBridge.onStatusBarClock(
                                view,
                                name,
                                hidden = !view.isShown || view.width <= 0 || view.height <= 0,
                            )
                        }
                    }
                } catch (t: Throwable) {
                    Lg.e(TAG, "dc-status 回调异常 $name ${t.javaClass.simpleName}: ${t.message}", t)
                }
                result
            }
            Lg.i(TAG, "dc-status mount $CLASS_CLOCK#$name")
            true
        } catch (t: Throwable) {
            Lg.e(TAG, "dc-status mount $name 失败 ${t.javaClass.simpleName}: ${t.message}", t)
            false
        }
    }

    private fun ensureLayoutWatcher(view: View) {
        if (watched.put(view, true) != null) return
        try {
            view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                try {
                    DesktopClockAnchorBridge.onStatusBarClock(v, "layout", hidden = !v.isShown)
                } catch (t: Throwable) {
                    Lg.e(TAG, "dc-status 布局回调异常 ${t.javaClass.simpleName}", t)
                }
            }
        } catch (t: Throwable) {
            Lg.e(TAG, "dc-status 布局监听挂载失败 ${t.javaClass.simpleName}", t)
        }
    }
}
