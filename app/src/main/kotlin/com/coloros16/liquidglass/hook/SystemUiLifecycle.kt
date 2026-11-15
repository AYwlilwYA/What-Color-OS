package com.coloros16.liquidglass.hook

import android.os.SystemClock
import com.coloros16.liquidglass.log.Lg
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * SystemUI 装载点：等应用初始化完成后再装 hook。
 *
 * COS17 的生命周期 owner 是 `application.impl.SystemUIApplicationImpl`，旧名 `SystemUIApplication`
 * 作 C16 兜底；原 `onCreate` 先 `proceed()` 走完（宿主异常/返回值原样保留），成功后才安装。
 */
object SystemUiLifecycle {
    private const val TAG = "lg-lifecycle"
    private const val THROTTLE_MS = 2000L

    private val gate = SystemUiInstallGate()
    private var mounted = false
    private val throttleAt = ConcurrentHashMap<String, Long>()

    /** 解析 SystemUI 应用 onCreate()V：先新名后旧名，都没有则抛出。 */
    fun resolveOnCreate(loader: ClassLoader): Method {
        for (name in arrayOf(
            "com.android.systemui.application.impl.SystemUIApplicationImpl",
            "com.android.systemui.SystemUIApplication",
        )) {
            try {
                val method = Class.forName(name, false, loader).getDeclaredMethod("onCreate")
                if (method.returnType == Void.TYPE) return method
            } catch (_: ClassNotFoundException) {
            } catch (_: NoSuchMethodException) {
            }
        }
        throw NoSuchMethodException("未找到 SystemUI 应用 onCreate()V")
    }

    /**
     * 挂载 SystemUI 装载点（幂等；解析失败不锁死，下次调用可重试）。
     *
     * @param doInstall 应用初始化完成后的真正安装动作（第二阶段由入口注入）
     * @return true=装载点已挂上
     */
    fun install(api: XposedInterface, loader: ClassLoader, doInstall: () -> Unit): Boolean {
        if (mounted) {
            logThrottled("lifecycle-mounted", "装载点已挂载，跳过重复安装")
            return true
        }
        val method = try {
            resolveOnCreate(loader)
        } catch (t: Throwable) {
            logThrottled("lifecycle-resolve-miss", "装载点未匹配，保持可重试", t)
            return false
        }
        return try {
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    try {
                        gate.installOnce { doInstall() }
                    } catch (t: Throwable) {
                        logThrottled("lifecycle-install-fail", "初始化后安装失败", t)
                    }
                    result
                }
            mounted = true
            Lg.i(TAG, "[lifecycle-mounted] 装载点已挂载 ${method.declaringClass.name}#onCreate")
            true
        } catch (t: Throwable) {
            logThrottled("lifecycle-hook-fail", "装载点 hook 失败，保持可重试", t)
            false
        }
    }

    /** 节流诊断日志：同 key 2s 内只打一条，key 回显进打印文本。 */
    private fun logThrottled(key: String, msg: String, tr: Throwable? = null) {
        val now = SystemClock.uptimeMillis()
        val last = throttleAt[key]
        if (last != null && now - last < THROTTLE_MS) return
        throttleAt[key] = now
        if (throttleAt.size > 64) throttleAt.clear()
        if (tr != null) Lg.w(TAG, "[$key] $msg", tr) else Lg.i(TAG, "[$key] $msg")
    }
}

/** 启动流程可能多次通知；失败的安装保持可重试（不锁死门控）。 */
class SystemUiInstallGate {
    private var installed = false
    private var installing = false

    @Synchronized
    fun installOnce(install: () -> Unit): Boolean {
        if (installed || installing) return false
        installing = true
        return try {
            install()
            installed = true
            true
        } finally {
            installing = false
        }
    }
}
