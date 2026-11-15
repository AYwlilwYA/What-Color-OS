package com.coloros16.liquidglass.sf

import android.annotation.SuppressLint
import android.view.View
import com.coloros16.liquidglass.log.Lg
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * SF 模糊通道直调桥：进程内反射调 SurfaceControl.Transaction 的
 * setBlurRegions + setBackgroundBlurRadius，几何/圆角随调用直达 SF——
 * 不经文件信箱、不经独立进程。
 *
 * 反射面（android-34 源码实证）：
 * - setBlurRegions(SurfaceControl, float[][])：每条区域 10 float——
 *   [0]radius [1]alpha [2]left [3]top [4]right [5]bottom [6..9]四角圆角
 * - setBackgroundBlurRadius(SurfaceControl, int)
 * - 宿主链 View.mAttachInfo → AttachInfo.mViewRootImpl → ViewRootImpl.mSurfaceControl
 * SystemUI 进程为平台签名，隐藏 API 不受限。
 */
// 本类全反射隐藏 API，lint 的非 SDK 访问门不适用
@SuppressLint("BlockedPrivateApi")
internal object SfBlurCall {

    private const val TAG = "SfBlurCall"

    /** native 每条区域要求的 float 数（不足会 AIOOBE）。 */
    private const val REGION_FLOATS = 32

    private class Ref(
        val transactionCtor: Constructor<*>,
        val setBlurRegions: Method,
        val setBackgroundBlurRadius: Method,
        val apply: Method,
        val close: Method?,
        val attachInfo: Field,
        val viewRootImpl: Field,
        val surfaceControl: Field,
    )

    private val ref: Ref? by lazy { resolve() }

    private fun resolve(): Ref? = try {
        val scClass = Class.forName("android.view.SurfaceControl")
        val txClass = Class.forName("android.view.SurfaceControl\$Transaction")
        val regionsParam = Array<FloatArray>::class.java
        val intP = Int::class.javaPrimitiveType
        Ref(
            transactionCtor = txClass.getConstructor(),
            setBlurRegions = txClass.getMethod("setBlurRegions", scClass, regionsParam),
            setBackgroundBlurRadius = txClass.getMethod("setBackgroundBlurRadius", scClass, intP),
            apply = txClass.methods.first { it.name == "apply" && it.parameterCount == 0 },
            close = txClass.methods.firstOrNull { it.name == "close" && it.parameterCount == 0 },
            attachInfo = View::class.java.getDeclaredField("mAttachInfo"),
            viewRootImpl = Class.forName("android.view.View\$AttachInfo").getDeclaredField("mViewRootImpl"),
            surfaceControl = Class.forName("android.view.ViewRootImpl").getDeclaredField("mSurfaceControl"),
        ).also {
            it.attachInfo.isAccessible = true
            it.viewRootImpl.isAccessible = true
            it.surfaceControl.isAccessible = true
        }
    } catch (t: Throwable) {
        Lg.w(TAG, "reflect resolve failed: ${t.javaClass.simpleName}: ${t.message}")
        null
    }

    /** 单条区域序列化（前 10 与 BackgroundBlurDrawable.BlurRegion.toFloatArray 同布局，其余补 0）。 */
    fun regionOf(radius: Float, alpha: Float, left: Float, top: Float, right: Float, bottom: Float, corner: Float): FloatArray =
        FloatArray(REGION_FLOATS).also {
            it[0] = radius
            it[1] = alpha
            it[2] = left
            it[3] = top
            it[4] = right
            it[5] = bottom
            it[6] = corner
            it[7] = corner
            it[8] = corner
            it[9] = corner
        }

    /** 取宿主窗口 SurfaceControl（View→ViewRootImpl→mSurfaceControl）；失败返回 null。 */
    fun hostScOf(view: View): Any? {
        val r = ref ?: return null
        return try {
            val ai = r.attachInfo.get(view) ?: return null
            val vr = r.viewRootImpl.get(ai) ?: return null
            r.surfaceControl.get(vr)
        } catch (t: Throwable) {
            throttledLog("host-sc", "${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /** host view 拿不到时的兜底：从 WindowManagerGlobal.mRoots 里找有效窗口 SC（优先 NotificationShade）。 */
    fun fallbackSc(): Any? {
        val r = ref ?: return null
        return try {
            val wmgCls = Class.forName("android.view.WindowManagerGlobal")
            val inst = wmgCls.getMethod("getInstance").invoke(null)
            val rootsF = wmgCls.getDeclaredField("mRoots")
            rootsF.isAccessible = true
            val roots = rootsF.get(inst) as? java.util.ArrayList<*> ?: return null
            val scCls = Class.forName("android.view.SurfaceControl")
            val isValid = scCls.getMethod("isValid")
            val getName = scCls.methods.firstOrNull { it.name == "getName" && it.parameterCount == 0 }
            val vriSC = r.surfaceControl
            var firstValid: Any? = null
            var shadeSc: Any? = null
            for (root in roots) {
                val sc = vriSC.get(root) ?: continue
                if (isValid.invoke(sc) != true) continue
                if (firstValid == null) firstValid = sc
                val name = getName?.invoke(sc) as? String
                if (name != null && name.contains("NotificationShade")) {
                    shadeSc = sc
                    break
                }
            }
            shadeSc ?: firstValid
        } catch (t: Throwable) {
            throttledLog("fallback-sc", "${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /**
     * 把区域数组打到宿主窗口 SC（regions 空数组 = 清除模糊并把窗口半径归零）。
     * 返回 null=成功，否则失败原因（调用方走节流日志）。
     */
    fun applyRegions(sc: Any, regions: Array<FloatArray>, radius: Int): String? {
        val r = ref ?: return "反射未解析"
        return try {
            val tx = r.transactionCtor.newInstance()
            try {
                r.setBlurRegions.invoke(tx, sc, regions)
                r.setBackgroundBlurRadius.invoke(tx, sc, if (regions.isEmpty()) 0 else radius)
                r.apply.invoke(tx)
            } finally {
                try {
                    r.close?.invoke(tx)
                } catch (_: Throwable) {
                }
            }
            null
        } catch (t: Throwable) {
            val cause = t.cause ?: t
            "${cause.javaClass.simpleName}: ${cause.message}"
        }
    }

    private val throttleAt = HashMap<String, Long>()

    private fun throttledLog(key: String, msg: String, intervalMs: Long = 5000L) {
        val now = android.os.SystemClock.uptimeMillis()
        val last = throttleAt[key]
        if (last != null && now - last < intervalMs) return
        throttleAt[key] = now
        if (throttleAt.size > 8) throttleAt.clear()
        Lg.w(TAG, "[$key] $msg")
    }
}
