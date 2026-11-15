package com.coloros16.liquidglass.hook

import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.view.View
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * QS 进度滑杆：工厂签名与「包装层 → 实际渲染对象」两本账。
 *
 * 工厂返回的是 AutoBlurDrawable 包装层，`drawBlurShader` 的 `this` 是内层 posteffect 对象，
 * 两层身份分开记账，登记时再 bind。
 */
class QsSeekbarCompat {
    private val activeWrappers = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
    private val activeTargets = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())

    /** 7 参工厂优先（SystemUI 17.99.02），1 参后备；不按名字/参数个数猜其它重载。 */
    fun resolveActiveFactory(type: Class<*>): Method {
        val name = "getSeekBarActiveDrawable"
        return try {
            type.getDeclaredMethod(
                name, View::class.java, Rect::class.java,
                Float::class.javaPrimitiveType!!, Float::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!,
                Float::class.javaPrimitiveType!!,
            )
        } catch (_: NoSuchMethodException) {
            type.getDeclaredMethod(name, View::class.java)
        }
    }

    /** 工厂返回的包装层登记。 */
    fun registerFactoryResult(wrapper: Any?) {
        if (wrapper != null) activeWrappers[wrapper] = true
    }

    fun isActiveWrapper(wrapper: Any): Boolean = activeWrappers.containsKey(wrapper)

    /** 包装层 → 实际渲染对象补登记（registerScreenRegionCore 解析出内层后调用）。 */
    fun bind(wrapper: Any, target: Any): Boolean {
        if (!activeWrappers.containsKey(wrapper)) return false
        activeTargets[target] = true
        return true
    }

    fun isActiveTarget(target: Any): Boolean = activeTargets.containsKey(target)

    /** seekbar 绕过 registerTargetCache：系统材质会随主题/布局重建，缓存会拿到过期 target。 */
    fun bypassTargetCache(wrapper: Any?): Boolean = wrapper != null && isActiveWrapper(wrapper)

    /**
     * 进度填充层 tint 只在一次绘制内生效：清 shader + 上 tint，finally 恢复原状态。
     *
     * 常驻 colorFilter 会在关玻璃后留下白色填充。
     */
    inline fun <T> withActiveTint(paint: Paint, alpha: Int, block: () -> T): T {
        val shader = paint.shader
        val colorFilter = paint.colorFilter
        try {
            paint.shader = null
            paint.colorFilter = PorterDuffColorFilter(
                Color.argb(alpha, 255, 255, 255), PorterDuff.Mode.SRC,
            )
            return block()
        } finally {
            paint.shader = shader
            paint.colorFilter = colorFilter
        }
    }

    companion object {
        /** 进度填充层 tint alpha（0..255）：半透明让轨道层玻璃的折射/高光透出。 */
        const val ACTIVE_TINT_ALPHA = 0x66

        /** 单例：接线方共享同一本账。 */
        val instance: QsSeekbarCompat by lazy { QsSeekbarCompat() }
    }
}
