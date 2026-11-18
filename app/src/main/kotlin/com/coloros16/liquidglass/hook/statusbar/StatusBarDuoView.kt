package com.coloros16.liquidglass.hook.statusbar

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.coloros16.liquidglass.log.Lg
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy
import java.util.WeakHashMap

/**
 * 三合一状态图标：淡环 = 总电量轨道（开口弧，缺口朝下），实环 = 当前电量（锚定轨道起点顺时针填充，充电绿 / 低电红 / 省电黄 / 常态 tint）；
 * 环内上方 = 电量数字（同实环状态色）；中心 = 网络（Wi-Fi 弧按强度分层，否则移动信号柱），底部 = Wi-Fi 时四点为移动运营商信号强度、蜂窝时网络类型文字。
 *
 * 只做绘制与自身数据采样（1s 周期，仅在挂载期间运行），不写系统状态；
 * 挂载/卸载由 [StatusBarDuoHost] 负责，原系统电池/移动/Wi-Fi 图标由改动器隐藏并可在关闭时还原。
 */
class StatusBarDuoView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val sizePx = (26f * density).toInt()

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.6f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.0f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val netTypePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 7f * density
    }
    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 6.5f * density
    }
    private val arcRect = RectF()
    private val arcPath = Path()

    private var tint = -1
    private var tintTarget = -1
    private var displayedTint = -1
    private var batteryPercent = 0
    private var charging = false
    private var wifiConnected = false
    private var wifiLevel = 0
    private var cellPresent = false
    private var cellLevel = 0
    private var netType = ""
    private var netTypeCached = ""
    private var netTypeReadAt = 0L
    @Volatile
    private var powerSave = false

    @Volatile
    private var powerSaveReadAt = 0L

    private val handler = Handler(Looper.getMainLooper())
    private var refreshAliveLogged = false

    private var wasCharging = false
    private var chargeAnimStartAt = 0L
    private var displayedArcColor = -1

    private val chargeAnimTask = object : Runnable {
        override fun run() {
            if (chargeAnimStartAt <= 0L) return
            invalidate()
            if (SystemClock.uptimeMillis() - chargeAnimStartAt >= CHARGE_ANIM_TOTAL_MS) {
                chargeAnimStartAt = 0L
                return
            }
            handler.postDelayed(this, CHARGE_ANIM_FRAME_MS)
        }
    }

    private val tintAnimTask = object : Runnable {
        override fun run() {
            val target = tintTarget
            if (target < 0) return
            val next = lerpColor(tint, target, COLOR_LERP_STEP)
            tint = if (colorNear(next, target)) target else next
            displayedTint = tint
            invalidate()
            if (tint != target) handler.postDelayed(this, CHARGE_ANIM_FRAME_MS)
        }
    }

    private val colorAnimTask = object : Runnable {
        override fun run() {
            stepArcColor()
            invalidate()
            if (displayedArcColor != arcColor(batteryPercent.coerceIn(0, 100))) {
                handler.postDelayed(this, CHARGE_ANIM_FRAME_MS)
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sampleSafely()
        onViewAttached(this)
    }

    /** 共享状态源事件入口（电池/插拔/省电/网络）。 */
    internal fun onSourceEvent(action: String, intent: Intent?) {
        when (action) {
            Intent.ACTION_BATTERY_CHANGED -> if (intent != null) applyBatteryIntent(intent, "broadcast")
            Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED -> {
                Lg.i(TAG, "sb-duo power-edge plugged=${action == Intent.ACTION_POWER_CONNECTED}")
                val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                if (sticky != null) applyBatteryIntent(sticky, "power-edge")
            }
            PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                powerSaveReadAt = 0L
                samplePowerSave()
                Lg.i(TAG, "sb-duo powersave-broadcast on=$powerSave")
            }
            else -> {
                netTypeReadAt = 0L
                wifiConnected = StatusBarState.wifiConnected()
                wifiLevel = if (wifiConnected) sampleWifiLevel() else 0
                sampleCell()
                sampleNetType()
                Lg.i(TAG, "sb-duo net-broadcast act=${action.substringAfterLast('.')} wifi=$wifiConnected/$wifiLevel cell=$cellPresent/$cellLevel")
            }
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        onViewDetached(this)
        handler.removeCallbacks(chargeAnimTask)
        handler.removeCallbacks(colorAnimTask)
        handler.removeCallbacks(tintAnimTask)
        chargeAnimStartAt = 0L
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) {
            powerSaveReadAt = 0L
            sampleSafely()
            invalidate()
        }
    }

    /**
     * 单帧刷新（共享采样器驱动，仅可见实例）：只重绘 + 读省电 TTL；
     * 电量/充电/网络由广播即时更新，不在此周期拉取。
     */
    private fun tick() {
        if (!refreshAliveLogged) {
            refreshAliveLogged = true
            Lg.i(TAG, "sb-duo refresh alive")
        }
        try {
            samplePowerSave()
        } catch (t: Throwable) {
            Lg.i(TAG, "sb-duo-src powersave tick ${t.javaClass.simpleName}")
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(sizePx, sizePx)
    }

    fun preferredSizePx(): Int = sizePx

    /** 系统暗色分发入口：目标色变化走过渡，不硬切。 */
    fun setTint(color: Int) {
        if (tintTarget == color) return
        tintTarget = color
        if (tint < 0) {
            tint = color
            displayedTint = color
            invalidate()
            return
        }
        handler.removeCallbacks(tintAnimTask)
        handler.post(tintAnimTask)
    }

    /** 挂载时主动取一次系统当前图标色（错过的 onDarkChanged 不补发）。 */
    fun syncInitialTint(fallback: Int) {
        if (tint >= 0) return
        tint = fallback
        tintTarget = fallback
        displayedTint = fallback
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (tint < 0) {
            tint = Color.WHITE
            tintTarget = Color.WHITE
            displayedTint = Color.WHITE
        }
        val cx = width / 2f
        val cy = height / 2f
        val radius = (minOf(width, height) / 2f) - ringPaint.strokeWidth
        if (radius <= 0f) return

        val level = batteryPercent.coerceIn(0, 100)
        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        val arcColorNow = if (displayedArcColor >= 0) displayedArcColor else arcColor(level)

        ringPaint.color = tint
        ringPaint.alpha = TRACK_ALPHA
        canvas.drawArc(arcRect, TRACK_START_DEG, TRACK_SPAN_DEG, false, ringPaint)

        ringPaint.color = arcColorNow
        ringPaint.alpha = 255
        val sweep = (TRACK_SPAN_DEG * level / 100f).coerceIn(MIN_SWEEP_DEG, TRACK_SPAN_DEG)
        canvas.drawArc(arcRect, TRACK_START_DEG, sweep, false, ringPaint)

        levelPaint.color = arcColorNow
        val levelBaseline = cy - radius * 0.45f + levelPaint.textSize * 0.36f
        canvas.drawText(level.toString(), cx, levelBaseline, levelPaint)

        val inner = radius - 2.0f * density
        val now = SystemClock.uptimeMillis()
        val animT = if (chargeAnimStartAt > 0L) now - chargeAnimStartAt else -1L
        val netAlpha = chargeNetAlphaAt(animT)
        if (netAlpha > 0) {
            if (netAlpha >= 255) {
                drawNetwork(canvas, cx, cy, radius, inner)
            } else {
                val save = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), netAlpha)
                drawNetwork(canvas, cx, cy, radius, inner)
                canvas.restoreToCount(save)
            }
        }
        val boltAlpha = chargeBoltAlphaAt(animT)
        if (boltAlpha > 0) drawChargeBolt(canvas, cx, cy, inner * 0.95f, boltAlpha)
    }

    private fun drawNetwork(canvas: Canvas, cx: Float, cy: Float, radius: Float, inner: Float) {
        if (wifiConnected) {
            drawWifi(canvas, cx, cy + 0.42f * inner, inner, wifiLevel)
            if (cellPresent) {
                drawDots(canvas, cx, cy + radius * 0.70f, cellLevel.coerceIn(0, 4))
            }
        } else if (cellPresent) {
            drawCellBars(canvas, cx, cy + 0.09f * inner, inner)
            if (netType.isNotEmpty()) {
                netTypePaint.color = tint
                val baseline = cy + radius * 0.62f + netTypePaint.textSize * 0.34f
                canvas.drawText(netType, cx, baseline, netTypePaint)
            }
        }
    }

    private fun arcColor(level: Int): Int = when {
        level <= LOW_BATTERY_PCT -> COLOR_LOW
        powerSave -> COLOR_SAVE
        charging -> COLOR_CHARGING
        else -> tint
    }


    private fun stepArcColor() {
        val target = arcColor(batteryPercent.coerceIn(0, 100))
        val cur = displayedArcColor
        if (cur < 0) {
            displayedArcColor = target
            return
        }
        if (cur == target) return
        val next = lerpColor(cur, target, COLOR_LERP_STEP)
        displayedArcColor = if (colorNear(next, target)) target else next
    }

    private fun lerpColor(from: Int, to: Int, t: Float): Int {
        val r = (Color.red(from) + (Color.red(to) - Color.red(from)) * t + 0.5f).toInt()
        val g = (Color.green(from) + (Color.green(to) - Color.green(from)) * t + 0.5f).toInt()
        val b = (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t + 0.5f).toInt()
        val a = (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t + 0.5f).toInt()
        return Color.argb(a, r, g, b)
    }

    private fun colorNear(a: Int, b: Int): Boolean =
        Math.abs(Color.red(a) - Color.red(b)) <= 2 &&
            Math.abs(Color.green(a) - Color.green(b)) <= 2 &&
            Math.abs(Color.blue(a) - Color.blue(b)) <= 2 &&
            Math.abs(Color.alpha(a) - Color.alpha(b)) <= 2

    private fun drawWifi(canvas: Canvas, cx: Float, cy: Float, radius: Float, level: Int) {
        glyphPaint.color = tint
        glyphPaint.alpha = 255
        val base = radius * 0.45f
        val arcs = level.coerceIn(0, 3)
        for (i in 1..arcs) {
            val r = base * i / 3f
            arcPath.reset()
            arcPath.addArc(cx - r, cy - r, cx + r, cy + r, 208f, 124f)
            canvas.drawPath(arcPath, glyphPaint)
        }
        fillPaint.color = tint
        fillPaint.alpha = if (level <= 0) 70 else 255
        canvas.drawCircle(cx, cy + base * 0.10f, 1.1f * density, fillPaint)
    }

    private fun drawCellBars(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        fillPaint.color = tint
        fillPaint.alpha = 255
        val barWidth = 1.5f * density
        val gap = barWidth * 1.5f
        val startX = cx - gap * 1.5f
        val maxHeight = radius * 0.36f
        val bottom = cy + maxHeight / 2f
        for (i in 0 until 4) {
            val h = maxHeight * (0.34f + 0.22f * i)
            val x = startX + gap * i
            canvas.drawRect(x - barWidth / 2f, bottom - h, x + barWidth / 2f, bottom, fillPaint)
        }
    }

    private fun drawDots(canvas: Canvas, cx: Float, cy: Float, level: Int) {
        val r = 1.0f * density
        val gap = 2.6f * density
        val startX = cx - gap * 1.5f
        fillPaint.color = tint
        for (i in 0 until 4) {
            fillPaint.alpha = if (i < level) 255 else 70
            canvas.drawCircle(startX + gap * i, cy, r, fillPaint)
        }
        fillPaint.alpha = 255
    }

    private fun chargeNetAlphaAt(animT: Long): Int = when {
        animT < 0L -> 255
        animT < CHARGE_FADE_OUT_MS -> (255 * (CHARGE_FADE_OUT_MS - animT) / CHARGE_FADE_OUT_MS).toInt()
        animT < CHARGE_BOLT_END_MS -> 0
        animT < CHARGE_ANIM_TOTAL_MS ->
            (255 * (animT - CHARGE_BOLT_END_MS) / CHARGE_FADE_IN_MS).toInt()
        else -> 255
    }

    private fun chargeBoltAlphaAt(animT: Long): Int = when {
        animT < CHARGE_FADE_OUT_MS -> 0
        animT < CHARGE_BOLT_IN_END_MS ->
            (255 * (animT - CHARGE_FADE_OUT_MS) / CHARGE_BOLT_IN_MS).toInt()
        animT < CHARGE_BOLT_HOLD_END_MS -> 255
        animT < CHARGE_BOLT_END_MS ->
            (255 * (CHARGE_BOLT_END_MS - animT) / CHARGE_BOLT_OUT_MS).toInt()
        else -> 0
    }

    private fun drawChargeBolt(canvas: Canvas, cx: Float, cy: Float, size: Float, alpha: Int) {
        fillPaint.color = COLOR_CHARGING
        fillPaint.alpha = alpha
        val path = Path()
        path.moveTo(cx + 0.12f * size, cy - 0.50f * size)
        path.lineTo(cx - 0.30f * size, cy + 0.06f * size)
        path.lineTo(cx - 0.04f * size, cy + 0.06f * size)
        path.lineTo(cx - 0.12f * size, cy + 0.50f * size)
        path.lineTo(cx + 0.30f * size, cy - 0.06f * size)
        path.lineTo(cx + 0.04f * size, cy - 0.06f * size)
        path.close()
        canvas.drawPath(path, fillPaint)
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
        try {
            sampleNetType()
            samplePowerSave()
        } catch (t: Throwable) {
            Lg.i(TAG, "sb-duo-src state 失败 ${t.javaClass.simpleName}")
        }
        onStateChanged()
    }

    private fun sampleNetType() {
        val id = StatusBarCompat.id("mobile_type")
        if (id == 0) {
            netType = ""
            return
        }
        val v = rootView.findViewById<View>(id)
        netType = if (v != null && v.visibility == VISIBLE) {
            (v as? TextView)?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: networkTypeLabel()
        } else {
            ""
        }
    }

    private fun networkTypeLabel(): String {
        val now = SystemClock.uptimeMillis()
        if (now - netTypeReadAt < NETTYPE_TTL_MS) return netTypeCached
        netTypeReadAt = now
        netTypeCached = try {
            val tm = context.getSystemService(TelephonyManager::class.java)
            val subId = StatusBarState.defaultDataSubId()
            val target = if (subId >= 0) tm?.createForSubscriptionId(subId) else tm
            when (target?.dataNetworkType) {
                TelephonyManager.NETWORK_TYPE_NR -> "5G"
                TelephonyManager.NETWORK_TYPE_LTE -> "4G"
                else -> ""
            }
        } catch (t: Throwable) {
            Lg.i(TAG, "sb-duo-src nettype 失败 ${t.javaClass.simpleName}")
            ""
        }
        return netTypeCached
    }

    /** 省电判定：TTL 内读缓存；实测失败保留旧值（不回 false，避免闪色）。 */
    private fun samplePowerSave() {
        val now = SystemClock.uptimeMillis()
        val ttl = if (powerSave) POWER_SAVE_ON_RECHECK_MS else POWER_SAVE_TTL_MS
        if (now - powerSaveReadAt < ttl) return
        powerSaveReadAt = now
        val v = try {
            val pm = context.applicationContext
                .getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isPowerSaveMode ?: powerSave
        } catch (t: Throwable) {
            Lg.i(TAG, "sb-duo-src powersave 失败 ${t.javaClass.simpleName}")
            powerSave
        }
        if (v != powerSave) {
            Lg.i(TAG, "sb-duo powersave=$v")
            powerSave = v
            onStateChanged()
        }
    }



    /** 状态色目标变化时驱动过渡动画（采样与广播共用）。 */
    private fun onStateChanged() {
        val target = arcColor(batteryPercent.coerceIn(0, 100))
        if (displayedArcColor < 0) {
            displayedArcColor = target
        } else if (displayedArcColor != target) {
            handler.removeCallbacks(colorAnimTask)
            handler.post(colorAnimTask)
        }
        invalidate()
    }

    private fun sampleBattery() {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        applyBatteryIntent(intent, "sample")
    }

    /** 电池意图落地：电量/充电态更新，插电上升沿起充电动画，色变走过渡。 */
    private fun applyBatteryIntent(intent: Intent, from: String) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val oldPct = batteryPercent
        if (level >= 0 && scale > 0) batteryPercent = level * 100 / scale
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val nowCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val edge = nowCharging && !wasCharging
        charging = nowCharging
        wasCharging = nowCharging
        if (edge) {
            Lg.i(TAG, "sb-duo charge-edge from=$from status=$status level=$batteryPercent")
            chargeAnimStartAt = SystemClock.uptimeMillis()
            handler.removeCallbacks(chargeAnimTask)
            handler.post(chargeAnimTask)
        }
        if (edge || oldPct != batteryPercent) onStateChanged()
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
        private const val REFRESH_MS = 30_000L
        private const val LOW_BATTERY_PCT = 20
        private const val POWER_SAVE_TTL_MS = 60_000L
        private const val POWER_SAVE_ON_RECHECK_MS = 10_000L
        private const val NETTYPE_TTL_MS = 10_000L
        private const val TRACK_ALPHA = 55
        private const val TRACK_START_DEG = 150f
        private const val TRACK_SPAN_DEG = 240f
        private const val MIN_SWEEP_DEG = 60f
        private const val COLOR_CHARGING = 0xFF34C759.toInt()
        private const val COLOR_LOW = 0xFFFF3B30.toInt()
        private const val COLOR_SAVE = 0xFFFFCC00.toInt()
        private const val CHARGE_FADE_OUT_MS = 300L
        private const val CHARGE_BOLT_IN_MS = 200L
        private const val CHARGE_HOLD_MS = 1000L
        private const val CHARGE_BOLT_OUT_MS = 200L
        private const val CHARGE_FADE_IN_MS = 300L
        private const val CHARGE_BOLT_IN_END_MS = CHARGE_FADE_OUT_MS + CHARGE_BOLT_IN_MS
        private const val CHARGE_BOLT_HOLD_END_MS = CHARGE_BOLT_IN_END_MS + CHARGE_HOLD_MS
        private const val CHARGE_BOLT_END_MS = CHARGE_BOLT_HOLD_END_MS + CHARGE_BOLT_OUT_MS
        private const val CHARGE_ANIM_TOTAL_MS = CHARGE_BOLT_END_MS + CHARGE_FADE_IN_MS
        private const val CHARGE_ANIM_FRAME_MS = 16L
        private const val COLOR_LERP_STEP = 0.22f

        /** 共享 1s 采样器：全部挂载实例共用一个循环，轮询数不随实例数增长。 */
        private val attachedViews = ArrayList<WeakReference<StatusBarDuoView>>()
        private val sharedHandler = Handler(Looper.getMainLooper())
        private var sharedRunning = false
        private val sharedTask = object : Runnable {
            override fun run() {
                for (i in attachedViews.size - 1 downTo 0) {
                    val v = attachedViews[i].get()
                    if (v == null) {
                        attachedViews.removeAt(i)
                        continue
                    }
                    if (v.isShown) v.tick()
                }
                if (attachedViews.isNotEmpty()) {
                    sharedHandler.postDelayed(this, REFRESH_MS)
                } else {
                    sharedRunning = false
                }
            }
        }

        /** 状态源共享：一套 receiver 服务全部实例，首个挂载时注册、末个卸载时注销。 */
        private var srcReceiver: android.content.BroadcastReceiver? = null
        private var srcContext: Context? = null

        private fun ensureSources(v: StatusBarDuoView) {
            if (srcReceiver != null) return
            val app = v.context.applicationContext ?: return
            try {
                val r = object : android.content.BroadcastReceiver() {
                    override fun onReceive(c: Context?, i: Intent?) {
                        val act = i?.action ?: return
                        for (k in attachedViews.size - 1 downTo 0) {
                            val t = attachedViews[k].get()
                            if (t == null) {
                                attachedViews.removeAt(k)
                                continue
                            }
                            try {
                                t.onSourceEvent(act, i)
                            } catch (th: Throwable) {
                                Lg.i(TAG, "sb-duo src-dispatch ${th.javaClass.simpleName}")
                            }
                        }
                    }
                }
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_BATTERY_CHANGED)
                    addAction(Intent.ACTION_POWER_CONNECTED)
                    addAction(Intent.ACTION_POWER_DISCONNECTED)
                    addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                    addAction(android.net.wifi.WifiManager.NETWORK_STATE_CHANGED_ACTION)
                    addAction(android.net.wifi.WifiManager.RSSI_CHANGED_ACTION)
                    addAction(android.net.ConnectivityManager.CONNECTIVITY_ACTION)
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    app.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    app.registerReceiver(r, filter)
                }
                srcReceiver = r
                srcContext = app
                Lg.i(TAG, "sb-duo src-receiver registered (shared)")
            } catch (t: Throwable) {
                Lg.i(TAG, "sb-duo src-receiver 注册失败 ${t.javaClass.simpleName}")
            }
        }

        private fun releaseSources() {
            val r = srcReceiver ?: return
            srcReceiver = null
            val c = srcContext
            srcContext = null
            try {
                c?.unregisterReceiver(r)
                Lg.i(TAG, "sb-duo src-receiver unregistered")
            } catch (_: Throwable) {
            }
        }

        private fun onViewAttached(v: StatusBarDuoView) {
            attachedViews.add(WeakReference(v))
            ensureSources(v)
            if (!sharedRunning) {
                sharedRunning = true
                sharedHandler.postDelayed(sharedTask, REFRESH_MS)
            }
        }

        private fun onViewDetached(v: StatusBarDuoView) {
            for (i in attachedViews.size - 1 downTo 0) {
                val ref = attachedViews[i]
                if (ref.get() === v || ref.get() == null) attachedViews.removeAt(i)
            }
            if (attachedViews.isEmpty()) {
                sharedRunning = false
                sharedHandler.removeCallbacks(sharedTask)
                releaseSources()
            }
        }
    }
}

/**
 * 三合一图标的挂载宿主：插入右侧容器末尾；tint 接系统暗色分发。
 * 原电池/移动/Wi-Fi 图标的隐藏由 [StatusBarEnforcer] 统一负责（配置关闭时可还原）。
 */
object StatusBarDuoHost {

    private const val TAG = "LgStatusBar"

    private const val TREE_LOG_GAP_MS = 5000L

    private const val DUO_CLASS_NAME = "com.coloros16.liquidglass.hook.statusbar.StatusBarDuoView"

    /** 已挂载实例（普通树与锁屏树各一，多宿主并存）。 */
    private val mountedViews = ArrayList<WeakReference<StatusBarDuoView>>()

    @Volatile
    private var dispatcher: Any? = null

    private val tintProxies = WeakHashMap<StatusBarDuoView, Any>()

    /** DarkIconDispatcherImpl 实例（构造 hook 捕获）。 */
    fun onDispatcher(instance: Any) {
        if (dispatcher === instance) return
        val old = dispatcher
        dispatcher = instance
        for (ref in mountedViews) {
            val v = ref.get() ?: continue
            if (old != null) {
                tintProxies.remove(v)?.let { p -> removeDarkReceiver(old, v, p) }
            }
            registerTint(v)
        }
    }

    /** 按配置挂载/卸载；每次根视图布局后调用（幂等，按类名识别跨热重载残留，多宿主并存）。 */
    fun sync(root: View, cfg: StatusBarConfig) {
        dumpStructure(root)
        val host = findHost(root)
        if (host == null) {
            if (!cfg.duoEnable) {
                detach()
                return
            }
            logOnce("sb-duo-host", "sb-skip duo 容器缺失")
            return
        }
        var selfInHost = false
        for (i in host.childCount - 1 downTo 0) {
            val c = host.getChildAt(i) ?: continue
            if (c.javaClass.name != DUO_CLASS_NAME) continue
            if (c.javaClass === StatusBarDuoView::class.java) {
                selfInHost = true
                continue
            }
            try {
                host.removeView(c)
                Lg.i(TAG, "sb-duo-unmount")
            } catch (_: Throwable) {
            }
        }
        if (!cfg.duoEnable) {
            detach()
            return
        }
        if (selfInHost) return
        try {
            val view = StatusBarDuoView(root.context)
            val size = view.preferredSizePx()
            val lp = LinearLayout.LayoutParams(size, size)
            lp.gravity = Gravity.CENTER_VERTICAL
            host.addView(view, host.childCount, lp)
            mountedViews.add(WeakReference(view))
            view.syncInitialTint(readSystemIconTint(host))
            registerTint(view)
            Lg.i(TAG, "sb-duo-mount host=${host.javaClass.simpleName} size=$size")
        } catch (t: Throwable) {
            Lg.i(TAG, "sb-skip duo mount ${t.javaClass.simpleName}")
        }
    }

    /** 宿主查找：普通树 end_side_content；锁屏树回退 status_icon_area；下拉顶栏回退 icons（三树 id 互斥）。 */
    private fun findHost(root: View): ViewGroup? {
        val endId = StatusBarCompat.id("status_bar_end_side_content")
        if (endId != 0) {
            (root.findViewById<View>(endId) as? ViewGroup)?.let { return it }
        }
        val kgId = StatusBarCompat.id("status_icon_area")
        if (kgId != 0) {
            (root.findViewById<View>(kgId) as? ViewGroup)?.let { return it }
        }
        val iconsId = StatusBarCompat.id("icons")
        if (iconsId != 0) {
            (root.findViewById<View>(iconsId) as? ViewGroup)?.let { return it }
        }
        return null
    }

    fun detach() {
        for (ref in mountedViews) {
            val view = ref.get() ?: continue
            unregisterTint(view)
            try {
                (view.parent as? ViewGroup)?.removeView(view)
                Lg.i(TAG, "sb-duo-unmount")
            } catch (_: Throwable) {
            }
        }
        mountedViews.clear()
    }

    /** 三合一是否在位（hook 侧拦截闸门）。 */
    fun isMounted(): Boolean {
        mountedViews.removeAll { it.get() == null }
        return mountedViews.isNotEmpty()
    }

    private var treeLogAt = 0L

    /** 右侧容器结构快照：类名/id/可见性/宽度/边距，定位隐藏后不收缩的占位。 */
    private fun dumpStructure(root: View) {
        val now = SystemClock.uptimeMillis()
        if (now - treeLogAt < TREE_LOG_GAP_MS) return
        treeLogAt = now
        try {
            val sb = StringBuilder(256)
            val battery = root.findViewById<View>(StatusBarCompat.id("battery"))
            sb.append(" battery=").append(battery?.visibility ?: -1)
            val end = root.findViewById<View>(StatusBarCompat.id("status_bar_end_side_container"))
            if (end is ViewGroup) {
                walkTree(end, 0, sb)
            } else {
                val area = root.findViewById<View>(StatusBarCompat.id("status_icon_area"))
                if (area is ViewGroup) {
                    sb.append(" area=")
                    walkTree(area, 0, sb)
                } else {
                    sb.append(" end=null")
                }
            }
            Lg.i(TAG, "sb-duo-tree$sb")
        } catch (t: Throwable) {
            Lg.i(TAG, "sb-duo-tree 失败 ${t.javaClass.simpleName}")
        }
    }

    private fun walkTree(v: View, depth: Int, sb: StringBuilder) {
        if (depth > 6 || sb.length > 3000) return
        sb.append('\n').append("  ".repeat(depth))
        sb.append(v.javaClass.simpleName)
        try {
            sb.append('#').append(v.resources.getResourceEntryName(v.id))
        } catch (_: Throwable) {
            sb.append("#?")
        }
        sb.append(" vis=").append(
            if (v.visibility == View.VISIBLE) "V" else if (v.visibility == View.INVISIBLE) "I" else "G",
        )
        sb.append(" w=").append(v.width).append(" x=").append(v.x)
        (v.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            sb.append(" lpw=").append(it.width).append(" m=").append(it.leftMargin).append(',').append(it.rightMargin)
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) v.getChildAt(i)?.let { walkTree(it, depth + 1, sb) }
        }
    }

    /** 挂载即时色：读 dispatcher 当前暗色态，失败回退白（后续 onDarkChanged 会纠正）。 */
    private fun readSystemIconTint(host: ViewGroup): Int {
        val d = dispatcher ?: return android.graphics.Color.WHITE
        return try {
            val m = d.javaClass.methods.firstOrNull {
                it.name == "getTint" && it.parameterCount == 2
            }
            val area = host.getChildAt(0) ?: host
            val v = m?.invoke(d, area, null) as? Int
            if (v != null && v != 0) v else android.graphics.Color.WHITE
        } catch (_: Throwable) {
            android.graphics.Color.WHITE
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
            tintProxies[view] = proxy
            Lg.i(TAG, "sb-duo-tint registered")
        } catch (t: Throwable) {
            Lg.i(TAG, "sb-duo-tint 注册失败 ${t.javaClass.simpleName}")
        }
    }

    private fun unregisterTint(view: StatusBarDuoView) {
        val d = dispatcher ?: return
        val p = tintProxies.remove(view) ?: return
        removeDarkReceiver(d, view, p)
    }

    private fun removeDarkReceiver(d: Any, view: StatusBarDuoView, proxy: Any) {
        try {
            val cl = view.javaClass.classLoader ?: return
            val iface = Class.forName("com.android.systemui.plugins.DarkIconDispatcher\$DarkReceiver", false, cl)
            d.javaClass.getMethod("removeDarkReceiver", iface).invoke(d, proxy)
        } catch (_: Throwable) {
        }
    }

    private val logged = HashSet<String>()

    private fun logOnce(key: String, msg: String) {
        if (logged.add(key)) Lg.i(TAG, msg)
    }
}
