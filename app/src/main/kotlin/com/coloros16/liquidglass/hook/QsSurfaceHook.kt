package com.coloros16.liquidglass.hook

import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import com.coloros16.liquidglass.log.Lg
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * 控制中心两处外观修正：滑杆方底角（按 COUI 父类当前外轮廓裁剪）+ 2×1 横向按钮内圈白底跳过。
 *
 * 类名/字段名已对 C16（reference/systemui-16.99.12）与 C17（dc_sysui_179902）反编译件双侧复核。
 */
object QsSurfaceHook {
    private const val TAG = "lg-qs"
    private const val THROTTLE_MS = 2000L
    private const val ICON = "com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSIconView"
    private const val SLIDER = "com.oplus.systemui.qs.base.seek.OplusQsVerticalSeekBar"
    private const val ICON_TRANSITION = "com.oplus.systemui.qs.base.res.drawable.TileTransitionDrawable"

    private val iconBackgrounds = Collections.synchronizedMap(WeakHashMap<Drawable, WeakReference<View>>())
    private val throttleAt = ConcurrentHashMap<String, Long>()

    /** 挂载两处修正；任一失败只降级该处（保持原生绘制），不影响另一处。
     *  [enabled] = 总开关（玻璃开且非省电）；[hideSecondary] = 隐藏二级玻璃（小圆底）独立开关。 */
    fun install(
        api: XposedInterface,
        loader: ClassLoader,
        enabled: () -> Boolean,
        hideSecondary: () -> Boolean,
    ) {
        installSlider(api, loader, enabled)
        installIconBackground(api, loader) { enabled() && hideSecondary() }
    }

    /** 2×1 横向按钮：登记 iconBgView 的独立背景 Drawable，仅祖先命中横向磁贴时跳过其绘制。 */
    private fun installIconBackground(api: XposedInterface, loader: ClassLoader, enabled: () -> Boolean) {
        try {
            val icon = Class.forName(ICON, false, loader)
            val background = icon.getDeclaredField("iconBgView").apply { isAccessible = true }
            for (method in arrayOf(
                icon.getDeclaredMethod("onAttachedToWindow"),
                icon.getDeclaredMethod(
                    "onLayout", Boolean::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                ),
                icon.getDeclaredMethod("updateIconBgDrawable"),
            )) {
                api.hook(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val result = chain.proceed()
                        try {
                            val owner = chain.getThisObject() as? View
                            val drawable = (background.get(owner) as? View)?.background
                            if (owner != null && drawable != null) {
                                iconBackgrounds[drawable] = WeakReference(owner)
                            }
                        } catch (_: Throwable) {
                        }
                        result
                    }
            }
            val transition = Class.forName(ICON_TRANSITION, false, loader)
            api.hook(transition.getDeclaredMethod("draw", Canvas::class.java))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val owner = iconBackgrounds[chain.getThisObject()]?.get()
                    if (owner != null && hasWideTileParent(owner) && enabled()) {
                        logThrottled("qs-icon-bg", "横向按钮独立图标底层已跳过；保留图标和状态色")
                        null
                    } else chain.proceed()
                }
            Lg.i(TAG, "[qs-icon-hook] 已挂载横向按钮图标底层；待实际绘制")
        } catch (t: Throwable) {
            logThrottled("qs-icon-fail", "横向按钮图标底层不可用，保持原生绘制", t)
        }
    }

    /** 父链上溯 8 层找横向磁贴（普通 1×1 圆钮与顶栏图标不受影响）。 */
    private fun hasWideTileParent(icon: View): Boolean {
        var cursor = icon.parent as? View
        repeat(8) {
            val view = cursor ?: return false
            if (QsSurfacePolicy.isWideTile(view.javaClass.name)) return true
            cursor = view.parent as? View
        }
        return false
    }

    private class ClipState(
        val path: Path = Path(),
        val rect: RectF = RectF(),
        val radii: FloatArray = FloatArray(8),
    ) {
        var adapter: Any? = null
    }

    /** 滑杆方底角：原生进度裁剪外包一层按当前外轮廓的 clipPath，进度高度/拖动/动画不变。 */
    private fun installSlider(api: XposedInterface, loader: ClassLoader, enabled: () -> Boolean) {
        try {
            val slider = Class.forName(SLIDER, false, loader)
            val bounds = QsSurfacePolicy.field(slider, "mClipProgressRect")
            val radius = QsSurfacePolicy.field(slider, "mCurProgressRadius")
            val weight = QsSurfacePolicy.field(slider, "mBackgroundRoundCornerWeight")
            val states = Collections.synchronizedMap(WeakHashMap<View, ClipState>())
            // 用 ROM 自家平滑圆角构造器，不猜固定半径
            val smooth = try {
                val type = Class.forName("com.oplus.posteffect.util.OplusPathAdapterCompatUtils", false, loader)
                Triple(
                    type.getField("INSTANCE").get(null),
                    type.getDeclaredMethod("create", Path::class.java),
                    type.getDeclaredMethod(
                        "addSmoothRoundRect", Any::class.java, RectF::class.java,
                        FloatArray::class.java, Path.Direction::class.java, Float::class.javaPrimitiveType!!,
                    ),
                )
            } catch (_: Throwable) {
                null
            }
            api.hook(slider.getDeclaredMethod("drawActiveMixColorTrack", Canvas::class.java))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val view = chain.getThisObject() as? View
                    val canvas = chain.getArg(0) as? Canvas
                    val clip = if (view != null && canvas != null && enabled()) try {
                        val rect = bounds.get(view) as? Rect
                        val r = radius.getFloat(view)
                        if (rect == null || rect.isEmpty || !r.isFinite() || r <= 0f) null else {
                            val state = synchronized(states) { states.getOrPut(view) { ClipState() } }
                            state.rect.set(rect)
                            state.path.reset()
                            val w = weight.getFloat(view)
                            val rounded = if (smooth != null && w.isFinite() && w != 0f) try {
                                state.adapter = state.adapter ?: smooth.second.invoke(smooth.first, state.path)
                                state.radii.fill(r)
                                smooth.third.invoke(
                                    smooth.first, state.adapter, state.rect, state.radii, Path.Direction.CW, w,
                                ) == true
                            } catch (_: Throwable) {
                                false
                            } else false
                            if (!rounded || state.path.isEmpty) {
                                state.path.reset()
                                state.path.addRoundRect(state.rect, r, r, Path.Direction.CW)
                            }
                            state.path
                        }
                    } catch (t: Throwable) {
                        logThrottled("qs-slider-clip-fail", "滑杆外轮廓不可用，保持原生绘制", t)
                        null
                    } else null
                    if (canvas == null || clip == null) chain.proceed() else {
                        val saved = canvas.save()
                        try {
                            canvas.clipPath(clip)
                            val result = chain.proceed()
                            logThrottled("qs-slider-clip", "进度填充已按滑杆当前外轮廓裁剪")
                            result
                        } finally {
                            canvas.restoreToCount(saved)
                        }
                    }
                }
            Lg.i(TAG, "[qs-slider-hook] 已挂载控制中心进度外轮廓裁剪；待实际绘制")
        } catch (t: Throwable) {
            logThrottled("qs-slider-fail", "滑杆外轮廓兼容不可用，保持原生绘制", t)
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

/** 精确归属规则：只动 2×1 横向磁贴，圆钮与状态栏图标背景一律不碰。 */
object QsSurfacePolicy {
    fun isWideTile(name: String): Boolean =
        name == "com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSResizeableTileViewTwoXOne"

    /** 沿父链找声明字段（COUI 父类字段在子类实例上可直接取）。 */
    fun field(type: Class<*>, name: String): Field {
        var cursor: Class<*>? = type
        while (cursor != null) {
            try {
                return cursor.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                cursor = cursor.superclass
            }
        }
        throw NoSuchFieldException("${type.name}.$name")
    }
}
