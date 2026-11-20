package com.coloros16.liquidglass.hook

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.coloros16.liquidglass.engine.SeedlingActivity
import com.coloros16.liquidglass.engine.SeedlingStatusPolicy
import com.coloros16.liquidglass.log.Lg

/**
 * 常驻状态内容源：充电岛 / 耳机岛（事件驱动广播，零轮询）。
 * ACTION_BATTERY_CHANGED  sticky 广播注册即得当前态；电源插拔/电量变化 → 充电活动；
 * 有线耳机插拔 + 蓝牙音频设备连接 → 耳机活动；断开即撤对应活动。
 * 灭屏暂停由 SeedlingSceneGate 场景门控统一管，本源只负责内容有无。
 */
object SeedlingStatusSource {

    private const val TAG = "LiquidGlass"

    @Volatile
    var onActivity: ((SeedlingActivity?, String) -> Unit)? = null

    @Volatile
    var onRemove: ((String) -> Unit)? = null

    @Volatile
    private var installed = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var receiver: android.content.BroadcastReceiver? = null
    private var appCtx: Context? = null

    @Volatile
    private var chargingShown = false

    @Volatile
    private var headsetShown = false

    @Volatile
    private var lastChargingLabel = ""

    @Volatile
    private var lastHeadsetLabel = ""

    fun install(ctx: Context) {
        if (installed) return
        try {
            appCtx = ctx.applicationContext
            val r = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val action = intent?.action ?: return
                    mainHandler.post {
                        try {
                            when (action) {
                                Intent.ACTION_BATTERY_CHANGED -> onBattery(intent)
                                Intent.ACTION_HEADSET_PLUG -> onHeadsetPlug(intent)
                                android.bluetooth.BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED,
                                android.bluetooth.BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED,
                                -> onBluetoothAudio(intent)
                            }
                        } catch (t: Throwable) {
                            Lg.e(TAG, "seedling-status: event failed", t)
                        }
                    }
                }
            }
            receiver = r
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_HEADSET_PLUG)
                addAction(android.bluetooth.BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
                addAction(android.bluetooth.BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            }
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
            } else {
                ctx.registerReceiver(r, filter)
            }
            installed = true
            Lg.i(TAG, "seedling-status: installed (battery/headset/bluetooth events)")
        } catch (t: Throwable) {
            Lg.e(TAG, "seedling-status: install failed", t)
        }
    }

    fun shutdown() {
        try {
            receiver?.let { appCtx?.unregisterReceiver(it) }
        } catch (_: Throwable) {
        }
        receiver = null
        appCtx = null
        installed = false
        chargingShown = false
        headsetShown = false
        lastChargingLabel = ""
        lastHeadsetLabel = ""
    }

    private fun onBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val pct = if (scale > 0 && level >= 0) level * 100 / scale else 0
        val charging = plugged != 0 &&
            (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
        if (!SeedlingStatusPolicy.chargingActive(charging)) {
            if (chargingShown) {
                chargingShown = false
                lastChargingLabel = ""
                Lg.i(TAG, "seedling-status: charging ended")
                onRemove?.invoke(SeedlingStatusPolicy.CHARGING_ID)
            }
            return
        }
        val full = status == BatteryManager.BATTERY_STATUS_FULL
        val label = SeedlingStatusPolicy.chargingLabel(pct, full)
        if (chargingShown && label == lastChargingLabel) return
        chargingShown = true
        lastChargingLabel = label
        val now = SystemClock.elapsedRealtime()
        Lg.i(TAG, "seedling-status: charging pct=$pct plugged=$plugged full=$full")
        onActivity?.invoke(SeedlingStatusPolicy.chargingActivity(pct, plugged, now), "status")
    }

    private fun onHeadsetPlug(intent: Intent) {
        val state = intent.getIntExtra("state", 0)
        val wired = state == 1
        if (!wired) {
            if (headsetShown && lastHeadsetLabel == SeedlingStatusPolicy.headsetLabel(true)) {
                headsetShown = false
                lastHeadsetLabel = ""
                Lg.i(TAG, "seedling-status: wired headset unplugged")
                onRemove?.invoke(SeedlingStatusPolicy.HEADSET_ID)
            }
            return
        }
        emitHeadset(wired = true)
    }

    private fun onBluetoothAudio(intent: Intent) {
        val state = intent.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_CONNECTION_STATE, -1)
        val connected = state == android.bluetooth.BluetoothAdapter.STATE_CONNECTED
        val disconnected = state == android.bluetooth.BluetoothAdapter.STATE_DISCONNECTED
        if (connected) {
            emitHeadset(wired = false)
        } else if (disconnected && headsetShown && lastHeadsetLabel == SeedlingStatusPolicy.headsetLabel(false)) {
            headsetShown = false
            lastHeadsetLabel = ""
            Lg.i(TAG, "seedling-status: bluetooth audio disconnected")
            onRemove?.invoke(SeedlingStatusPolicy.HEADSET_ID)
        }
    }

    private fun emitHeadset(wired: Boolean) {
        val label = SeedlingStatusPolicy.headsetLabel(wired)
        if (headsetShown && label == lastHeadsetLabel) return
        headsetShown = true
        lastHeadsetLabel = label
        val now = SystemClock.elapsedRealtime()
        Lg.i(TAG, "seedling-status: headset connected wired=$wired")
        onActivity?.invoke(SeedlingStatusPolicy.headsetActivity(wired, now), "status")
    }
}
