package com.coloros16.liquidglass.hook

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.coloros16.liquidglass.engine.SeedlingDisplayRules
import com.coloros16.liquidglass.engine.SeedlingKindPolicy
import com.coloros16.liquidglass.engine.SeedlingKindSwitches
import com.coloros16.liquidglass.engine.SeedlingSceneState
import com.coloros16.liquidglass.log.Lg

/**
 * 接管岛场景门控：锁屏/息屏/横屏/下拉场景探针（事件驱动广播 + 既有状态读取，零轮询）
 * → SeedlingKindPolicy.shouldShow 求值 → 窗口显隐。显示规则此前零消费，本件接成真。
 */
object SeedlingSceneGate {

    private const val TAG = "LiquidGlass"

    @Volatile
    var onVisibilityChanged: ((Boolean) -> Unit)? = null

    @Volatile
    private var installed = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var receiver: android.content.BroadcastReceiver? = null

    @Volatile
    private var screenOn = true

    @Volatile
    private var lastVisible = true

    fun install(ctx: Context) {
        if (installed) return
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            screenOn = pm?.isInteractive ?: true
            val r = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: android.content.Intent?) {
                    when (intent?.action) {
                        android.content.Intent.ACTION_SCREEN_ON -> {
                            screenOn = true
                            reevaluate()
                        }
                        android.content.Intent.ACTION_SCREEN_OFF -> {
                            screenOn = false
                            reevaluate()
                        }
                        android.content.Intent.ACTION_USER_PRESENT -> reevaluate()
                        android.content.Intent.ACTION_CONFIGURATION_CHANGED -> reevaluate()
                    }
                }
            }
            receiver = r
            val filter = android.content.IntentFilter().apply {
                addAction(android.content.Intent.ACTION_SCREEN_ON)
                addAction(android.content.Intent.ACTION_SCREEN_OFF)
                addAction(android.content.Intent.ACTION_USER_PRESENT)
                addAction(android.content.Intent.ACTION_CONFIGURATION_CHANGED)
            }
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
            } else {
                ctx.registerReceiver(r, filter)
            }
            installed = true
            Lg.i(TAG, "seedling-scene: gate installed (screen/user-present/config events)")
        } catch (t: Throwable) {
            Lg.w(TAG, "seedling-scene: install skipped (${t.message})")
        }
    }

    /** 场景变化重算（下拉面板展开等外部事件也可调）。 */
    fun reevaluate() {
        mainHandler.post {
            val visible = computeVisible()
            if (visible != lastVisible) {
                lastVisible = visible
                Lg.i(TAG, "seedling-scene: visible=$visible screenOn=$screenOn")
                onVisibilityChanged?.invoke(visible)
            }
        }
    }

    private fun computeVisible(): Boolean {
        val ctx = BlurDrawHook.resolveSystemUiContext() ?: Lg.processContext()
        val landscape = ctx?.resources?.configuration?.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val keyguard = BlurDrawHook.isKeyguardLockedForIsland()
        val scene = SeedlingSceneState(
            onLockscreen = keyguard && screenOn,
            onAod = !screenOn,
            landscape = landscape,
            shadeExpanded = BlurDrawHook.isPanelExpanded(),
        )
        val kind = SeedlingIslandWindow.activity?.kind ?: com.coloros16.liquidglass.engine.SeedlingKind.UNKNOWN
        return SeedlingKindPolicy.shouldShow(
            kind,
            SeedlingTakeoverHook.kindSwitches(),
            SeedlingDisplayRules(),
            scene,
            masterEnabled = SeedlingTakeoverHook.takeoverEnabled(),
        )
    }

    fun shutdown() {
        try {
            receiver?.let {
                val ctx = BlurDrawHook.resolveSystemUiContext() ?: Lg.processContext()
                ctx?.unregisterReceiver(it)
            }
        } catch (_: Throwable) {
        }
        receiver = null
        installed = false
        lastVisible = true
    }
}
