package com.coloros16.liquidglass.demo

import android.app.Activity
import android.view.View

/**
 * SF 模糊通道探针：反射 SurfaceControl.Transaction.setBackgroundBlurRadius 直接打宿主窗口。
 * 仅在框架注入解除 hiddenapi 限制后可用；返回 null=成功，否则失败原因原文。
 */
object SfBlurProbe {

    fun apply(activity: Activity, radius: Int): String? = try {
        val decor = activity.window.decorView
        val ai = View::class.java.getDeclaredField("mAttachInfo").apply { isAccessible = true }.get(decor)
            ?: error("mAttachInfo 为 null（未附着）")
        val vr = ai.javaClass.getDeclaredField("mViewRootImpl").apply { isAccessible = true }.get(ai)
            ?: error("mViewRootImpl 为 null")
        val sc = vr.javaClass.getDeclaredField("mSurfaceControl").apply { isAccessible = true }.get(vr)
            ?: error("mSurfaceControl 为 null")
        val scClass = Class.forName("android.view.SurfaceControl")
        val txClass = Class.forName("android.view.SurfaceControl\$Transaction")
        val tx = txClass.getConstructor().newInstance()
        txClass.getMethod("setBackgroundBlurRadius", scClass, Int::class.javaPrimitiveType)
            .invoke(tx, sc, radius)
        txClass.methods.first { it.name == "apply" && it.parameterCount == 0 }.invoke(tx)
        null
    } catch (t: Throwable) {
        val c = t.cause ?: t
        "${c.javaClass.simpleName}: ${c.message}"
    }
}
