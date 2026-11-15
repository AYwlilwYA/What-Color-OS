package com.coloros16.liquidglass.hook

import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.hook.statusbar.StatusBarCompat
import com.coloros16.liquidglass.hook.statusbar.StatusBarConfig
import com.coloros16.liquidglass.hook.statusbar.StatusBarDuoHost
import com.coloros16.liquidglass.hook.statusbar.StatusBarEnforcer
import com.coloros16.liquidglass.hook.statusbar.StatusBarSettings
import com.coloros16.liquidglass.log.Lg
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 状态栏 hook 线唯一入口。
 *
 * 只装三个观察点，行为全部落在 [StatusBarEnforcer]：
 * - CollapsedStatusBarFragment.onViewCreated → 状态栏根视图到手（通知图标/边距）
 * - StatusIconContainer.onLayout → 系统每次摆位后幂等纠正（复合视图内部元素显隐）
 * - StatusIconContainer.onViewAdded → 新图标进来时合并补一次
 *
 * 全链 fail-open：任一 hook 装不上只降级对应功能；回调内任何异常只打节流日志。
 * 日志 tag = `LgStatusBar`；计数 key（sb-hit-*）不带过滤条件，用于确认 hook 真被调用。
 */
object StatusBarHook {

    private const val TAG = "LgStatusBar"
    private const val THROTTLE_MS = 2000L

    private val throttleAt = ConcurrentHashMap<String, Long>()
    private val viewCreatedHits = AtomicLong()
    private val layoutHits = AtomicLong()
    private val addedHits = AtomicLong()
    private val applyErrors = AtomicLong()
    private val pendingApply = AtomicBoolean(false)

    private var api: XposedInterface? = null

    @Volatile
    private var installed = false

    @Volatile
    private var lastEnabled = false

    /** 装载：三个 hook 各自独立 try，一个失败其它照装。 */
    fun install(api: XposedInterface, classLoader: ClassLoader) {
        if (installed) {
            logThrottled("sb-install-dup", "sb-install 已装载，跳过")
            return
        }
        this.api = api
        val gates = StatusBarCompat.gate(classLoader)
        val profile = gates.structure.profile
        var hook = 0
        if (profile.hasFragmentEntry) {
            hook += hookViewCreated(api, classLoader)
        } else {
            logThrottled("sb-install-no-fragment", "sb-install 跳过 viewCreated（档位无 Fragment 入口，根视图走上溯）")
        }
        hook += hookContainerLayout(api, classLoader)
        hook += hookContainerAdded(api, classLoader)
        hook += hookDarkDispatcher(api, classLoader)
        installed = hook > 0
        try {
            StatusBarEnforcer.attachOrderPrefs(Prefs.read(api))
        } catch (t: Throwable) {
            Lg.i(TAG, "sb-install 顺序回传通道装载失败 ${t.javaClass.simpleName}")
        }
        val cfg = StatusBarSettings.snapshot(api)
        lastEnabled = cfg.enabled
        Lg.i(
            TAG,
            "sb-install hook=$hook/4 installed=$installed gate=${cfg.enabled} any=${cfg.anyFeature} " +
                "fragmentEntry=${profile.hasFragmentEntry} clockSec=${profile.hasClockSeconds} " +
                "gates=${gates.summary()}",
        )
    }

    private fun hookViewCreated(api: XposedInterface, cl: ClassLoader): Int = try {
        val cls = Class.forName(StatusBarCompat.FQCN_FRAGMENT, false, cl)
        val method = cls.getDeclaredMethod("onViewCreated", View::class.java, Bundle::class.java)
        api.hook(method)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                viewCreatedHits.incrementAndGet()
                try {
                    val root = chain.getArg(0) as? View
                    if (root != null) {
                        withConfig { cfg -> StatusBarEnforcer.onStatusBarView(root, cfg) }
                    }
                } catch (t: Throwable) {
                    applyErrors.incrementAndGet()
                    logThrottled("sb-viewcreated-err", "sb-skip viewCreated ${t.javaClass.simpleName}")
                }
                report("sb-hit-viewcreated", viewCreatedHits.get())
                result
            }
        1
    } catch (t: Throwable) {
        Lg.e(TAG, "sb-install onViewCreated 失败 ${t.javaClass.simpleName}: ${t.message}", t)
        0
    }

    private fun hookContainerLayout(api: XposedInterface, cl: ClassLoader): Int = try {
        val cls = Class.forName(StatusBarCompat.FQCN_ICON_CONTAINER, false, cl)
        val method = cls.getDeclaredMethod(
            "onLayout",
            Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        api.hook(method)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                layoutHits.incrementAndGet()
                try {
                    val container = chain.getThisObject() as? View
                    if (container is ViewGroup) StatusBarEnforcer.publishOrder(container)
                    if (container != null) {
                        withConfig { cfg -> StatusBarEnforcer.onContainerLayout(container, cfg) }
                    }
                } catch (t: Throwable) {
                    applyErrors.incrementAndGet()
                    logThrottled("sb-layout-err", "sb-skip layout ${t.javaClass.simpleName}")
                }
                report("sb-hit-layout", layoutHits.get())
                result
            }
        1
    } catch (t: Throwable) {
        Lg.e(TAG, "sb-install onLayout 失败 ${t.javaClass.simpleName}: ${t.message}", t)
        0
    }

    private fun hookContainerAdded(api: XposedInterface, cl: ClassLoader): Int = try {
        val cls = Class.forName(StatusBarCompat.FQCN_ICON_CONTAINER, false, cl)
        val method = cls.getDeclaredMethod("onViewAdded", View::class.java)
        api.hook(method)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                addedHits.incrementAndGet()
                try {
                    val container = chain.getThisObject() as? View
                    if (container is ViewGroup) scheduleApply(container)
                } catch (t: Throwable) {
                    applyErrors.incrementAndGet()
                    logThrottled("sb-added-err", "sb-skip viewAdded ${t.javaClass.simpleName}")
                }
                report("sb-hit-added", addedHits.get())
                result
            }
        1
    } catch (t: Throwable) {
        Lg.e(TAG, "sb-install onViewAdded 失败 ${t.javaClass.simpleName}: ${t.message}", t)
        0
    }

    /** 捕获暗色分发实例：三合一自绘视图据此注册 tint 接收器。 */
    private fun hookDarkDispatcher(api: XposedInterface, cl: ClassLoader): Int = try {
        val cls = Class.forName(StatusBarCompat.FQCN_DARK_DISPATCHER, false, cl)
        val ctors = cls.declaredConstructors
        if (ctors.isEmpty()) {
            0
        } else {
            var ok = 0
            for (ctor in ctors) {
                try {
                    ctor.isAccessible = true
                    api.hook(ctor)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            val result = chain.proceed()
                            try {
                                chain.getThisObject()?.let { StatusBarDuoHost.onDispatcher(it) }
                            } catch (t: Throwable) {
                                applyErrors.incrementAndGet()
                                logThrottled("sb-dispatcher-err", "sb-skip dispatcher ${t.javaClass.simpleName}")
                            }
                            result
                        }
                    ok++
                } catch (t: Throwable) {
                    Lg.e(TAG, "sb-install dispatcher ctor 失败 ${t.javaClass.simpleName}: ${t.message}", t)
                }
            }
            if (ok > 0) 1 else 0
        }
    } catch (t: Throwable) {
        Lg.e(TAG, "sb-install darkDispatcher 失败 ${t.javaClass.simpleName}: ${t.message}", t)
        0
    }

    /** 新子 View 到达时合并成一次异步应用（避免在布局过程中改结构/可见性）。 */
    private fun scheduleApply(container: View) {
        if (!pendingApply.compareAndSet(false, true)) return
        try {
            container.post {
                pendingApply.set(false)
                val target = container
                withConfig { cfg -> StatusBarEnforcer.onChildAdded(target, cfg) }
            }
        } catch (t: Throwable) {
            pendingApply.set(false)
            logThrottled("sb-post-err", "sb-skip post ${t.javaClass.simpleName}")
        }
    }

    /**
     * 统一门控：每次回调都按 500ms TTL 重读配置；关闸时只还原一次。
     * 读键失败保持上次快照（fail-open 不误关用户功能）。
     */
    private fun withConfig(block: (StatusBarConfig) -> Unit) {
        val cfg = StatusBarSettings.snapshot(api)
        if (!cfg.enabled) {
            if (lastEnabled) {
                lastEnabled = false
                StatusBarEnforcer.restoreAll()
            }
            return
        }
        lastEnabled = true
        try {
            block(cfg)
        } catch (t: Throwable) {
            applyErrors.incrementAndGet()
            logThrottled("sb-apply-err", "sb-skip apply ${t.javaClass.simpleName}")
        }
    }

    private fun report(key: String, count: Long) {
        logThrottled(
            key,
            "$key total=$count err=${applyErrors.get()} " +
                "layout=${layoutHits.get()} added=${addedHits.get()} applied=${StatusBarEnforcer.isApplied()}",
        )
    }

    /** 节流诊断日志：同 key 2s 一条；key 回显进打印文本（只进节流表按 key grep 恒 0）。 */
    private fun logThrottled(key: String, msg: String) {
        val now = SystemClock.uptimeMillis()
        val last = throttleAt[key]
        if (last != null && now - last < THROTTLE_MS) return
        throttleAt[key] = now
        if (throttleAt.size > 256) throttleAt.clear()
        Lg.i(TAG, msg)
    }
}
