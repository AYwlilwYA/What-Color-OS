package com.coloros16.liquidglass.hook

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.coloros16.liquidglass.log.Lg
import java.lang.reflect.Proxy

/**
 * 前台应用监听：ITaskStackListener 动态 Proxy 注册（系统隐藏 API 反射，事件驱动零轮询）。
 * onTaskMovedToFront/onTaskStackChanged → 读顶部任务包名 → 回调；供「进来源应用隐藏岛」消费。
 * 注册失败静默降级（不读前台=不隐藏，不炸宿主）。
 */
object SeedlingForegroundSource {

    private const val TAG = "LiquidGlass"

    @Volatile
    var onForegroundChanged: ((String?) -> Unit)? = null

    @Volatile
    private var installed = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var listenerProxy: Any? = null
    private var taskService: Any? = null

    @Volatile
    var currentPackage: String? = null
        private set

    fun install(ctx: Context) {
        if (installed) return
        try {
            val listenerCls = Class.forName("android.app.ITaskStackListener")
            val atmCls = Class.forName("android.app.ActivityTaskManager")
            val service = atmCls.getMethod("getService").invoke(null)
            if (service == null) {
                Lg.w(TAG, "seedling-foreground: task service unavailable")
                return
            }
            taskService = service
            val handler = java.lang.reflect.InvocationHandler { proxy, method, args ->
                try {
                    when (method.name) {
                        "onTaskMovedToFront", "onTaskStackChanged", "onTaskRemoved" -> {
                            mainHandler.post { refreshTopPackage() }
                            null
                        }
                        "asBinder" -> proxy
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.getOrNull(0)
                        "toString" -> "lg-seedling-task-listener"
                        "getInterfaceDescriptor" -> "android.app.ITaskStackListener"
                        else -> null
                    }
                } catch (t: Throwable) {
                    Lg.w(TAG, "seedling-foreground: listener ${method.name} error: ${t.message}")
                    when (method.name) {
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> false
                        "toString" -> "lg-seedling-task-listener"
                        else -> null
                    }
                }
            }
            val proxy = Proxy.newProxyInstance(listenerCls.classLoader, arrayOf(listenerCls), handler)
            listenerProxy = proxy
            service.javaClass.getMethod("registerTaskStackListener", listenerCls).invoke(service, proxy)
            installed = true
            Lg.i(TAG, "seedling-foreground: task stack listener registered (event-driven)")
            refreshTopPackage()
        } catch (t: Throwable) {
            Lg.w(TAG, "seedling-foreground: register skipped (${t.javaClass.simpleName}: ${t.message})")
        }
    }

    private fun refreshTopPackage() {
        try {
            val atmCls = Class.forName("android.app.ActivityTaskManager")
            val service = atmCls.getMethod("getService").invoke(null) ?: return
            // getTasks 是服务端方法（公开 SDK 的 ActivityTaskManager.getTasks 走实例包装，服务端直调）
            val getTasks = service.javaClass.getMethod("getTasks", Int::class.javaPrimitiveType)
            @Suppress("UNCHECKED_CAST")
            val tasks = getTasks.invoke(service, 1) as? List<android.app.ActivityManager.RunningTaskInfo>
            val top = tasks?.firstOrNull()
            val pkg = top?.topActivity?.packageName ?: top?.baseIntent?.component?.packageName
            if (pkg != currentPackage) {
                currentPackage = pkg
                Lg.i(TAG, "seedling-foreground: top=$pkg")
                onForegroundChanged?.invoke(pkg)
            }
        } catch (t: Throwable) {
            Lg.w(TAG, "seedling-foreground: read top failed: ${t.message}")
        }
    }

    fun shutdown() {
        try {
            val proxy = listenerProxy
            val service = taskService
            if (proxy != null && service != null) {
                val listenerCls = Class.forName("android.app.ITaskStackListener")
                service.javaClass.getMethod("unregisterTaskStackListener", listenerCls).invoke(service, proxy)
            }
        } catch (_: Throwable) {
        }
        listenerProxy = null
        taskService = null
        currentPackage = null
        installed = false
    }
}
