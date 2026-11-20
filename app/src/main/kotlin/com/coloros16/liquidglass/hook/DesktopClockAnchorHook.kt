package com.coloros16.liquidglass.hook

import android.appwidget.AppWidgetHostView
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.view.View
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.engine.ClockAnchorMapper
import com.coloros16.liquidglass.engine.ClockHandoffPolicy
import com.coloros16.liquidglass.engine.OriginLedger
import com.coloros16.liquidglass.engine.WidgetRectPx
import com.coloros16.liquidglass.log.Lg
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.ref.WeakReference
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
 * - 编辑态拖动 / 缩放走 translation 与 scale，不触发 layout 也不触发 scroll：
 *   由 `NavigableAppWidgetHostView` 的六个 transform setter 补发（四版同名同签名）。
 *
 * - 同时收 SystemUI 下行的交接进度，写桌面小部件 alpha 完成两颗钟换手；
 *   宿主自带灵感时钟（[ClockHandoffPolicy.CLASS_NATIVE_CLOCK]）时整条让位，不写任何属性。
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
    private const val CLASS_NAVIGABLE_HOST_VIEW =
        "com.android.launcher3.widget.NavigableAppWidgetHostView"

    /** 编辑态 transform 入口：拖动预览/回弹/居中/改尺寸都收口在这六个 setter。 */
    private val TRANSFORM_SETTERS_2F = arrayOf(
        "setReorderBounceOffset",
        "setReorderPreviewOffset",
        "setTranslationForCentering",
        "setTranslationForMoveFromCenterAnimation",
    )
    private val TRANSFORM_SETTERS_1F = arrayOf("setReorderBounceScale", "setScaleToFit")

    private val throttleAt = ConcurrentHashMap<String, Long>()
    private val watched = WeakHashMap<View, Boolean>()
    private val scrollWatchers = WeakHashMap<View, android.view.ViewTreeObserver.OnScrollChangedListener>()
    private val publishes = AtomicLong()
    private val skips = AtomicLong()
    private val handoffWrites = AtomicLong()

    /** 桌面小部件 alpha 记账：写前存原值，交接结束按存回写。 */
    private val widgetAlphaOrigins = OriginLedger<Float>()
    private var clockWidgetRef: WeakReference<AppWidgetHostView>? = null
    @Volatile private var lastHandoff = Float.NaN
    @Volatile private var handoffEnabled = false
    @Volatile private var nativeClockPresent = false
    @Volatile private var receiverInstalled = false

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
        probeNativeClock(classLoader)
        var hook = 0
        if (mount(api, cls, "onLayout", Boolean::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)) hook++
        hook += mountTransformSetters(api, classLoader)
        installed = hook > 0
        installHandoffReceiver()
        Lg.i(
            TAG,
            "dc-install hook=$hook installed=$installed native=$nativeClockPresent gate=${refreshGate()}",
        )
    }

    /**
     * 宿主自带平移探针：灵感时钟类 + 解锁参数方法都在才认定自带。
     *
     * 静态点表说「该版本本来该有」，探针是生效真值；两者取或写入版本池补丁层。
     */
    private fun probeNativeClock(classLoader: ClassLoader) {
        val staticNative = try {
            val ver = VersionPool.readVersion(DesktopClockProtocol.LAUNCHER_PKG)
            VersionPool.query(DesktopClockProtocol.LAUNCHER_PKG, ver)
                .profile.desktopClockPath == DesktopClockPath.NATIVE_INSPIRATION
        } catch (t: Throwable) {
            false
        }
        val probed = try {
            val clock = Class.forName(ClockHandoffPolicy.CLASS_NATIVE_CLOCK, false, classLoader)
            clock.declaredMethods.any { it.name == ClockHandoffPolicy.METHOD_NATIVE_UNLOCK_PARAMS }
        } catch (t: Throwable) {
            false
        }
        nativeClockPresent = staticNative || probed
        try {
            VersionPool.notePatch(
                DesktopClockProtocol.LAUNCHER_PKG,
                VersionPool.PATCH_DESKTOP_CLOCK_PATH,
                if (nativeClockPresent) {
                    DesktopClockPath.NATIVE_INSPIRATION
                } else {
                    DesktopClockPath.MODULE_SUPPLEMENT
                },
            )
        } catch (t: Throwable) {
            logThrottled("dc-native-patch", "补丁写回失败 ${t.javaClass.simpleName}")
        }
        Lg.i(
            TAG,
            "dc-native-clock present=$nativeClockPresent static=$staticNative probed=$probed " +
                "translate=${ClockHandoffPolicy.moduleTranslates(nativeClockPresent)}",
        )
    }

    /**
     * 编辑态 transform 事件源：六个 setter 全在抽象基类上，子类无 override，
     * 故挂基类即覆盖整条继承链（四版同名同签名，实证见交接方案文档）。
     */
    private fun mountTransformSetters(api: XposedInterface, classLoader: ClassLoader): Int {
        if (!ClockHandoffPolicy.moduleTranslates(nativeClockPresent)) {
            Lg.i(TAG, "dc-transform skip: 宿主自带平移，让位")
            return 0
        }
        val cls = try {
            Class.forName(CLASS_NAVIGABLE_HOST_VIEW, false, classLoader)
        } catch (t: Throwable) {
            Lg.i(TAG, "dc-transform class-miss ${t.javaClass.simpleName}: ${t.message}")
            return 0
        }
        var n = 0
        for (name in TRANSFORM_SETTERS_2F) {
            if (mount(api, cls, name, Float::class.javaPrimitiveType!!, Float::class.javaPrimitiveType!!)) n++
        }
        for (name in TRANSFORM_SETTERS_1F) {
            if (mount(api, cls, name, Float::class.javaPrimitiveType!!)) n++
        }
        if (n == 0) Lg.e(TAG, "dc-transform 六个 setter 全缺，编辑态锚点不刷新")
        return n
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
            Lg.i(TAG, "dc-mount ${cls.name}#$name")
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
        clockWidgetRef = WeakReference(view)

        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) {
            logThrottled("dc-size", "布局未完成 ${w}x$h reason=$reason")
            return
        }
        val screen = screenSize(view)
        if (screen == null) {
            logThrottled("dc-screen", "屏幕尺寸不可用 reason=$reason")
            return
        }
        val rect = visualRect(view, w, h) ?: run {
            logThrottled("dc-loc", "取屏幕矩形失败 reason=$reason")
            return
        }
        val anchor = ClockAnchorMapper.fromWidgetRect(rect, screen[0], screen[1], 0f)
        if (anchor == null) {
            logThrottled("dc-rect", "矩形非法 $rect screen=${screen[0]}x${screen[1]}")
            return
        }
        val now = SystemClock.uptimeMillis()
        val key = String.format(
            Locale.US, "%.1f,%.1f,%.3f,%.3f,%d,%d,%d,%d,%d,%d",
            view.translationX, view.translationY, view.scaleX, view.scaleY,
            rect.left, rect.top, w, h, screen[0], screen[1],
        )
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

    /**
     * 视觉矩形：本地矩形先按自身矩阵映射（编辑态缩放/位移在这里体现），
     * 再沿父链逐级套矩阵与滚动偏移，最后补窗口在屏幕上的位置。
     *
     * `getLocationOnScreen` 只映射左上角一个点，缩放后尺寸仍取布局尺寸，
     * 拿到的是「原大小的框」而不是屏幕上真正看到的那个框。
     */
    private fun visualRect(view: View, w: Int, h: Int): WidgetRectPx? {
        val box = android.graphics.RectF(0f, 0f, w.toFloat(), h.toFloat())
        return try {
            if (!view.matrix.isIdentity) view.matrix.mapRect(box)
            box.offset(view.left.toFloat(), view.top.toFloat())
            var p = view.parent
            while (p is View) {
                box.offset(-p.scrollX.toFloat(), -p.scrollY.toFloat())
                if (!p.matrix.isIdentity) p.matrix.mapRect(box)
                box.offset(p.left.toFloat(), p.top.toFloat())
                p = p.parent
            }
            val win = IntArray(2)
            val scr = IntArray(2)
            val root = view.rootView
            root.getLocationInWindow(win)
            root.getLocationOnScreen(scr)
            box.offset((scr[0] - win[0]).toFloat(), (scr[1] - win[1]).toFloat())
            WidgetRectPx(
                Math.round(box.left), Math.round(box.top),
                Math.round(box.right), Math.round(box.bottom),
            )
        } catch (t: Throwable) {
            logThrottled("dc-matrix", "矩阵映射失败 ${t.javaClass.simpleName}")
            null
        }
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
                            widgetAlphaOrigins.forget(widgetKey(v))
                            if (clockWidgetRef?.get() === v) clockWidgetRef = null
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
                putExtra(DesktopClockProtocol.EXTRA_DIRECTION, DesktopClockProtocol.DIR_ANCHOR)
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

    /**
     * 交接下行接收：SystemUI 把交接进度推过来，本进程写桌面时钟小部件的 alpha。
     *
     * 让位档不注册；只收 SystemUI 包发来的包（API 34+ 校验，取不到放行并记日志）。
     */
    private fun installHandoffReceiver() {
        if (receiverInstalled) return
        if (!ClockHandoffPolicy.moduleTranslates(nativeClockPresent)) {
            Lg.i(TAG, "dc-handoff skip: 宿主自带平移，不注册接收器")
            return
        }
        val ctx = context()
        if (ctx == null) {
            Lg.i(TAG, "dc-handoff Context 为空，未注册接收器")
            return
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                try {
                    handleHandoff(this, intent)
                } catch (t: Throwable) {
                    logThrottled("dc-handoff-err", "交接处理失败 ${t.javaClass.simpleName}: ${t.message}")
                }
            }
        }
        try {
            val filter = IntentFilter(DesktopClockProtocol.ACTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                ctx.registerReceiver(receiver, filter)
            }
            receiverInstalled = true
            Lg.i(TAG, "dc-handoff 已注册 action=${DesktopClockProtocol.ACTION}")
        } catch (t: Throwable) {
            Lg.e(TAG, "dc-handoff 注册失败 ${t.javaClass.simpleName}: ${t.message}", t)
        }
    }

    private fun handleHandoff(receiver: BroadcastReceiver, intent: Intent?) {
        if (intent == null) return
        val dir = intent.getIntExtra(
            DesktopClockProtocol.EXTRA_DIRECTION, DesktopClockProtocol.DIR_ANCHOR,
        )
        if (dir != DesktopClockProtocol.DIR_HANDOFF) return
        if (!refreshGate()) {
            logThrottled("dc-handoff-gate", "门控关闭，丢弃交接包")
            return
        }
        val from = try {
            if (Build.VERSION.SDK_INT >= 34) receiver.sentFromPackage else null
        } catch (t: Throwable) {
            null
        }
        if (from != null && from != DesktopClockProtocol.SYSTEMUI_PKG) {
            logThrottled("dc-handoff-sender", "非 SystemUI 来源丢弃 from=$from")
            return
        }
        val enabled = intent.getBooleanExtra(DesktopClockProtocol.EXTRA_HANDOFF_ENABLED, false)
        val p = intent.getFloatExtra(DesktopClockProtocol.EXTRA_HANDOFF, 0f)
        if (!p.isFinite()) {
            logThrottled("dc-handoff-nan", "交接进度非法 p=$p")
            return
        }
        handoffEnabled = enabled
        if (!enabled) {
            restoreWidgetAlpha("disabled")
            lastHandoff = Float.NaN
            return
        }
        if (p == lastHandoff) return
        lastHandoff = p
        writeWidgetAlpha(ClockHandoffPolicy.alphaOf(ClockHandoffPolicy.Side.DESKTOP_WIDGET, p))
    }

    /** 写小部件 alpha：首写记原值，值未变不写。 */
    private fun writeWidgetAlpha(alpha: Float) {
        val view = clockWidgetRef?.get()
        if (!ClockHandoffPolicy.writesWidget(nativeClockPresent, view != null)) {
            logThrottled("dc-handoff-nowidget", "无桌面时钟小部件，交接只记账不写")
            return
        }
        requireNotNull(view)
        val key = widgetKey(view)
        widgetAlphaOrigins.record(key, view.alpha)
        val a = alpha.coerceIn(0f, 1f)
        if (view.alpha == a) return
        view.alpha = a
        handoffWrites.incrementAndGet()
        logThrottled(
            "dc-handoff-set",
            String.format(Locale.US, "a=%.3f writes=%d", a, handoffWrites.get()),
        )
    }

    /** 交接结束或让位：按记账回写原 alpha，只撤本件写过的视图。 */
    private fun restoreWidgetAlpha(reason: String) {
        val view = clockWidgetRef?.get() ?: return
        val origin = widgetAlphaOrigins.take(widgetKey(view)) ?: return
        view.alpha = origin
        logThrottled("dc-handoff-restore", "已还原 a=$origin reason=$reason")
    }

    private fun widgetKey(view: View): String = "widget|${System.identityHashCode(view)}"
}
