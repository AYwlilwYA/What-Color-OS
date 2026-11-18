package com.coloros16.liquidglass.hook

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.engine.ClockAnchorMapper
import com.coloros16.liquidglass.engine.WidgetRectPx
import com.coloros16.liquidglass.log.Lg
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 桌面时钟锚点采集（Launcher 进程）：hook 框架类 `android.appwidget.AppWidgetHostView`
 * 的 `onLayout`，命中闹钟时钟小部件时把屏幕矩形换算成归一化锚点，
 * 经包名定向广播推给 SystemUI 的 [DesktopClockAnchorBridge]。
 *
 * - 换页 / 拖动这类只改滚动位置、不触发 onLayout 的移动，由首次命中后挂的
 *   `ViewTreeObserver.OnScrollChangedListener` 补发（200ms 最小间隔）。
 *
 * - 只跑在 Launcher 进程；门控 = [Prefs.KEY_MASTER] ∧ [Prefs.KEY_ALIVE_AOD_HANDOFF]（TTL 500ms）。
 * - 事件驱动：只在上述回调里取矩形，无轮询；同一矩形 + 最小间隔内不重复发。
 * - 全链 fail-open：类/方法/字段解析失败、Context 缺失、发送异常都只降级本功能并打节流日志。
 */
object DesktopClockAnchorHook {

    private const val TAG = "LgClockAnchor"
    private const val GATE_TTL_MS = 500L
    private const val PUBLISH_MIN_MS = 120L
    private const val SCROLL_PUBLISH_MIN_MS = 200L
    private const val THROTTLE_MS = 2000L

    private const val CLASS_APPWIDGET_HOST_VIEW = "android.appwidget.AppWidgetHostView"

    private val throttleAt = ConcurrentHashMap<String, Long>()
    private val watched = WeakHashMap<View, Boolean>()
    private val scrollWatchers = WeakHashMap<View, android.view.ViewTreeObserver.OnScrollChangedListener>()
    private val publishes = AtomicLong()
    private val skips = AtomicLong()

    @Volatile private var installed = false
    @Volatile private var gateEnabled = false
    @Volatile private var gateReadAt = 0L
    @Volatile private var appContext: Context? = null
    @Volatile private var lastKey = ""
    @Volatile private var lastPublishAt = 0L
    @Volatile private var lastVisible = false
    @Volatile private var lastCenterX = 0.5f
    @Volatile private var lastCenterY = 0.5f
    @Volatile private var lastScale = 1f

    private var api: XposedInterface? = null

    private fun logThrottled(key: String, msg: String, intervalMs: Long = THROTTLE_MS) {
        val now = SystemClock.uptimeMillis()
        val last = throttleAt[key]
        if (last != null && now - last < intervalMs) return
        throttleAt[key] = now
        if (throttleAt.size > 128) throttleAt.clear()
        Lg.i(TAG, "$key: $msg")
    }

    fun install(api: XposedInterface, classLoader: ClassLoader) {
        if (installed) {
            logThrottled("dc-install-dup", "已装载，跳过 pubs=${publishes.get()}")
            return
        }
        this.api = api
        refreshGate()
        val cls = try {
            Class.forName(CLASS_APPWIDGET_HOST_VIEW, false, classLoader)
        } catch (t: Throwable) {
            Lg.e(TAG, "dc-install 类缺失 ${t.javaClass.simpleName}: ${t.message}", t)
            return
        }
        var hook = 0
        if (mount(api, cls, "onLayout", Boolean::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)) hook++
        installed = hook > 0
        Lg.i(TAG, "dc-install hook=$hook/1 installed=$installed gate=${refreshGate()}")
    }

    private fun mount(api: XposedInterface, cls: Class<*>, name: String, vararg types: Class<*>): Boolean {
        val m = try {
            cls.getDeclaredMethod(name, *types)
        } catch (t: Throwable) {
            logThrottled("dc-miss-$name", "方法缺失 $name ${t.javaClass.simpleName}")
            return false
        }
        return try {
            api.hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val result = chain.proceed()
                try {
                    (chain.getThisObject() as? AppWidgetHostView)?.let { publish(it, name) }
                } catch (t: Throwable) {
                    logThrottled("dc-cb-$name", "回调异常 ${t.javaClass.simpleName}: ${t.message}")
                }
                result
            }
            Lg.i(TAG, "dc-mount $CLASS_APPWIDGET_HOST_VIEW#$name")
            true
        } catch (t: Throwable) {
            Lg.e(TAG, "dc-mount $name 失败 ${t.javaClass.simpleName}: ${t.message}", t)
            false
        }
    }

    /** 门控（TTL 500ms）：master ∧ 息屏衔接键；读失败保持上次值。 */
    private fun refreshGate(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (gateReadAt != 0L && now - gateReadAt < GATE_TTL_MS) return gateEnabled
        gateReadAt = now
        val a = api ?: return gateEnabled
        val prev = gateEnabled
        gateEnabled = try {
            val prefs = Prefs.read(a)
            prefs.getBoolean(Prefs.KEY_MASTER, false) &&
                prefs.getBoolean(Prefs.KEY_ALIVE_AOD_HANDOFF, false)
        } catch (t: Throwable) {
            logThrottled("dc-gate-read", "读键失败 ${t.javaClass.simpleName} 保持 $prev")
            prev
        }
        if (prev != gateEnabled) Lg.i(TAG, "dc-gate $prev -> $gateEnabled")
        return gateEnabled
    }

    private fun publish(view: AppWidgetHostView, reason: String, minIntervalMs: Long = PUBLISH_MIN_MS) {
        if (!refreshGate()) {
            skips.incrementAndGet()
            logThrottled("dc-gate-off", "门控关闭，跳过 reason=$reason skip=${skips.get()}")
            return
        }
        val info = try {
            view.appWidgetInfo
        } catch (t: Throwable) {
            logThrottled("dc-info", "取 provider 失败 ${t.javaClass.simpleName}")
            return
        } ?: return
        val provider = info.provider ?: return
        val match = ClockAnchorMapper.match(provider.packageName, provider.className)
        if (match == ClockAnchorMapper.Match.NONE) return
        ensureAttachWatcher(view)

        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) {
            logThrottled("dc-size", "布局未完成 ${w}x$h reason=$reason")
            return
        }
        val loc = IntArray(2)
        try {
            view.getLocationOnScreen(loc)
        } catch (t: Throwable) {
            logThrottled("dc-loc", "取屏幕坐标失败 ${t.javaClass.simpleName}")
            return
        }
        val screen = screenSize(view)
        if (screen == null) {
            logThrottled("dc-screen", "屏幕尺寸不可用 reason=$reason")
            return
        }
        val rect = WidgetRectPx(loc[0], loc[1], loc[0] + w, loc[1] + h)
        val anchor = ClockAnchorMapper.fromWidgetRect(rect, screen[0], screen[1], 0f)
        if (anchor == null) {
            logThrottled("dc-rect", "矩形非法 $rect screen=${screen[0]}x${screen[1]}")
            return
        }
        val now = SystemClock.uptimeMillis()
        val key = String.format(Locale.US, "%d,%d,%d,%d,%d,%d", rect.left, rect.top, w, h, screen[0], screen[1])
        if (key == lastKey && lastVisible && now - lastPublishAt < minIntervalMs) return
        lastKey = key
        lastPublishAt = now
        lastVisible = true
        lastCenterX = anchor.centerX
        lastCenterY = anchor.centerY
        lastScale = anchor.scale
        send(
            visible = true,
            cx = anchor.centerX,
            cy = anchor.centerY,
            scale = anchor.scale,
            widgetW = w,
            widgetH = h,
            screenW = screen[0],
            screenH = screen[1],
            reason = "$reason/${match.name}",
        )
    }

    /** 小部件移除：立即发不可见（不参与最小间隔节流），中心沿用最后一次值。 */
    private fun publishInvisible(view: AppWidgetHostView, reason: String) {
        if (!refreshGate() || !lastVisible) return
        if (!isClockWidget(view)) return
        lastVisible = false
        lastKey = ""
        val screen = screenSize(view)
        send(
            visible = false,
            cx = lastCenterX,
            cy = lastCenterY,
            scale = lastScale,
            widgetW = 0,
            widgetH = 0,
            screenW = screen?.get(0) ?: 0,
            screenH = screen?.get(1) ?: 0,
            reason = reason,
        )
    }

    /** 首次命中时钟小部件时挂 attach / scroll 监听：移除时补发不可见，换页滚动时重算矩形。 */
    private fun ensureAttachWatcher(view: AppWidgetHostView) {
        if (watched.put(view, true) != null) return
        try {
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}

                override fun onViewDetachedFromWindow(v: View) {
                    if (v is AppWidgetHostView) {
                        try {
                            removeScrollWatcher(v)
                            publishInvisible(v, "detach")
                        } catch (t: Throwable) {
                            logThrottled("dc-detach", "移除回调异常 ${t.javaClass.simpleName}")
                        }
                    }
                }
            })
        } catch (t: Throwable) {
            logThrottled("dc-watch", "attach 监听挂载失败 ${t.javaClass.simpleName}")
        }
        try {
            val observer = view.viewTreeObserver
            if (observer.isAlive) {
                val ref = java.lang.ref.WeakReference(view)
                val listener = android.view.ViewTreeObserver.OnScrollChangedListener {
                    val v = ref.get() ?: return@OnScrollChangedListener
                    try {
                        publish(v, "scroll", SCROLL_PUBLISH_MIN_MS)
                    } catch (t: Throwable) {
                        logThrottled("dc-scroll", "滚动回调异常 ${t.javaClass.simpleName}")
                    }
                }
                scrollWatchers[view] = listener
                observer.addOnScrollChangedListener(listener)
            }
        } catch (t: Throwable) {
            logThrottled("dc-scroll-watch", "滚动监听挂载失败 ${t.javaClass.simpleName}")
        }
    }

    private fun removeScrollWatcher(view: View) {
        val listener = scrollWatchers.remove(view) ?: return
        try {
            val observer = view.viewTreeObserver
            if (observer.isAlive) observer.removeOnScrollChangedListener(listener)
        } catch (t: Throwable) {
            logThrottled("dc-scroll-unwatch", "滚动监听移除失败 ${t.javaClass.simpleName}")
        }
    }

    private fun isClockWidget(view: AppWidgetHostView): Boolean {
        val info = try {
            view.appWidgetInfo
        } catch (t: Throwable) {
            return false
        } ?: return false
        val provider = info.provider ?: return false
        return ClockAnchorMapper.match(provider.packageName, provider.className) != ClockAnchorMapper.Match.NONE
    }

    private fun screenSize(view: View): IntArray? {
        val root = view.rootView
        if (root.width > 0 && root.height > 0) return intArrayOf(root.width, root.height)
        val dm = context()?.resources?.displayMetrics ?: return null
        if (dm.widthPixels <= 0 || dm.heightPixels <= 0) return null
        return intArrayOf(dm.widthPixels, dm.heightPixels)
    }

    private fun context(): Context? {
        appContext?.let { return it }
        val ctx = Lg.processContext()?.applicationContext
        if (ctx != null) appContext = ctx
        return ctx
    }

    private fun send(
        visible: Boolean,
        cx: Float,
        cy: Float,
        scale: Float,
        widgetW: Int,
        widgetH: Int,
        screenW: Int,
        screenH: Int,
        reason: String,
    ) {
        val ctx = context()
        if (ctx == null) {
            skips.incrementAndGet()
            logThrottled("dc-ctx", "Context 为空，未发送 reason=$reason")
            return
        }
        try {
            val intent = Intent(DesktopClockProtocol.ACTION).apply {
                setPackage(DesktopClockProtocol.SYSTEMUI_PKG)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                putExtra(DesktopClockProtocol.EXTRA_VISIBLE, visible)
                putExtra(DesktopClockProtocol.EXTRA_CENTER_X, cx)
                putExtra(DesktopClockProtocol.EXTRA_CENTER_Y, cy)
                putExtra(DesktopClockProtocol.EXTRA_SCALE, scale)
                putExtra(DesktopClockProtocol.EXTRA_WIDGET_W, widgetW)
                putExtra(DesktopClockProtocol.EXTRA_WIDGET_H, widgetH)
                putExtra(DesktopClockProtocol.EXTRA_SCREEN_W, screenW)
                putExtra(DesktopClockProtocol.EXTRA_SCREEN_H, screenH)
                putExtra(DesktopClockProtocol.EXTRA_REASON, reason)
            }
            ctx.sendBroadcast(intent)
            publishes.incrementAndGet()
            logThrottled(
                if (visible) "dc-pub" else "dc-pub-hide",
                String.format(
                    Locale.US,
                    "vis=%b cx=%.4f cy=%.4f s=%.3f widget=%dx%d screen=%dx%d reason=%s pubs=%d",
                    visible, cx, cy, scale, widgetW, widgetH, screenW, screenH, reason, publishes.get(),
                ),
            )
        } catch (t: Throwable) {
            skips.incrementAndGet()
            logThrottled("dc-send", "广播发送失败 ${t.javaClass.simpleName}: ${t.message}")
        }
    }
}
