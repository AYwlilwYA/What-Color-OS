package com.coloros16.liquidglass.hook

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import com.coloros16.liquidglass.log.Lg
import com.coloros16.liquidglass.sf.SfBlurCall
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode

/**
 * 注入进程内的 SF 模糊探针：模块代码直调 SurfaceControl.Transaction 给宿主窗口申报整窗模糊。
 * 挂 Activity#onResume，区域按屏幕尺寸取，不依赖 decor 布局完成。
 */
object DemoGlassHook {
    private const val TAG = "lg-demo-glass"
    private const val RADIUS = 0
    private const val CORNER = 40f

    private val main = Handler(Looper.getMainLooper())
    private var mounted = false

    fun install(api: XposedInterface) {
        if (mounted) return
        mounted = true
        try {
            val onResume = Activity::class.java.getDeclaredMethod("onResume")
            api.hook(onResume)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val act = chain.getThisObject() as? Activity
                        val decor = act?.window?.decorView
                        Lg.e(TAG, "onResume hit act=${act?.javaClass?.name} decorAttached=${decor != null}")
                        if (decor != null) runNativeProbe(decor)
                    } catch (t: Throwable) {
                        Lg.e(TAG, "resume probe error: ${t.javaClass.simpleName}: ${t.message}")
                    }
                    result
                }
            Lg.e(TAG, "mounted: android.app.Activity#onResume")
        } catch (t: Throwable) {
            Lg.e(TAG, "install FAILED: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private var probeLoaded = false

    /**
     * 锁屏时只有 SystemUI 窗口可见，Activity onResume 触发不到；
     * 挂 ViewGroup.dispatchDraw，首次命中即对所在窗口打模糊。
     */
    fun installIntoViewDraw(api: XposedInterface) {
        if (drawMounted) return
        drawMounted = true
        try {
            val dispatchDraw = ViewGroup::class.java.getDeclaredMethod("dispatchDraw", Canvas::class.java)
            api.hook(dispatchDraw)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    if (!drawProbed) {
                        val v = chain.getThisObject() as? View
                        if (v != null) {
                            drawProbed = true
                            Lg.e(TAG, "dispatchDraw hit view=${v.javaClass.name} ctx=${v.context.javaClass.name}")
                            runNativeProbe(v)
                        }
                    }
                    result
                }
            Lg.e(TAG, "mounted: ViewGroup#dispatchDraw")
        } catch (t: Throwable) {
            Lg.e(TAG, "dispatchDraw install FAILED: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private var drawMounted = false
    private var drawProbed = false

    /** 加载模块 native 探针并直读隐藏字段，验证注入进程 native 路径是否受 hiddenapi 限制。 */
    private fun runNativeProbe(decor: View, attempt: Int = 0) {
        if (!probeLoaded) {
            try {
                val lib = java.io.File("/data/data/${decor.context.packageName}/code_cache/liblgprobe11.so")
                Lg.e(TAG, "native lib path=$lib exists=${lib.exists()} size=${lib.length()}")
                System.load(lib.absolutePath)
                probeLoaded = true
                Lg.e(TAG, "native lib loaded")
            } catch (t: Throwable) {
                Lg.e(TAG, "native load FAILED: ${t.javaClass.simpleName}: ${t.message}")
                return
            }
        }
        try {
            val r = LgNativeProbe.probe(decor, RADIUS)
            Lg.e(TAG, "native probe[$attempt]: $r")
            if (!r.contains("mSurfaceControl=non-null") && attempt < 12) {
                main.postDelayed({ runNativeProbe(decor, attempt + 1) }, 400)
            }
        } catch (t: Throwable) {
            Lg.e(TAG, "native probe THREW: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun apply(decor: View) {
        try {
            val aiField = View::class.java.getDeclaredField("mAttachInfo").apply { isAccessible = true }
            val ai = aiField.get(decor)
            Lg.e(TAG, "step1 mAttachInfo=${ai?.javaClass?.name}")
            if (ai == null) return
            val vrField = ai.javaClass.getDeclaredField("mViewRootImpl").apply { isAccessible = true }
            val vr = vrField.get(ai)
            Lg.e(TAG, "step2 mViewRootImpl=${vr?.javaClass?.name}")
            if (vr == null) return
            val scField = vr.javaClass.getDeclaredField("mSurfaceControl").apply { isAccessible = true }
            val sc = scField.get(vr)
            Lg.e(TAG, "step3 mSurfaceControl=${sc?.javaClass?.name}")
            if (sc == null) return
            val dm = decor.resources.displayMetrics
            val w = dm.widthPixels.toFloat()
            val h = dm.heightPixels.toFloat()
            val region = SfBlurCall.regionOf(RADIUS.toFloat(), 1f, 0f, 0f, w, h, CORNER)
            val err = SfBlurCall.applyRegions(sc, arrayOf(region), RADIUS)
            Lg.e(TAG, "apply screen=${w.toInt()}x${h.toInt()} radius=$RADIUS err=${err ?: "ok"}")
        } catch (t: Throwable) {
            Lg.e(TAG, "apply FAILED: ${t.javaClass.simpleName}: ${t.message}")
        }
    }
}
