package com.coloros16.liquidglass.hook

import android.content.res.AssetManager
import com.coloros16.liquidglass.log.Lg
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.ConcurrentHashMap

/**
 * 宿主进程内读模块自带 assets：宿主 context 的 assets 只含宿主资源，
 * 必须按模块 APK 路径另建 AssetManager。
 *
 * 路径来自框架 getModuleApplicationInfo().sourceDir；反射 addAssetPath 建管理器。
 * 结果缓存，失败只降级本功能（返回 null），不影响宿主。
 */
object HookAssetSource {

    private const val TAG = "LgHookAsset"

    @Volatile private var cached: AssetManager? = null

    @Volatile private var failed = false

    private val logged = ConcurrentHashMap<String, Boolean>()

    private fun logOnce(key: String, msg: String) {
        if (logged.putIfAbsent(key, true) != null) return
        Lg.i(TAG, "$key: $msg")
    }

    /** 取模块 assets；任一步失败返回 null 并记账，后续调用直接短路。 */
    fun open(api: XposedInterface): AssetManager? {
        cached?.let { return it }
        if (failed) return null
        synchronized(this) {
            cached?.let { return it }
            if (failed) return null
            val manager = build(api)
            if (manager == null) {
                failed = true
                return null
            }
            cached = manager
            return manager
        }
    }

    private fun build(api: XposedInterface): AssetManager? {
        val path = try {
            api.getModuleApplicationInfo()?.sourceDir
        } catch (t: Throwable) {
            logOnce("asset-appinfo", "取模块 ApplicationInfo 失败 ${t.javaClass.simpleName}: ${t.message}")
            null
        }
        if (path.isNullOrEmpty()) {
            logOnce("asset-nopath", "模块 APK 路径为空，assets 不可用")
            return null
        }
        return try {
            val manager = AssetManager::class.java.getDeclaredConstructor().newInstance()
            val add = AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java)
            add.isAccessible = true
            val cookie = add.invoke(manager, path) as? Int ?: 0
            if (cookie == 0) {
                logOnce("asset-addfail", "addAssetPath 返回 0 path=$path")
                null
            } else {
                logOnce("asset-ready", "模块 assets 已就绪 cookie=$cookie")
                manager
            }
        } catch (t: Throwable) {
            logOnce("asset-build", "建 AssetManager 失败 ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /** 热重载后模块 APK 可能换路径，清缓存重建。 */
    fun reset() {
        synchronized(this) {
            cached = null
            failed = false
            logged.clear()
        }
    }
}