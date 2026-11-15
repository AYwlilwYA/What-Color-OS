package com.coloros16.liquidglass.hook.statusbar

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.coloros16.liquidglass.log.Lg
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy

/**
 * 三合一状态图标：外圈 = 电量，中心 = 网络（Wi-Fi 优先，否则移动信号），下方四点 = 信号强度。
 *
 * 只做绘制与自身数据采样（1s 周期，仅在挂载期间运行），不写系统状态；
 * 挂载/卸载由 [StatusBarDuoHost] 负责，原系统电池/移动/Wi-Fi 图标由改动器隐藏并可在关闭时还原。
 */
class StatusBarDuoView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val sizePx = (21f * density).toInt()

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.1f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.3f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val arcRect = RectF()
    private val arcPath = Path()

    private var tint = Color.WHITE
    private var batteryPercent = 0
    private var charging = false
    private var wifiConnected = false
    private var wifiLevel = 0
    private var cellPresent = false
    private var cellLevel = 0

    private val handler = Handler(Looper.getMainLooper())
    private val refreshTask = object : Runnable {
        override fun run() {
            sampleSafely()
            invalidate()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sampleSafely()
        handler.removeCallbacks(refreshTask)
        handler.postDelayed(refreshTask, REFRESH_MS)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(refreshTask)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(sizePx, sizePx)
    }

    fun preferredSizePx(): Int = sizePx

    fun setTint(color: Int) {
        if (tint != color) {
            tint = color
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = (minOf(width, height) / 2f) - ringPaint.strokeWidth
        if (radius <= 0f) return

        ringPaint.color = tint
        ringPaint.alpha = 55
        canvas.drawCircle(cx, cy, radius, ringPaint)

        ringPaint.alpha = 255
        val sweep = 360f * batteryPercent.coerceIn(0, 100) / 100f
        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(arcRect, -90f, sweep, false, ringPaint)

        val inner = radius - 2.6f * density
        if (wifiConnected) {
            drawWifi(canvas, cx, cy - 1.2f * density, inner)
        } else if (cellPresent) {
            drawCellBars(canvas, cx, cy - 1.2f * density, inner)
        }

        val signal = (if (wifiConnected) wifiLevel else cellLevel).coerceIn(0, 4)
        drawDots(canvas, cx, cy + radius * 0.58f, signal)

        if (charging) {
            fillPaint.color = tint
            fillPaint.alpha = 255
            canvas.drawCircle(cx + radius * 0.60f, cy - radius * 0.60f, 1.5f * density, fillPaint)
        }
    }

    private fun drawWifi(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        glyphPaint.color = tint
        glyphPaint.alpha = 255
        val base = radius * 0.78f
        for (i in 1..3) {
            val r = base * i / 3f
            arcPath.reset()
            arcPath.addArc(cx - r, cy - r, cx + r, cy + r, 232f, 76f)
            canvas.drawPath(arcPath, glyphPaint)
        }
        fillPaint.color = tint
        fillPaint.alpha = 255
        canvas.drawCircle(cx, cy + base * 0.30f, 1.15f * density, fillPaint)
    }

    private fun drawCellBars(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        fillPaint.color = tint
        fillPaint.alpha = 255
        val barWidth = 1.5f * density
        val gap = barWidth * 1.5f
        val startX = cx - gap * 1.5f
        val maxHeight = radius * 0.95f
        val bottom = cy + maxHeight / 2f
        for (i in 0 until 4) {
            val h = maxHeight * (0.34f + 0.22f * i)
            val x = startX + gap * i
            canvas.drawRect(x - barWidth / 2f, bottom - h, x + barWidth / 2f, bottom, fillPaint)
        }
    }

    private fun drawDots(canvas: Canvas, cx: Float, cy: Float, level: Int) {
        val r = 1.05f * density
        val gap = 3.1f * density
        val startX = cx - gap * 1.5f
        fillPaint.color = tint
        for (i in 0 until 4) {
            fillPaint.alpha = if (i < level) 255 else 70
            canvas.drawCircle(startX + gap * i, cy, r, fillPaint)
        }
        fillPaint.alpha = 255
    }

    private fun sampleSafely() {
        try {
            sampleBattery()
        } catch (t: Throwable) {
            Lg.i(TAG, "sb-duo-src battery 失败 ${t.javaClass.simpleName}")
        }
        try {
            wifiConnected = StatusBarState.wifiConnected()
            if (wifiConnected) wifiLevel = sampleWifiLevel() else wifiLevel = 0
        } catch (t: Throwable) {
            Lg.i(TAG, "sb-duo-src wifi 失败 ${t.javaClass.simpleName}")
        }
        try {
            sampleCell()
        } catch (t: Throwable) {
            Lg.i(TAG, "sb-duo-src cell 失败 ${t.javaClass.simpleName}")
        }
    }

    private fun sampleBattery() {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level >= 0 && scale > 0) batteryPercent = level * 100 / scale
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun sampleWifiLevel(): Int = try {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        val rssi = wm?.connectionInfo?.rssi ?: return 0
        android.net.wifi.WifiManager.calculateSignalLevel(rssi, 4).coerceIn(0, 4)
    } catch (_: Throwable) {
        0
    }

    private fun sampleCell() {
        val tm = context.applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        val subId = StatusBarState.defaultDataSubId()
        val target = if (subId >= 0) tm.createForSubscriptionId(subId) else tm
        cellPresent = try {
            target.simState == TelephonyManager.SIM_STATE_READY
        } catch (_: Throwable) {
            false
        }
        if (!cellPresent) {
            cellLevel = 0
            return
        }
        cellLevel = try {
            target.signalStrength?.level?.coerceIn(0, 4) ?: 0
        } catch (_: Throwable) {
            0
        }
    }

    companion object {
        private const val TAG = "LgStatusBar"
        private const val REFRESH_MS = 1000L
    }
}

/**
 * 三合一图标的挂载宿主：插入右侧容器末尾；tint 接系统暗色分发。
 * 原电池/移动/Wi-Fi 图标的隐藏由 [StatusBarEnforcer] 统一负责（配置关闭时可还原）。
 */
object StatusBarDuoHost {

    private const val TAG = "LgStatusBar"

    private var parentRef: WeakReference<ViewGroup>? = null
    private var viewRef: WeakReference<StatusBarDuoView>? = null

    @Volatile
    private var dispatcher: Any? = null

    @Volatile
    private var receiverProxy: Any? = null

    /** DarkIconDispatcherImpl 实例（构造 hook 捕获）。 */
    fun onDispatcher(instance: Any) {
        if (dispatcher === instance) return
        dispatcher = instance
        viewRef?.get()?.let { registerTint(it) }
    }

    /** 按配置挂载/卸载；每次根视图布局后调用（幂等）。 */
    fun sync(root: View, cfg: StatusBarConfig) {
        if (!cfg.duoEnable) {
            detach()
            return
        }
        val hostId = StatusBarCompat.id("status_bar_end_side_content")
        val host = (if (hostId != 0) root.findViewById<View>(hostId) else null) as? ViewGroup
        if (host == null) {
            logOnce("sb-duo-host", "sb-skip duo 容器缺失")
            return
        }
        val existing = viewRef?.get()
        if (existing != null && existing.parent === host) return
        detach()
        try {
            val view = StatusBarDuoView(root.context)
            val size = view.preferredSizePx()
            val lp = LinearLayout.LayoutParams(size, size)
            lp.gravity = Gravity.CENTER_VERTICAL
            host.addView(view, host.childCount, lp)
            parentRef = WeakReference(host)
            viewRef = WeakReference(view)
            registerTint(view)
            Lg.i(TAG, "sb-duo-mount host=${host.javaClass.simpleName} size=$size")
        } catch (t: Throwable) {
            Lg.i(TAG, "sb-skip duo mount ${t.javaClass.simpleName}")
        }
    }

    fun detach() {
        val view = viewRef?.get()
        viewRef = null
        parentRef = null
        if (view == null) return
        unregisterTint(view)
        try {
            (view.parent as? ViewGroup)?.removeView(view)
            Lg.i(TAG, "sb-duo-unmount")
        } catch (_: Throwable) {
        }
    }

    private fun registerTint(view: StatusBarDuoView) {
        val d = dispatcher ?: return
        unregisterTint(view)
        try {
            val cl = view.javaClass.classLoader ?: return
            val iface = Class.forName("com.android.systemui.plugins.DarkIconDispatcher\$DarkReceiver", false, cl)
            val proxy = Proxy.newProxyInstance(cl, arrayOf(iface)) { _, method, args ->
                if (method.name.startsWith("onDarkChanged")) {
                    val color = args?.lastOrNull() as? Int
                    if (color != null) view.post { view.setTint(color) }
                }
                null
            }
            d.javaClass.getMethod("addDarkReceiver", iface).invoke(d, proxy)
            receiverProxy = proxy
            Lg.i(TAG, "sb-duo-tint registered")
        } catch (t: Throwable) {
            Lg.i(TAG, "sb-duo-tint 注册失败 ${t.javaClass.simpleName}")
        }
    }

    private fun unregisterTint(view: StatusBarDuoView) {
        val d = dispatcher ?: return
        val p = receiverProxy ?: return
        receiverProxy = null
        try {
            val cl = view.javaClass.classLoader ?: return
            val iface = Class.forName("com.android.systemui.plugins.DarkIconDispatcher\$DarkReceiver", false, cl)
            d.javaClass.getMethod("removeDarkReceiver", iface).invoke(d, p)
        } catch (_: Throwable) {
        }
    }

    private val logged = HashSet<String>()

    private fun logOnce(key: String, msg: String) {
        if (logged.add(key)) Lg.i(TAG, msg)
    }
}
