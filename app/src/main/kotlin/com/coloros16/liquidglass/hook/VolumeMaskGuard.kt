package com.coloros16.liquidglass.hook

import android.os.SystemClock
import android.view.View
import com.coloros16.liquidglass.log.Lg
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * 独立硬件音量面板隔离：遮罩层与内层内容都留在原生渲染路径。
 *
 * 归属判定只有这一个入口（既有 blurMaskColor 抑制链也从这里取判定，不长第二套）；
 * 早期挂接前查到的否定结果不缓存，只缓存肯定结果。
 */
object VolumeMaskGuard {
    private const val TAG = "lg-volume"
    private const val THROTTLE_MS = 2000L

    private val decisions = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
    private val nativeContents = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
    private val throttleAt = ConcurrentHashMap<String, Long>()

    /** 包装层遮罩 + 内层内容双登记（含异步内容录制）。 */
    fun protectNativeContent(wrapper: Any?, target: Any) {
        if (wrapper != null) decisions[wrapper] = true
        nativeContents[target] = true
        logThrottled("volume-native", "音量宿主已隔离 wrapper=${wrapper?.javaClass?.simpleName} target=${target.javaClass.simpleName}")
    }

    fun preservesContent(target: Any): Boolean = nativeContents.containsKey(target)

    fun isVolumeClass(name: String): Boolean =
        name.startsWith("com.oplus.systemui.volume.") || name.startsWith("com.android.systemui.volume.")

    /** 宿主 View 父链上溯 10 层判音量面板。 */
    fun isVolumeHost(host: View?): Boolean {
        var cursor = host
        repeat(10) {
            val view = cursor ?: return false
            if (isVolumeClass(view.javaClass.name)) return true
            cursor = view.parent as? View
        }
        return false
    }

    /** 遮罩层归属：从包装层拿宿主 View 判定；只缓存肯定结果。 */
    fun preserveMask(wrapper: Any): Boolean {
        decisions[wrapper]?.let { return it }
        val result = try {
            val supplier = wrapper.javaClass.getDeclaredField("getHostViewName")
                .apply { isAccessible = true }.get(wrapper)
            val host = supplier?.javaClass?.declaredFields?.asSequence()
                ?.filter { !Modifier.isStatic(it.modifiers) }
                ?.mapNotNull { field ->
                    try {
                        val owner = field.apply { isAccessible = true }.get(supplier)
                        if (owner is View) owner else owner?.javaClass?.getDeclaredField("hostView")
                            ?.apply { isAccessible = true }?.get(owner) as? View
                    } catch (_: Throwable) {
                        null
                    }
                }?.firstOrNull()
            isVolumeHost(host)
        } catch (_: Throwable) {
            false
        }
        if (result) decisions[wrapper] = true
        return result
    }

    /** blurMaskColor 抑制链的归属判定（既有抑制实现唯一调用点，禁止另立判定）。 */
    fun shouldProtectNative(wrapper: Any): Boolean = preserveMask(wrapper)

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
