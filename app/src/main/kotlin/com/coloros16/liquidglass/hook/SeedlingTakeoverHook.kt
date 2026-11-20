package com.coloros16.liquidglass.hook

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.engine.SeedlingAnimType
import com.coloros16.liquidglass.engine.SeedlingTransitionEvent
import com.coloros16.liquidglass.log.Lg
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * 流体云接管入口：注册系统 ChangeListener 真值通道 + 接管态自绘渲染。
 *
 * 动画真值通道：hook 宿主 `SeedlingPluginManager.onPluginConnected(SeedlingPlugin, Context)`
 * （:1210，插件连接唯一入口）拿到 SeedlingPlugin 实例 → 反射 `addChangeListener` 注册我们的
 * 动态代理监听器（同 LauncherTiltHook 代理路径，hashCode/equals 返回正确装箱防 NPE）。
 * 四事件 Bundle（animationId/isLockscreen/animationType/animationDurations）+ placeholder_width
 * 喂进 engine/SeedlingTakeoverMotion 求值（纯 JVM，App 预览与 hook 共用）。
 *
 * 接管门控：`seedling_takeover_enable` TTL 500ms 重读；关 → 零干预（现系统路径 + 现玻璃键）。
 * 锁屏事件（isLockscreen=true）忽略（锁屏流体云不同包，不在本轮）。禁新增轮询：事件驱动。
 */
object SeedlingTakeoverHook {

    private const val TAG = "LiquidGlass"
    private const val THROTTLE_MS = 2000L
    private const val PREFS_TTL_MS = 500L

    private const val CLASS_SEEDLING_PLUGIN_MANAGER =
        "com.oplus.systemui.statusbar.seeding.SeedlingPluginManager"
    private const val CLASS_SEEDLING_PLUGIN =
        "com.android.systemui.plugins.statusbar.SeedlingPlugin"
    private const val CLASS_CHANGE_LISTENER =
        "com.android.systemui.plugins.statusbar.SeedlingPlugin\$ChangeListener"
    private const val CLASS_CAPSULE_PLUGIN_CONTAINER =
        "com.oplus.systemui.statusbar.seeding.CapsulePluginContainer"
    private const val CLASS_CARD_CONTAINER =
        "com.oplus.systemui.plugins.seedling.card.ui.view.CardContainer"
    private const val METHOD_ON_PLUGIN_CONNECTED = "onPluginConnected"
    private const val METHOD_ADD_CHANGE_LISTENER = "addChangeListener"
    private const val METHOD_REMOVE_CHANGE_LISTENER = "removeChangeListener"

    /** 测试命令广播 action（设置页按钮与 adb 共用）。 */
    const val ACTION_TEST_ISLAND = "com.coloros16.liquidglass.action.TEST_ISLAND"

    private fun demoActivity(
        id: String,
        kind: com.coloros16.liquidglass.engine.SeedlingKind,
        label: String,
        symbol: String,
    ): com.coloros16.liquidglass.engine.SeedlingActivity =
        com.coloros16.liquidglass.engine.SeedlingActivity(
            id = "demo:$id",
            source = "demo",
            kind = kind,
            updatedAtElapsedMs = SystemClock.elapsedRealtime(),
            compact = com.coloros16.liquidglass.engine.SeedlingCompact(
                leading = com.coloros16.liquidglass.engine.SeedlingElement.Symbol(symbol),
                label = label,
                contentId = "demo:$id",
            ),
            expanded = com.coloros16.liquidglass.engine.SeedlingExpanded(
                title = label,
                subtitle = "测试内容",
                actions = listOf(
                    com.coloros16.liquidglass.engine.SeedlingAction("prev", "上一首", "SKIP_PREV"),
                    com.coloros16.liquidglass.engine.SeedlingAction("play_pause", "播放", "PLAY"),
                    com.coloros16.liquidglass.engine.SeedlingAction("next", "下一首", "SKIP_NEXT"),
                ),
            ),
        )

    /** 计时演示：150s 真倒计时（countdownEndAtElapsedMs 真值时钟，绘制期逐秒走时）。 */
    private fun demoTimer(): com.coloros16.liquidglass.engine.SeedlingActivity {
        val now = SystemClock.elapsedRealtime()
        return com.coloros16.liquidglass.engine.SeedlingActivity(
            id = "demo:timer",
            source = "demo",
            kind = com.coloros16.liquidglass.engine.SeedlingKind.TIMER,
            startedAtElapsedMs = now,
            updatedAtElapsedMs = now,
            compact = com.coloros16.liquidglass.engine.SeedlingCompact(
                leading = com.coloros16.liquidglass.engine.SeedlingElement.Symbol("TIMER"),
                label = "计时 02:30",
                contentId = "demo:timer",
            ),
            expanded = com.coloros16.liquidglass.engine.SeedlingExpanded(
                template = com.coloros16.liquidglass.engine.SeedlingTemplate.TIMER,
                title = "计时",
                subtitle = "测试倒计时",
                countdownEndAtElapsedMs = now + 150_000L,
                actions = listOf(
                    com.coloros16.liquidglass.engine.SeedlingAction("pause", "暂停", "PAUSE"),
                    com.coloros16.liquidglass.engine.SeedlingAction("stop", "停止", "CLOSE"),
                ),
            ),
        )
    }

    /** 外卖演示：S3 分段进度（接单/取餐/配送/送达）+ tracker + 里程碑。 */
    private fun demoDelivery(): com.coloros16.liquidglass.engine.SeedlingActivity =
        com.coloros16.liquidglass.engine.SeedlingActivity(
            id = "demo:delivery",
            source = "demo",
            kind = com.coloros16.liquidglass.engine.SeedlingKind.LIVE_UPDATE,
            updatedAtElapsedMs = SystemClock.elapsedRealtime(),
            compact = com.coloros16.liquidglass.engine.SeedlingCompact(
                leading = com.coloros16.liquidglass.engine.SeedlingElement.Symbol("NAVIGATION"),
                label = "外卖配送中 · 12分钟",
                contentId = "demo:delivery",
            ),
            expanded = com.coloros16.liquidglass.engine.SeedlingExpanded(
                template = com.coloros16.liquidglass.engine.SeedlingTemplate.PROGRESS,
                title = "外卖配送中",
                subtitle = "骑手距你 1.2km",
                progress = com.coloros16.liquidglass.engine.SeedlingProgress(
                    fraction = 0.62f,
                    segments = listOf(
                        com.coloros16.liquidglass.engine.SeedlingProgress.Segment(0.2f, 0xFF4CAF50.toInt()),
                        com.coloros16.liquidglass.engine.SeedlingProgress.Segment(0.3f, 0xFF2196F3.toInt()),
                        com.coloros16.liquidglass.engine.SeedlingProgress.Segment(0.4f, 0xFFFF9800.toInt()),
                        com.coloros16.liquidglass.engine.SeedlingProgress.Segment(0.1f, 0xFFE91E63.toInt()),
                    ),
                    points = listOf(0.2f, 0.5f, 0.9f),
                    trackerSymbol = "NAVIGATION",
                    startLabel = "已接单",
                    endLabel = "送达",
                ),
            ),
        )

    private val throttleAt = ConcurrentHashMap<String, Long>()

    @Volatile
    private var mounted = false

    @Volatile
    private var suppressionMounted = false

    @Volatile
    private var testReceiverMounted = false

    @Volatile
    private var mediaSourceMounted = false

    @Volatile
    private var collapseRulesMounted = false

    /** 测试通道禁收起规矩（灭屏/close-dialogs 广播），E2 期间防系统广播收大卡。 */
    @Volatile
    private var collapseRulesDisabled = false

    @Volatile
    private var apiRef: XposedInterface? = null

    @Volatile
    private var hostClassLoader: ClassLoader? = null

    /** 当前注册的监听器代理 + 插件实例（detach/热重载摘除用）。 */
    @Volatile
    private var registeredListener: Any? = null

    @Volatile
    private var registeredPlugin: Any? = null

    /** 最近一次过渡事件（自绘渲染消费）。 */
    @Volatile
    var lastEvent: SeedlingTransitionEvent? = null
        private set

    @Volatile
    var lastEventAtMs: Long = 0L
        private set

    /** 最近 placeholder_width 事件真值（岛目标宽）。 */
    @Volatile
    var lastPlaceholderWidthPx: Int = 0
        private set

    @Volatile
    private var cachedTakeoverEnabled = false

    @Volatile
    private var cachedTakeoverAtMs = 0L

    @Volatile
    private var cachedTakeoverGlass = false

    @Volatile
    private var cachedTakeoverGlassAtMs = 0L

    private fun logThrottled(key: String, msg: String, intervalMs: Long = THROTTLE_MS) {
        val now = SystemClock.uptimeMillis()
        val last = throttleAt[key]
        if (last == null || now - last >= intervalMs) {
            throttleAt[key] = now
            if (throttleAt.size > 256) throttleAt.clear()
            Lg.i(TAG, "[$key] $msg")
        }
    }

    /** 接管功能开关（TTL 500ms 重读，开关切换免重启生效）。 */
    fun takeoverEnabled(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - cachedTakeoverAtMs > PREFS_TTL_MS) {
            cachedTakeoverAtMs = now
            if (!testReceiverMounted) mountTestCommandReceiver()
            if (!mediaSourceMounted) mountMediaSource()
            val before = cachedTakeoverEnabled
            cachedTakeoverEnabled = try {
                apiRef?.let {
                    Prefs.read(it).getBoolean(
                        Prefs.KEY_SEEDLING_TAKEOVER_ENABLE,
                        Prefs.DEFAULT_SEEDLING_TAKEOVER_ENABLE,
                    )
                } ?: false
            } catch (t: Throwable) {
                Lg.w(TAG, "seedling-takeover: prefs read enable failed", t)
                false
            }
            if (cachedTakeoverEnabled != before) {
                Lg.i(TAG, "seedling-takeover: switch ${Prefs.KEY_SEEDLING_TAKEOVER_ENABLE}=$cachedTakeoverEnabled")
                // 接管关 → 撤独立窗口，原岛恢复（零残留，防双岛）
                if (before && !cachedTakeoverEnabled) {
                    SeedlingIslandWindow.remove()
                }
            }
        }
        return cachedTakeoverEnabled
    }

    /** 接管态玻璃开关（独立于功能开关，TTL 500ms）。 */
    fun takeoverGlassEnabled(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - cachedTakeoverGlassAtMs > PREFS_TTL_MS) {
            cachedTakeoverGlassAtMs = now
            cachedTakeoverGlass = try {
                apiRef?.let {
                    Prefs.read(it).getBoolean(
                        Prefs.KEY_SEEDLING_TAKEOVER_GLASS,
                        Prefs.DEFAULT_SEEDLING_TAKEOVER_GLASS,
                    )
                } ?: false
            } catch (t: Throwable) {
                false
            }
        }
        return cachedTakeoverGlass
    }

    @Volatile
    private var cachedBounceDp = Prefs.DEFAULT_SEEDLING_TAKEOVER_BOUNCE_DP

    @Volatile
    private var cachedSpeed = Prefs.DEFAULT_SEEDLING_TAKEOVER_SPEED

    @Volatile
    private var cachedAmbient = Prefs.DEFAULT_SEEDLING_TAKEOVER_AMBIENT_STROKE

    @Volatile
    private var cachedLyrics = Prefs.DEFAULT_SEEDLING_TAKEOVER_LYRICS

    @Volatile
    private var cachedIdleHide = Prefs.DEFAULT_SEEDLING_TAKEOVER_IDLE_HIDE

    @Volatile
    private var cachedCardMode = Prefs.DEFAULT_SEEDLING_CARD_MODE

    @Volatile
    private var cachedAnchorFollow = Prefs.DEFAULT_SEEDLING_ANCHOR_FOLLOW

    @Volatile
    private var cachedKindSwitches = com.coloros16.liquidglass.engine.SeedlingKindSwitches()

    @Volatile
    private var cachedAppearanceAtMs = 0L

    /** 外观参数组（回弹/速度/描边/歌词），TTL 500ms 惰性重读。 */
    private fun refreshAppearance() {
        val now = SystemClock.uptimeMillis()
        if (now - cachedAppearanceAtMs <= PREFS_TTL_MS) return
        cachedAppearanceAtMs = now
        try {
            apiRef?.let { api ->
                val p = Prefs.read(api)
                cachedBounceDp = p.getFloat(Prefs.KEY_SEEDLING_TAKEOVER_BOUNCE_DP, Prefs.DEFAULT_SEEDLING_TAKEOVER_BOUNCE_DP)
                cachedSpeed = p.getFloat(Prefs.KEY_SEEDLING_TAKEOVER_SPEED, Prefs.DEFAULT_SEEDLING_TAKEOVER_SPEED)
                cachedAmbient = p.getBoolean(Prefs.KEY_SEEDLING_TAKEOVER_AMBIENT_STROKE, Prefs.DEFAULT_SEEDLING_TAKEOVER_AMBIENT_STROKE)
                cachedLyrics = p.getBoolean(Prefs.KEY_SEEDLING_TAKEOVER_LYRICS, Prefs.DEFAULT_SEEDLING_TAKEOVER_LYRICS)
                cachedIdleHide = p.getBoolean(Prefs.KEY_SEEDLING_TAKEOVER_IDLE_HIDE, Prefs.DEFAULT_SEEDLING_TAKEOVER_IDLE_HIDE)
                cachedCardMode = p.getString(Prefs.KEY_SEEDLING_CARD_MODE, Prefs.DEFAULT_SEEDLING_CARD_MODE) ?: Prefs.DEFAULT_SEEDLING_CARD_MODE
                cachedAnchorFollow = p.getBoolean(Prefs.KEY_SEEDLING_ANCHOR_FOLLOW, Prefs.DEFAULT_SEEDLING_ANCHOR_FOLLOW)
                cachedKindSwitches = com.coloros16.liquidglass.engine.SeedlingKindSwitches(
                    media = p.getBoolean(Prefs.KEY_SEEDLING_KIND_MEDIA, Prefs.DEFAULT_SEEDLING_KIND_MEDIA),
                    message = p.getBoolean(Prefs.KEY_SEEDLING_KIND_MESSAGE, Prefs.DEFAULT_SEEDLING_KIND_MESSAGE),
                    call = p.getBoolean(Prefs.KEY_SEEDLING_KIND_CALL, Prefs.DEFAULT_SEEDLING_KIND_CALL),
                    timer = p.getBoolean(Prefs.KEY_SEEDLING_KIND_TIMER, Prefs.DEFAULT_SEEDLING_KIND_TIMER),
                    status = p.getBoolean(Prefs.KEY_SEEDLING_KIND_STATUS, Prefs.DEFAULT_SEEDLING_KIND_STATUS),
                    faceUnlock = p.getBoolean(Prefs.KEY_SEEDLING_KIND_FACE, Prefs.DEFAULT_SEEDLING_KIND_FACE),
                    liveUpdate = p.getBoolean(Prefs.KEY_SEEDLING_KIND_LIVE, Prefs.DEFAULT_SEEDLING_KIND_LIVE),
                    code = p.getBoolean(Prefs.KEY_SEEDLING_KIND_CODE, Prefs.DEFAULT_SEEDLING_KIND_CODE),
                )
            }
        } catch (t: Throwable) {
            Lg.w(TAG, "seedling-takeover: appearance prefs read failed", t)
        }
    }

    fun takeoverBounceDp(): Float {
        refreshAppearance()
        return cachedBounceDp
    }

    fun takeoverSpeed(): Float {
        refreshAppearance()
        return cachedSpeed
    }

    fun takeoverAmbientStroke(): Boolean {
        refreshAppearance()
        return cachedAmbient
    }

    fun takeoverLyricsEnabled(): Boolean {
        refreshAppearance()
        return cachedLyrics
    }

    /** 大卡样式模式（apple=单卡横滑换卡 / op=全卡竖排），TTL 500ms 惰性重读。 */
    fun takeoverCardMode(): com.coloros16.liquidglass.engine.SeedlingCardModePolicy.Mode {
        refreshAppearance()
        return if (cachedCardMode == "op") com.coloros16.liquidglass.engine.SeedlingCardModePolicy.Mode.OP
        else com.coloros16.liquidglass.engine.SeedlingCardModePolicy.Mode.APPLE
    }

    /** 岛位置是否跟随挖孔（false=屏幕居中），TTL 惰性重读。 */
    fun takeoverAnchorFollow(): Boolean {
        refreshAppearance()
        return cachedAnchorFollow
    }

    /** 测试通道切大卡模式：写框架侧 remote prefs（与 refreshAppearance 同源）+ 立即生效缓存 + 重绘岛。 */
    private fun setCardMode(ctx: Context?, mode: String) {
        cachedCardMode = mode
        cachedAppearanceAtMs = SystemClock.uptimeMillis()
        apiRef?.let { Prefs.read(it).edit().putString(Prefs.KEY_SEEDLING_CARD_MODE, mode).apply() }
        SeedlingIslandWindow.relayoutForModeChange()
        Lg.i(TAG, "seedling-takeover: card mode set=$mode")
    }

    /** 无操作隐藏开关（TTL 500ms 惰性重读）。 */
    fun takeoverIdleHide(): Boolean {
        refreshAppearance()
        return cachedIdleHide
    }

    /** kind 级开关表（TTL 500ms 惰性重读；内容源喂活动时过滤）。 */
    fun kindSwitches(): com.coloros16.liquidglass.engine.SeedlingKindSwitches {
        refreshAppearance()
        return cachedKindSwitches
    }

    /** 一行接入：BlurDrawHook.install 调用。 */
    fun install(api: XposedInterface, classLoader: ClassLoader) {
        if (mounted) return
        apiRef = api
        hostClassLoader = classLoader
        try {
            val mgrCls = hostForName(CLASS_SEEDLING_PLUGIN_MANAGER, classLoader)
            val pluginCls = hostForName(CLASS_SEEDLING_PLUGIN, classLoader)
            val method = mgrCls.getMethod(
                METHOD_ON_PLUGIN_CONNECTED,
                pluginCls,
                Context::class.java,
            )
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val plugin = chain.getArg(0)
                        if (plugin != null) registerChangeListener(plugin)
                    } catch (t: Throwable) {
                        Lg.e(TAG, "seedling-takeover: register listener failed", t)
                    }
                    result
                }
            mounted = true
            Lg.i(TAG, "seedling-takeover: mounted $CLASS_SEEDLING_PLUGIN_MANAGER#$METHOD_ON_PLUGIN_CONNECTED")
        } catch (t: Throwable) {
            Lg.e(TAG, "seedling-takeover: mount FAILED", t)
        }
        mountOriginalIslandSuppression(api, classLoader)
        mountTestCommandReceiver()
        mountMediaSource()
    }

    /** 媒体内容源接入：MediaSession 直读 → 活动喂独立窗口（事件驱动）。 */
    private fun mountMediaSource() {
        if (mediaSourceMounted) return
        val ctx = (BlurDrawHook.resolveSystemUiContext() ?: Lg.processContext())?.applicationContext ?: return
        SeedlingMediaSource.onActivity = { a, src ->
            SeedlingIslandWindow.onActivity(a, ctx, src)
        }
        SeedlingMediaSource.install(ctx)
        SeedlingRecordingSource.onActivity = { a, src ->
            SeedlingIslandWindow.onActivity(a, ctx, src)
        }
        SeedlingRecordingSource.install(ctx)
        SeedlingStatusSource.onActivity = { a, src ->
            SeedlingIslandWindow.onActivity(a, ctx, src)
        }
        SeedlingStatusSource.onRemove = { id ->
            SeedlingIslandWindow.removeActivityById(id)
        }
        SeedlingStatusSource.install(ctx)
        SeedlingLyricsBridge.onLyricsUpdated = {
            SeedlingIslandWindow.refreshLyrics()
        }
        SeedlingLyricsBridge.install(ctx)
        SeedlingForegroundSource.onForegroundChanged = { pkg ->
            SeedlingIslandWindow.onForegroundChanged(pkg)
        }
        SeedlingForegroundSource.install(ctx)
        SeedlingSceneGate.onVisibilityChanged = { visible ->
            SeedlingIslandWindow.onSceneVisibilityChanged(visible)
        }
        SeedlingSceneGate.install(ctx)
        apiRef?.let { api ->
            hostClassLoader?.let { cl ->
                SeedlingDataHook.onActivity = { a, src ->
                    SeedlingIslandWindow.onActivity(a, ctx, src)
                }
                SeedlingDataHook.install(api, cl)
                SeedlingNotifSource.onActivity = { a, src ->
                    SeedlingIslandWindow.onActivity(a, ctx, src)
                }
                SeedlingNotifSource.onRemove = { id ->
                    SeedlingIslandWindow.removeActivityById(id)
                }
                SeedlingNotifSource.install(api, cl)
            }
        }
        mediaSourceMounted = true
    }

    /** 测试命令通道：设置页「测试岛」按钮 / adb 广播手动唤醒自绘岛（不依赖系统事件）。 */
    private fun mountTestCommandReceiver() {
        if (testReceiverMounted) return
        val ctx = (BlurDrawHook.resolveSystemUiContext() ?: Lg.processContext())?.applicationContext
        if (ctx == null) {
            Lg.w(TAG, "seedling-takeover: test receiver deferred (no context yet)")
            return
        }
        try {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: android.content.Intent?) {
                    try {
                        val cmd = intent?.getStringExtra("cmd") ?: return
                        Lg.i(TAG, "seedling-takeover: test cmd=$cmd")
                        when (cmd) {
                            "show" -> SeedlingIslandWindow.ensureShown(ctx)
                            "expand" -> SeedlingIslandWindow.onTransitionEvent(
                                SeedlingTransitionEvent(SeedlingAnimType.EXPAND_OUT, longArrayOf(300L, 500L), 430, false),
                            )
                            "collapse" -> SeedlingIslandWindow.onTransitionEvent(
                                SeedlingTransitionEvent(SeedlingAnimType.SHRINK_IN, longArrayOf(300L, 500L), 430, false),
                            )
                            "hide" -> SeedlingIslandWindow.remove()
                            "s6-off" -> SeedlingIslandWindow.s6Disabled = true
                            "s6-on" -> SeedlingIslandWindow.s6Disabled = false
                            "collapse-rules-off" -> collapseRulesDisabled = true
                            "collapse-rules-on" -> collapseRulesDisabled = false
                            "mode-op" -> setCardMode(ctx, "op")
                            "mode-apple" -> setCardMode(ctx, "apple")
                            "demo-media" -> SeedlingIslandWindow.onActivity(demoActivity("media", com.coloros16.liquidglass.engine.SeedlingKind.MEDIA, "测试歌曲 - 歌手", "PLAY"), ctx)
                            "demo-timer" -> SeedlingIslandWindow.onActivity(demoTimer(), ctx)
                            "demo-msg" -> SeedlingIslandWindow.onActivity(demoActivity("msg", com.coloros16.liquidglass.engine.SeedlingKind.MESSAGE, "新消息：在吗", "MESSAGE"), ctx)
                            "demo-delivery" -> SeedlingIslandWindow.onActivity(demoDelivery(), ctx)
                            "demo-recording" -> SeedlingIslandWindow.onActivity(demoActivity("recording", com.coloros16.liquidglass.engine.SeedlingKind.RECORDING, "录音中 00:12", "MIC"), ctx)
                            "demo-charging" -> SeedlingIslandWindow.onActivity(com.coloros16.liquidglass.engine.SeedlingStatusPolicy.chargingActivity(68, 2, SystemClock.elapsedRealtime()), ctx, "status")
                            "demo-headset" -> SeedlingIslandWindow.onActivity(com.coloros16.liquidglass.engine.SeedlingStatusPolicy.headsetActivity(true, SystemClock.elapsedRealtime()), ctx, "status")
                            "recall" -> SeedlingIslandWindow.recallHidden()
                            "demo-clear" -> SeedlingIslandWindow.onActivity(null, ctx)
                            "demo-real-media" -> startTestMediaSession(ctx)
                            "demo-real-media-off" -> stopTestMediaSession()
                        }
                    } catch (t: Throwable) {
                        Lg.e(TAG, "seedling-takeover: test cmd failed", t)
                    }
                }
            }
            val filter = android.content.IntentFilter(ACTION_TEST_ISLAND)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                ctx.registerReceiver(receiver, filter)
            }
            testReceiverMounted = true
            Lg.i(TAG, "seedling-takeover: test receiver mounted action=$ACTION_TEST_ISLAND")
            mountCollapseRules(ctx)
        } catch (t: Throwable) {
            Lg.e(TAG, "seedling-takeover: test receiver mount failed", t)
        }
    }

    /** 测试用真实 MediaSession：让 MediaSessionManager 直读链走到真会话（非 demo 注入）。 */
    private var testMediaSession: android.media.session.MediaSession? = null

    private fun startTestMediaSession(ctx: android.content.Context) {
        try {
            stopTestMediaSession()
            val s = android.media.session.MediaSession(ctx, "LgRealMediaTest")
            s.setMetadata(
                android.media.MediaMetadata.Builder()
                    .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, "真实会话测试曲")
                    .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, "自动化")
                    .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, 180_000L)
                    .build()
            )
            s.setPlaybackState(
                android.media.session.PlaybackState.Builder()
                    .setActions(
                        android.media.session.PlaybackState.ACTION_PLAY or
                            android.media.session.PlaybackState.ACTION_PAUSE or
                            android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT or
                            android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS
                    )
                    .setState(android.media.session.PlaybackState.STATE_PLAYING, 1_000L, 1f)
                    .build()
            )
            s.isActive = true
            testMediaSession = s
            Lg.i(TAG, "seedling-media-test: session active title=真实会话测试曲")
        } catch (t: Throwable) {
            Lg.e(TAG, "seedling-media-test: start failed", t)
        }
    }

    private fun stopTestMediaSession() {
        try {
            testMediaSession?.isActive = false
            testMediaSession?.release()
        } catch (_: Throwable) {
        }
        testMediaSession = null
    }

    /** 收起规矩：灭屏/锁屏/下拉通知栏 → 大卡收胶囊（事件驱动，不轮询）。 */
    private fun mountCollapseRules(ctx: Context) {
        if (collapseRulesMounted) return
        try {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: android.content.Intent?) {
                    val action = intent?.action ?: return
                    if (collapseRulesDisabled) {
                        Lg.i(TAG, "seedling-takeover: collapse rule suppressed (test) action=$action")
                        return
                    }
                    Lg.i(TAG, "seedling-takeover: collapse rule triggered action=$action")
                    SeedlingIslandWindow.collapseToCapsule()
                }
            }
            val filter = android.content.IntentFilter().apply {
                addAction(android.content.Intent.ACTION_SCREEN_OFF)
                addAction(android.content.Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
            }
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                ctx.registerReceiver(receiver, filter)
            }
            collapseRulesMounted = true
            Lg.i(TAG, "seedling-takeover: collapse rules mounted (screen-off/close-dialogs)")
        } catch (t: Throwable) {
            Lg.e(TAG, "seedling-takeover: collapse rules mount failed", t)
        }
    }

    /**
     * 原系统岛抑制：
     * hook 基类 ViewGroup.dispatchDraw，this=CapsulePluginContainer 且接管开 → 跳过原渲染
     * （不 proceed，原岛不画）；接管关 → 正常 proceed（系统原样）。状态机不动，关接管即恢复。
     * 大卡 CardContainer 同链抑制（展开态原卡也压掉，完全由我们独立窗口渲染）。
     */
    private fun mountOriginalIslandSuppression(api: XposedInterface, classLoader: ClassLoader) {
        if (suppressionMounted) return
        try {
            val vgCls = Class.forName("android.view.ViewGroup", false, classLoader)
            val method = vgCls.getDeclaredMethod("dispatchDraw", android.graphics.Canvas::class.java)
            api.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        if (takeoverEnabled()) {
                            val thisView = try {
                                chain.getThisObject() as? View
                            } catch (t: Throwable) {
                                null
                            }
                            if (thisView != null) {
                                val name = thisView.javaClass.name
                                if (name == CLASS_CAPSULE_PLUGIN_CONTAINER || name == CLASS_CARD_CONTAINER) {
                                    logThrottled(
                                        "seedling-suppress",
                                        "seedling-takeover: suppress original island ${thisView.javaClass.simpleName}",
                                    )
                                    // 接管开：跳过原渲染（不 proceed），原岛不画
                                    return@intercept null
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        Lg.e(TAG, "seedling-takeover: suppression error", t)
                    }
                    chain.proceed()
                }
            suppressionMounted = true
            Lg.i(TAG, "seedling-takeover: original island suppression mounted (ViewGroup#dispatchDraw)")
        } catch (t: Throwable) {
            Lg.e(TAG, "seedling-takeover: suppression mount FAILED", t)
        }
    }

    /** 拿到 SeedlingPlugin 实例 → 反射 addChangeListener 注册动态代理监听器。 */
    private fun registerChangeListener(plugin: Any) {
        // 已注册同一插件则跳过（幂等）
        if (registeredPlugin === plugin && registeredListener != null) return
        // 换插件先摘旧监听器
        unregisterChangeListener()
        try {
            val listenerCls = hostForName(CLASS_CHANGE_LISTENER, plugin.javaClass.classLoader)
            val handler = java.lang.reflect.InvocationHandler { proxy, method, args ->
                try {
                    when (method.name) {
                        "onPreChange" -> {
                            onBundleEvent(args?.getOrNull(0) as? Bundle, "pre"); null
                        }
                        "onChangeStart" -> {
                            onBundleEvent(args?.getOrNull(0) as? Bundle, "start"); null
                        }
                        "onChangeEnd" -> {
                            onBundleEvent(args?.getOrNull(0) as? Bundle, "end"); null
                        }
                        "onChangeCancel" -> {
                            onBundleEvent(args?.getOrNull(0) as? Bundle, "cancel"); null
                        }
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.getOrNull(0)
                        "toString" -> "lg-seedling-takeover-listener"
                        else -> null
                    }
                } catch (t: Throwable) {
                    Lg.e(TAG, "seedling-takeover: listener ${method.name} error", t)
                    when (method.name) {
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> false
                        "toString" -> "lg-seedling-takeover-listener"
                        else -> null
                    }
                }
            }
            val listener = Proxy.newProxyInstance(listenerCls.classLoader, arrayOf(listenerCls), handler)
            val addMethod = plugin.javaClass.getMethod(METHOD_ADD_CHANGE_LISTENER, listenerCls)
            addMethod.invoke(plugin, listener)
            registeredListener = listener
            registeredPlugin = plugin
            Lg.i(TAG, "seedling-takeover: ChangeListener registered on ${plugin.javaClass.name}")
        } catch (t: Throwable) {
            Lg.e(TAG, "seedling-takeover: addChangeListener failed", t)
        }
    }

    /** 摘除监听器（插件断开/热重载）。 */
    fun unregisterChangeListener() {
        val plugin = registeredPlugin
        val listener = registeredListener
        if (plugin != null && listener != null) {
            try {
                val listenerCls = hostForName(CLASS_CHANGE_LISTENER, plugin.javaClass.classLoader)
                val removeMethod = plugin.javaClass.getMethod(METHOD_REMOVE_CHANGE_LISTENER, listenerCls)
                removeMethod.invoke(plugin, listener)
                Lg.i(TAG, "seedling-takeover: ChangeListener removed")
            } catch (t: Throwable) {
                Lg.w(TAG, "seedling-takeover: removeChangeListener failed", t)
            }
        }
        registeredListener = null
        registeredPlugin = null
    }

    /** 解 Bundle 事件 → engine 求值器输入；锁屏事件忽略；节流诊断日志。 */
    private fun onBundleEvent(bundle: Bundle?, phase: String) {
        if (bundle == null) return
        val isLockscreen = bundle.getBoolean("isLockscreen", false)
        if (isLockscreen) {
            logThrottled("seedling-takeover-lockskip", "seedling-takeover: lockscreen event ignored phase=$phase")
            return
        }
        // placeholder_width 事件（onPreChange 携带，岛目标宽真值）
        val placeholderWidth = bundle.getInt("placeholder_width", 0)
        if (placeholderWidth > 0) {
            lastPlaceholderWidthPx = placeholderWidth
            logThrottled("seedling-takeover-placeholder", "seedling-takeover: placeholder_width=$placeholderWidth")
        }
        val typeName = bundle.getString("animationType")
        val durations = bundle.getLongArray("animationDurations") ?: longArrayOf()
        val animId = bundle.getLong("animationId", 0L)
        val type = SeedlingAnimType.ofName(typeName)
        val isPurePlaceholder = type == SeedlingAnimType.UNKNOWN && durations.isEmpty()
        if (!isPurePlaceholder) {
            val event = SeedlingTransitionEvent(type, durations, placeholderWidth, isLockscreen)
            lastEvent = event
            lastEventAtMs = SystemClock.uptimeMillis()
            logThrottled(
                "seedling-takeover-event",
                "seedling-takeover: $phase type=$typeName id=$animId durations=${durations.contentToString()} placeholder=$placeholderWidth",
            )
        }
        // 接管开 → 独立窗口自绘（事件驱动）：过渡事件喂动画；纯 placeholder（静止岛出现）也确保窗口在屏
        if (takeoverEnabled() && (placeholderWidth > 0 || !isPurePlaceholder)) {
            val ctx = BlurDrawHook.resolveSystemUiContext()
            if (ctx != null) {
                SeedlingIslandWindow.ensureShown(ctx)
                if (!isPurePlaceholder) {
                    lastEvent?.let { SeedlingIslandWindow.onTransitionEvent(it) }
                }
            }
        }
    }

    /** 热重载/卸载清理。 */
    fun shutdownForHotReload() {
        unregisterChangeListener()
        SeedlingMediaSource.shutdown()
        SeedlingRecordingSource.shutdown()
        SeedlingStatusSource.shutdown()
        SeedlingNotifSource.shutdown()
        SeedlingForegroundSource.shutdown()
        SeedlingSceneGate.shutdown()
        SeedlingLyricsBridge.shutdown()
        mediaSourceMounted = false
        SeedlingIslandWindow.remove()
        lastEvent = null
        lastPlaceholderWidthPx = 0
    }

    private fun hostForName(name: String, fallbackLoader: ClassLoader?): Class<*> {
        hostClassLoader?.let { cl ->
            try {
                return Class.forName(name, false, cl)
            } catch (_: ClassNotFoundException) {
            }
        }
        fallbackLoader?.let { cl ->
            try {
                return Class.forName(name, false, cl)
            } catch (_: ClassNotFoundException) {
            }
        }
        return Class.forName(name)
    }
}