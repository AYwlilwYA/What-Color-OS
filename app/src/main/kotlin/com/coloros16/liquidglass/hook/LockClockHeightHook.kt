package com.coloros16.liquidglass.hook

import android.os.SystemClock
import android.view.View
import com.coloros16.liquidglass.log.Lg
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * 锁屏时钟高度基准采集（SystemUI 进程）：给 [DesktopClockAnchorBridge] 提供缩放分母。
 *
 * - 入口：`KeyguardStyleClockControllerImpl.onViewAttached/onViewDetached`（C15/C16 同名同签名），
 *   从控制器字段 `keyguardStyleClock` 反射取时钟宿主 View（C15 = OplusKeyguardStyleClock，
 *   C16 = CustomOplusKeyguardStyleClock），挂布局监听读高度。
 * - 只用事件（attach / layout），无轮询；布局监听在 detach 时移除。
 * - 全链 fail-open：类/字段解析失败只降级本采集，打节流日志不炸宿主。
 */
object LockClockHeightHook {

    private const val TAG = "LgClockAnchor"
    private const val THROTTLE_MS = 2000L
    private const val CLASS_CONTROLLER =
        "com.oplus.systemui.keyguard.clockstyle.KeyguardStyleClockControllerImpl"
    private const val METHOD_ATTACHED = "onViewAttached"
    private const val METHOD_DETACHED = "onViewDetached"
    private const val FIELD_CLOCK = "keyguardStyleClock"

    private val throttleAt = ConcurrentHashMap<String, Long>()
    private val listeners = WeakHashMap<View, View.OnLayoutChangeListener>()
    private var clockField: java.lang.reflect.Field? = null

    @Volatile private var installed = false
    @Volatile private var lastHeightPx = 0f
    @Volatile private var lastWidthPx = 0

    private fun logThrottled(key: String, msg: String, intervalMs: Long = THROTTLE_MS) {
        val now = SystemClock.uptimeMillis()
        val last = throttleAt[key]
        if (last != null && now - last < intervalMs) return
        throttleAt[key] = now
        if (throttleAt.size > 128) throttleAt.clear()
        Lg.i(TAG, "$key: $msg")
    }

    fun install(api: XposedInterface, classLoader: ClassLoader) {
        if (installed) return
        var ok = false
        try {
            val controller = Class.forName(CLASS_CONTROLLER, false, classLoader)
            clockField = try {
                controller.getDeclaredField(FIELD_CLOCK).apply { isAccessible = true }
            } catch (t: Throwable) {
                Lg.i(TAG, "dc-lock-height-field 缺失 ${t.javaClass.simpleName}，跳过")
                null
            }
            if (clockField == null) return
            try {
                val attached = controller.getDeclaredMethod(METHOD_ATTACHED)
                api.hook(attached)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val result = chain.proceed()
                        try {
                            bind(chain.getThisObject())
                        } catch (t: Throwable) {
                            logThrottled("dc-lock-height-attach-err", "attach 处理失败 ${t.javaClass.simpleName}")
                        }
                        result
                    }
                ok = true
            } catch (t: Throwable) {
                Lg.e(TAG, "dc-lock-height install $METHOD_ATTACHED 失败 ${t.javaClass.simpleName}: ${t.message}", t)
            }
            try {
                val detached = controller.getDeclaredMethod(METHOD_DETACHED)
                api.hook(detached)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val result = chain.proceed()
                        try {
                            unbind(chain.getThisObject())
                        } catch (t: Throwable) {
                            logThrottled("dc-lock-height-detach-err", "detach 处理失败 ${t.javaClass.simpleName}")
                        }
                        result
                    }
                ok = true
            } catch (t: Throwable) {
                Lg.e(TAG, "dc-lock-height install $METHOD_DETACHED 失败 ${t.javaClass.simpleName}: ${t.message}", t)
            }
        } catch (t: Throwable) {
            Lg.e(TAG, "dc-lock-height install 失败 ${t.javaClass.simpleName}: ${t.message}", t)
        }
        if (ok) {
            installed = true
            Lg.i(TAG, "dc-lock-height 已挂载")
        }
    }

    /** 最近一次采到的锁屏时钟宿主高度（像素；0 = 未知）。 */
    fun lastHeightPx(): Float = lastHeightPx

    private fun bind(controller: Any?) {
        if (controller == null) return
        val view = try {
            clockField?.get(controller) as? View
        } catch (t: Throwable) {
            null
        }
        if (view == null) {
            logThrottled("dc-lock-height-noview", "控制器未持有时钟视图")
            return
        }
        if (listeners.containsKey(view)) {
            measure(view, "attach-repeat")
            return
        }
        val listener = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            try {
                measure(v, "layout")
            } catch (t: Throwable) {
                logThrottled("dc-lock-height-layout-err", "布局回调失败 ${t.javaClass.simpleName}")
            }
        }
        listeners[view] = listener
        view.addOnLayoutChangeListener(listener)
        measure(view, "attach")
    }

    private fun unbind(controller: Any?) {
        if (controller == null) return
        val view = try {
            clockField?.get(controller) as? View
        } catch (t: Throwable) {
            null
        } ?: return
        listeners.remove(view)?.let { view.removeOnLayoutChangeListener(it) }
    }

    private fun measure(view: View, reason: String) {
        val h = view.height
        val w = view.width
        if (h <= 0 || w <= 0) {
            logThrottled("dc-lock-height-zero", "时钟未布局 ${w}x$h reason=$reason")
            return
        }
        if (h.toFloat() == lastHeightPx && w == lastWidthPx) return
        lastHeightPx = h.toFloat()
        lastWidthPx = w
        DesktopClockAnchorBridge.setLockClockHeightPx(h.toFloat())
        logThrottled(
            "dc-lock-height-set",
            String.format(Locale.US, "锁屏时钟宿主=%dx%d reason=%s", w, h, reason),
        )
    }
}
