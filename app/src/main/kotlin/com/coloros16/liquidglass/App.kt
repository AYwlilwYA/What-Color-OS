package com.coloros16.liquidglass

import android.app.Application
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.log.Lg
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 模块 Application：接入 LibXposed 服务，向框架索要配置读写通道。
 *
 * 为什么需要：模块 UI（SettingsActivity，模块自身进程）与 hook 端（SystemUI 进程）分属两个进程。
 * 配置必须写入【框架侧存储】（LibXposed 的 getRemotePreferences，由 LSPosed 等框架落在其内部数据库），
 * hook 端才能经 XposedInterface.getRemotePreferences 读到；直接写本进程 SharedPreferences(XML) 两端不通
 * （见 config/Prefs.kt 注释与 doc/spec/04-技术方案.md 的配置读写模型）。
 *
 * 机制：manifest 由 libxposed.service 自带 XposedProvider（authorities 为
 * "${applicationId}.XposedService"），框架在模块进程启动时经 ContentProvider.call(SEND_BINDER)
 * 注入 service binder → [XposedServiceHelper.onBinderReceived] 缓存/分发。
 *
 * 配置读写模型：本地 XML 为权威（离线可写、立即生效于 UI），框架侧是给 hook 端消费的镜像；
 * UI 写本地后若框架在线即时推一份，框架绑定时把本地全量同步过去（本地覆盖框架）。
 */
class App : Application(), XposedServiceHelper.OnServiceListener {

    override fun onCreate() {
        super.onCreate()
        // logcat 详细度门控读「运行日志」开关（开=全量，关=仅 e 级）
        Lg.installGateReader { readEnableLogs() }
        Lg.e("lg-boot", "app pid=${android.os.Process.myPid()}")
        // 官方约定：全进程只注册一次。
        XposedServiceHelper.registerListener(this)
    }

    /** 「运行日志」开关（本地优先；本地无键时读框架侧；读失败默认开，避免静默丢日志）。 */
    private fun readEnableLogs(): Boolean {
        val local = Prefs.read(this)
        if (local.contains(Prefs.KEY_ENABLE_LOGS)) {
            return local.getBoolean(Prefs.KEY_ENABLE_LOGS, Prefs.DEFAULT_ENABLE_LOGS)
        }
        val svc = serviceRef ?: return Prefs.DEFAULT_ENABLE_LOGS
        return try {
            Prefs.remote(svc).getBoolean(Prefs.KEY_ENABLE_LOGS, Prefs.DEFAULT_ENABLE_LOGS)
        } catch (t: Throwable) {
            true
        }
    }

    override fun onServiceBind(service: XposedService) {
        Lg.i(TAG, "framework service bound: ${service.getFrameworkName()} v${service.getFrameworkVersion()}")
        serviceRef = service
        serviceLost = false
        syncLocalToFramework(service)
        listeners.forEach { it.onServiceStateChanged(service) }
    }

    /** 本地配置全量同步到框架侧（本地为权威；hook 状态回传键只在框架侧、本地没有，不被触碰）。 */
    private fun syncLocalToFramework(service: XposedService) {
        try {
            val local = Prefs.read(this)
            val editor = Prefs.remote(service).edit()
            var n = 0
            for ((key, value) in local.all) {
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Long -> editor.putLong(key, value)
                    is Set<*> -> @Suppress("UNCHECKED_CAST") editor.putStringSet(key, value as Set<String>)
                    else -> continue
                }
                n++
            }
            editor.apply()
            Lg.i(TAG, "config sync local->framework n=$n")
        } catch (t: Throwable) {
            Lg.w(TAG, "config sync failed", t)
        }
    }

    override fun onServiceDied(service: XposedService) {
        Lg.w(TAG, "framework service died")
        serviceRef = null
        // [2026-10-10] binder 注入只在进程启动时来一次，死后无重连机制 → UI 读写全部落空
        //（读走 XML 兜底显示旧值、写进队列永远发不出）。置标志供 UI 提示用户「划掉设置页重开」自愈。
        serviceLost = true
        listeners.forEach { it.onServiceStateChanged(null) }
    }

    companion object {
        private const val TAG = "LiquidGlassApp"

        /** 框架服务（可为 null：未注入/未就绪）。UI 写入配置前判空，null 时经 listener 等待就绪。 */
        @Volatile
        var serviceRef: XposedService? = null
            private set

        /** [2026-10-10] 曾经绑定成功后又死掉（≠从未注入）。UI 据此提示「划掉设置页重开」。 */
        @Volatile
        var serviceLost: Boolean = false

        private val listeners = CopyOnWriteArraySet<ServiceStateListener>()

        /**
         * 注册框架服务状态监听（settings 界面用）。
         * @param notifyImmediately 为 true 时若当前已就绪，立即回调一次。
         */
        fun addServiceStateListener(listener: ServiceStateListener, notifyImmediately: Boolean) {
            listeners.add(listener)
            if (notifyImmediately) {
                val s = serviceRef
                if (s != null) listener.onServiceStateChanged(s)
            }
        }

        fun removeServiceStateListener(listener: ServiceStateListener) {
            listeners.remove(listener)
        }
    }

    /** 框架服务状态回调（UI 层等待/监听框架就绪）。 */
    interface ServiceStateListener {
        fun onServiceStateChanged(service: XposedService?)
    }
}
