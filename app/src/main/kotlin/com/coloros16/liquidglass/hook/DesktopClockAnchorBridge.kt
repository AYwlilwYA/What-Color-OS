package com.coloros16.liquidglass.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.view.View
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.engine.ClockAnchor
import com.coloros16.liquidglass.engine.ClockAnchorMapper
import com.coloros16.liquidglass.engine.ClockHandoffPolicy
import com.coloros16.liquidglass.engine.ClockStateMachine
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.TransitionEdge
import com.coloros16.liquidglass.engine.WidgetRectPx
import com.coloros16.liquidglass.log.Lg
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 桌面时钟锚点接收（SystemUI 进程）：把两路时钟终点喂给 [ClockStateMachine] 的 HOME 锚点。
 *
 * - 第一路：[DesktopClockAnchorHook] 从 Launcher 广播来的桌面时钟小部件矩形；
 * - 第二路：[StatusBarClockAnchorHook] 在进程内直接给的状态栏时钟矩形。
 * - 优先级：桌面大时钟小部件 > 状态栏时钟 > 淡出（alpha=0）。
 * - 门控 = [Prefs.KEY_MASTER] ∧ [Prefs.KEY_ALIVE_AOD_HANDOFF]（TTL 500ms），关时只丢包不卸载。
 * - 只收 launcher 包发来的上行包（API 34+ 校验来源包，取不到则放行并记日志）。
 * - 同时把交接进度下行推给 Launcher，由它写桌面小部件 alpha 完成两颗钟换手。
 * - 事件驱动、无轮询；进程生命周期内常驻一个 receiver。
 */
object DesktopClockAnchorBridge {

    private const val TAG = "LgClockAnchor"
    private const val GATE_TTL_MS = 500L
    private const val THROTTLE_MS = 2000L
    private const val STATUS_BAR_MAX_CENTER_Y = 0.2f

    private val throttleAt = ConcurrentHashMap<String, Long>()
    private val receives = AtomicLong()
    private val drops = AtomicLong()

    /** 时钟状态机实例：后续渲染/接管消费方从这里取同一份 HOME 锚点。 */
    val machine: ClockStateMachine = ClockStateMachine()

    @Volatile private var installed = false
    @Volatile private var gateEnabled = false
    @Volatile private var gateReadAt = 0L
    @Volatile private var lastVisible = false
    @Volatile private var lastAnchor: ClockAnchor? = null
    @Volatile private var widgetAnchor: ClockAnchor? = null
    @Volatile private var statusBarAnchor: ClockAnchor? = null
    @Volatile private var statusClockView: WeakReference<View>? = null

    /** 锁屏时钟高度（缩放基准）；未接线时为 0，缩放退化为 1。 */
    @Volatile private var lockClockHeightPx = 0f

    /** 宿主自带桌面时钟平移（C17 灵感时钟）：为真则整条交接让位。 */
    @Volatile private var nativeClockPresent = false
    @Volatile private var lastHandoffSent = Float.NaN
    @Volatile private var lastHandoffEnabledSent = false
    private val handoffSends = AtomicLong()

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
            logThrottled("dc-bridge-dup", "已挂载，跳过 recv=${receives.get()}")
            return
        }
        this.api = api
        refreshGate()
        probeNativeClock(classLoader)
        val ctx = Lg.processContext()?.applicationContext
        if (ctx == null) {
            Lg.e(TAG, "dc-bridge 进程 Context 为空，未注册接收器")
            return
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                try {
                    handle(this, intent)
                } catch (t: Throwable) {
                    drops.incrementAndGet()
                    logThrottled("dc-bridge-err", "处理失败 ${t.javaClass.simpleName}: ${t.message}")
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
            installed = true
            Lg.i(TAG, "dc-bridge 已挂 action=${DesktopClockProtocol.ACTION} gate=${refreshGate()}")
        } catch (t: Throwable) {
            Lg.e(TAG, "dc-bridge 注册失败 ${t.javaClass.simpleName}: ${t.message}", t)
        }
    }

    /** 最近一次生效的 HOME 锚点（三路优先级合并后；两路皆无时 alpha=0）。 */
    fun anchorOrNull(): ClockAnchor? = lastAnchor

    fun desktopClockVisible(): Boolean = lastVisible

    /**
     * 宿主自带桌面时钟平移探针：桌面进程的灵感时钟类不在本进程 classloader 里，
     * 故只按 launcher 宿主版本查池；Launcher 侧另有类探针，两侧各自独立判定同一档。
     */
    private fun probeNativeClock(classLoader: ClassLoader) {
        val byVersion = try {
            val ver = VersionPool.readVersion(DesktopClockProtocol.LAUNCHER_PKG)
            VersionPool.query(DesktopClockProtocol.LAUNCHER_PKG, ver)
                .profile.desktopClockPath == DesktopClockPath.NATIVE_INSPIRATION
        } catch (t: Throwable) {
            false
        }
        val byClass = try {
            Class.forName(ClockHandoffPolicy.CLASS_NATIVE_CLOCK, false, classLoader)
            true
        } catch (t: Throwable) {
            false
        }
        nativeClockPresent = byVersion || byClass
        Lg.i(
            TAG,
            "dc-bridge-native present=$nativeClockPresent byVer=$byVersion byClass=$byClass " +
                "translate=${ClockHandoffPolicy.moduleTranslates(nativeClockPresent)}",
        )
    }

    /** 本模块是否该补桌面段平移（宿主自带则让位）。 */
    fun moduleTranslates(): Boolean = ClockHandoffPolicy.moduleTranslates(nativeClockPresent)

    /**
     * 交接进度下行：帧链内调用，值未变不发（同值重发等于自造跨进程轮询）。
     *
     * [edge] 为 null（静止态）时按当前静态归属发端值：桌面态 = 1，其余 = 0。
     */
    fun publishHandoff(edge: TransitionEdge?, t: Float, staticState: SceneState) {
        if (!refreshGate()) return
        val p = if (edge != null) {
            ClockHandoffPolicy.progress(edge, t)
        } else if (staticState == SceneState.HOME) {
            1f
        } else {
            0f
        }
        val enabled = moduleTranslates() && widgetAnchor != null
        if (!ClockHandoffPolicy.shouldPublish(p, lastHandoffSent, enabled, lastHandoffEnabledSent)) return
        lastHandoffSent = p
        lastHandoffEnabledSent = enabled
        sendHandoff(p, enabled, edge?.name ?: "static:$staticState")
    }

    /**
     * 撤效果时把桌面小部件还原：强制发一次使能位 false，桌面进程按记账回写原 alpha。
     *
     * 不走 [publishHandoff] 的值判重，避免「上一次也是 false」被判成无变化而不发。
     */
    fun releaseHandoff(reason: String) {
        if (!lastHandoffEnabledSent && lastHandoffSent.isNaN()) return
        lastHandoffSent = Float.NaN
        lastHandoffEnabledSent = false
        sendHandoff(0f, false, "release:$reason")
    }

    private fun sendHandoff(p: Float, enabled: Boolean, reason: String) {
        val ctx = Lg.processContext()?.applicationContext ?: run {
            logThrottled("dc-handoff-ctx", "Context 为空，交接未发送")
            return
        }
        try {
            val intent = Intent(DesktopClockProtocol.ACTION).apply {
                setPackage(DesktopClockProtocol.LAUNCHER_PKG)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                putExtra(DesktopClockProtocol.EXTRA_DIRECTION, DesktopClockProtocol.DIR_HANDOFF)
                putExtra(DesktopClockProtocol.EXTRA_HANDOFF, p)
                putExtra(DesktopClockProtocol.EXTRA_HANDOFF_ENABLED, enabled)
                putExtra(DesktopClockProtocol.EXTRA_REASON, reason)
            }
            ctx.sendBroadcast(intent)
            handoffSends.incrementAndGet()
            logThrottled(
                "dc-handoff-pub",
                String.format(
                    Locale.US, "p=%.3f on=%b reason=%s sends=%d",
                    p, enabled, reason, handoffSends.get(),
                ),
            )
        } catch (t: Throwable) {
            logThrottled("dc-handoff-send", "交接发送失败 ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** 锁屏时钟高度接入点：由锁屏时钟锚点链在测到高度后回填，状态栏小目标的缩放才正确。 */
    fun setLockClockHeightPx(px: Float) {
        if (px <= 0f || px == lockClockHeightPx) return
        lockClockHeightPx = px
        logThrottled("dc-lock-height", "锁屏时钟高度=${px}")
        recomputeStatusBarAnchor()
    }

    /** 状态栏时钟矩形刷新（SystemUI 进程内直调）：[hidden] 表示该视图已不可见/已移除。 */
    fun onStatusBarClock(view: View, reason: String, hidden: Boolean = false) {
        if (!refreshGate()) return
        if (hidden) {
            statusClockView = null
            if (statusBarAnchor != null) {
                statusBarAnchor = null
                logThrottled("dc-status-hide", "状态栏时钟不可见 reason=$reason")
                applyHomeAnchor("status-hide")
            }
            return
        }
        val anchor = measureStatusBarClock(view) ?: return
        statusClockView = WeakReference(view)
        statusBarAnchor = anchor
        logThrottled(
            "dc-status-set",
            String.format(
                Locale.US,
                "状态栏时钟锚点=(%.4f,%.4f) s=%.3f reason=%s",
                anchor.centerX, anchor.centerY, anchor.scale, reason,
            ),
        )
        applyHomeAnchor(reason)
    }

    private fun recomputeStatusBarAnchor() {
        val view = statusClockView?.get()
        if (view == null) {
            applyHomeAnchor("lock-height-noview")
            return
        }
        statusBarAnchor = measureStatusBarClock(view)
        applyHomeAnchor("lock-height")
    }

    private fun measureStatusBarClock(view: View): ClockAnchor? {
        if (!view.isShown) return null
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) {
            logThrottled("dc-status-size", "状态栏时钟布局未完成 ${w}x$h")
            return null
        }
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val root = view.rootView
        val screenW = root.width.takeIf { it > 0 } ?: return null
        val screenH = root.height.takeIf { it > 0 } ?: return null
        val rect = WidgetRectPx(loc[0], loc[1], loc[0] + w, loc[1] + h)
        val anchor = ClockAnchorMapper.fromTargetRect(rect, screenW, screenH, lockClockHeightPx)
        if (anchor == null) {
            logThrottled("dc-status-rect", "状态栏时钟矩形非法 $rect screen=${screenW}x$screenH")
            return null
        }
        if (anchor.centerY > STATUS_BAR_MAX_CENTER_Y) {
            logThrottled("dc-status-pos", "非状态栏区域 centerY=${anchor.centerY}")
            return null
        }
        return anchor
    }

    /** 三路优先级合并后写进状态机；widget 优先于状态栏时钟，二者皆无则淡出。 */
    private fun applyHomeAnchor(reason: String) {
        val effective = ClockAnchorMapper.pickHomeAnchor(widgetAnchor, statusBarAnchor)
        machine.setAnchor(SceneState.HOME, effective, SystemClock.uptimeMillis())
        lastAnchor = effective
        lastVisible = effective.alpha > 0.001f
        logThrottled(
            "dc-home",
            String.format(
                Locale.US,
                "HOME=(%.4f,%.4f) s=%.3f a=%.1f widget=%b status=%b reason=%s",
                effective.centerX, effective.centerY, effective.scale, effective.alpha,
                widgetAnchor != null, statusBarAnchor != null, reason,
            ),
        )
    }

    private fun handle(receiver: BroadcastReceiver, intent: Intent?) {
        receives.incrementAndGet()
        if (intent == null) return
        if (!refreshGate()) {
            drops.incrementAndGet()
            logThrottled("dc-bridge-gate", "门控关闭，丢弃 drop=${drops.get()}")
            return
        }
        val dir = intent.getIntExtra(
            DesktopClockProtocol.EXTRA_DIRECTION, DesktopClockProtocol.DIR_ANCHOR,
        )
        if (dir != DesktopClockProtocol.DIR_ANCHOR) return
        val from = senderPackage(receiver)
        if (from != null && from != DesktopClockProtocol.LAUNCHER_PKG) {
            drops.incrementAndGet()
            logThrottled("dc-bridge-sender", "非 launcher 来源丢弃 from=$from drop=${drops.get()}")
            return
        }
        val visible = intent.getBooleanExtra(DesktopClockProtocol.EXTRA_VISIBLE, false)
        val cx = intent.getFloatExtra(DesktopClockProtocol.EXTRA_CENTER_X, 0.5f)
        val cy = intent.getFloatExtra(DesktopClockProtocol.EXTRA_CENTER_Y, 0.5f)
        val scale = intent.getFloatExtra(DesktopClockProtocol.EXTRA_SCALE, 1f)
        val widgetW = intent.getIntExtra(DesktopClockProtocol.EXTRA_WIDGET_W, 0)
        val widgetH = intent.getIntExtra(DesktopClockProtocol.EXTRA_WIDGET_H, 0)
        val screenW = intent.getIntExtra(DesktopClockProtocol.EXTRA_SCREEN_W, 0)
        val screenH = intent.getIntExtra(DesktopClockProtocol.EXTRA_SCREEN_H, 0)
        val reason = intent.getStringExtra(DesktopClockProtocol.EXTRA_REASON) ?: "?"
        if (visible) {
            if (!cx.isFinite() || !cy.isFinite() || !scale.isFinite()) {
                drops.incrementAndGet()
                logThrottled("dc-bridge-nan", "锚点数值非法 cx=$cx cy=$cy s=$scale drop=${drops.get()}")
                return
            }
            widgetAnchor = ClockAnchor(
                centerX = cx.coerceIn(0f, 1f),
                centerY = cy.coerceIn(0f, 1f),
                scale = scale.coerceIn(ClockAnchorMapper.SCALE_MIN, ClockAnchorMapper.SCALE_MAX),
                alpha = 1f,
            )
        } else {
            widgetAnchor = null
        }
        applyHomeAnchor(reason)
        lastHandoffSent = Float.NaN
        logThrottled(
            if (visible) "dc-bridge-set" else "dc-bridge-hide",
            String.format(
                Locale.US,
                "vis=%b widget=%dx%d screen=%dx%d from=%s reason=%s recv=%d drop=%d",
                visible, widgetW, widgetH, screenW, screenH, from ?: "?", reason,
                receives.get(), drops.get(),
            ),
        )
    }

    /** API 34+ 取广播来源包名；取不到（旧版本/反射失败）返回 null 表示放行。 */
    private fun senderPackage(receiver: BroadcastReceiver): String? = try {
        if (Build.VERSION.SDK_INT >= 34) receiver.sentFromPackage else null
    } catch (t: Throwable) {
        logThrottled("dc-bridge-from", "来源包名不可用 ${t.javaClass.simpleName}")
        null
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
            logThrottled("dc-bridge-gate-read", "读键失败 ${t.javaClass.simpleName} 保持 $prev")
            prev
        }
        if (prev != gateEnabled) Lg.i(TAG, "dc-bridge-gate $prev -> $gateEnabled")
        return gateEnabled
    }
}
