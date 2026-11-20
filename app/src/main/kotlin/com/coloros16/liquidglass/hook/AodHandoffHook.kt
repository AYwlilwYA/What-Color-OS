package com.coloros16.liquidglass.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.coloros16.liquidglass.config.EffectConfigStore
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.engine.ChromakeyMask
import com.coloros16.liquidglass.engine.ContentLayerConfig
import com.coloros16.liquidglass.engine.HomeTransitionOverride
import com.coloros16.liquidglass.engine.MaskCenterPolicy
import com.coloros16.liquidglass.engine.MaskLayerConfig
import com.coloros16.liquidglass.engine.PumpTakeoverPolicy
import com.coloros16.liquidglass.engine.ReplayLedger
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.SceneStateMapper
import com.coloros16.liquidglass.engine.SeamProfilePolicy
import com.coloros16.liquidglass.engine.ScreenMetrics
import com.coloros16.liquidglass.engine.StyleCapabilities
import com.coloros16.liquidglass.engine.TransitionBridge
import com.coloros16.liquidglass.engine.TransitionEdge
import com.coloros16.liquidglass.engine.TransitionEvaluator
import com.coloros16.liquidglass.engine.TransitionProfile
import com.coloros16.liquidglass.engine.effect.HomeMotion
import com.coloros16.liquidglass.log.Lg
import com.coloros16.liquidglass.ui.aod.AodSceneCatalog
import com.coloros16.liquidglass.ui.aod.AodStyleCatalog
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.reflect.Method
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * alive 息屏 A 块衔接进度泵（空壳验证链）：
 * hook KeyguardTransitionRepositoryImpl 的 startTransition / updateTransition 两方法，
 * 打【不依赖过滤条件】的命中计数与最近一次转场进度摘要（logcat tag = [TAG]），
 * 供真机确认 hook 运行时真被调用；视觉动效在后续批次接入本文件 gate 分支。
 *
 * - 读键：[Prefs.KEY_MASTER] ∧ [Prefs.KEY_ALIVE_AOD_HANDOFF]，TTL 500ms 重读（热路径缓存）。
 * - 计数无条件：命中计数与日志观测不受 gate / stopped 门控（验证链要求），gate 值只进消息与后续行为位。
 * - 停机点：屏幕灭 → 置 stopped、停进度打印（改打 aod-stopped 守卫日志），屏幕亮恢复。
 * - 防重放骨架：[HandoffReplayGuard] 按代次丢弃过期回调（只记数不拦，语义待真机校准）。
 * - 全链 fail-open：类/方法解析失败、读键失败只降级本功能并打日志，不炸宿主。
 */
object AodHandoffHook {

    private const val TAG = "LgAod"
    private const val THROTTLE_MS = 2000L
    private const val BRIDGE_LOG_MS = 500L
    private const val PUMP_WRITE_LOG_MS = 0L
    private const val UNKNOWN_EDGE_LOG_MS = 5000L
    private const val GATE_TTL_MS = 500L

    private const val CLASS_REPO =
        "com.android.systemui.keyguard.data.repository.KeyguardTransitionRepositoryImpl"
    private const val CLASS_TRANSITION_INFO =
        "com.android.systemui.keyguard.shared.model.TransitionInfo"
    private const val CLASS_TRANSITION_STATE =
        "com.android.systemui.keyguard.shared.model.TransitionState"
    private const val METHOD_START = "startTransition"
    private const val METHOD_UPDATE = "updateTransition"
    private const val METHOD_EMIT = "emitTransition"
    private const val MODULE_PACKAGE = "com.coloros16.liquidglass"
    private const val STATE_STARTED = "STARTED"
    private const val SELFTIME_WATCH_MS = 120L
    private const val CLASS_TRANSITION_STEP =
        "com.android.systemui.keyguard.shared.model.TransitionStep"

    private val throttleAt = ConcurrentHashMap<String, Long>()

    /** 宿主当前能提供的能力：原生转场进度 + 遮罩层 + 内容交叠；L1 场景层待后续接入。 */
    private val HOST_CAPABILITIES = StyleCapabilities(
        sceneContinuous = false,
        mask = true,
        crossfade = true,
    )

    /** 走几何揭开、需要消费样式遮罩中心的两条边（与预览侧同一组）。 */
    private val MASK_CENTER_EDGES = arrayOf(
        TransitionEdge.AOD_TO_LOCK,
        TransitionEdge.LOCK_TO_AOD,
    )

    /** 照片类息屏：揭开由色键帧承担，几何遮罩让位。 */
    private val FRAME_CAPABILITIES = StyleCapabilities(
        sceneContinuous = false,
        mask = true,
        crossfade = true,
        frameReveal = true,
    )

    /** 节流诊断日志：同 key 2s 内一条；key 与计数都回显进打印文本（只进节流表按 key grep 恒 0）。 */
    private fun logThrottled(key: String, msg: String, intervalMs: Long = THROTTLE_MS) {
        val now = SystemClock.uptimeMillis()
        val last = throttleAt[key]
        if (last != null && now - last < intervalMs) return
        throttleAt[key] = now
        if (throttleAt.size > 256) throttleAt.clear()
        Lg.i(TAG, "$key: $msg")
    }

    private val startHits = AtomicLong()
    private val updateHits = AtomicLong()
    private val emitHits = AtomicLong()
    private val replayDrops = AtomicLong()
    private val selfTimeSerial = AtomicLong()

    @Volatile private var installed = false
    @Volatile private var gateEnabled = false
    @Volatile private var gateReadAt = 0L
    @Volatile private var revealByFrame = false
    @Volatile private var revealReadAt = 0L
    @Volatile private var maskCenterX = MaskCenterPolicy.SCREEN_CENTER
    @Volatile private var maskCenterY = MaskCenterPolicy.SCREEN_CENTER
    @Volatile private var homeOverrideId: String? = null
    @Volatile private var homeOverrideMs = 0L
    @Volatile private var stopped = false
    @Volatile private var lastFrom = "?"
    @Volatile private var lastTo = "?"

    /** 最近一次由确定来源（完整转场 / 锁屏边沿）记录的真实态；起点名不具判定力时兜底。 */
    @Volatile private var lastRealScene: SceneState? = null
    @Volatile private var lastUuid = "?"
    @Volatile private var lastProgress = Float.NaN
    @Volatile private var lastState = "?"
    @Volatile private var lastStartToken = -1L
    @Volatile private var lastStartInWindow = false

    private var api: XposedInterface? = null
    private var fromGetter: Method? = null
    private var toGetter: Method? = null
    private var stepFromGetter: Method? = null
    private var stepToGetter: Method? = null
    private var stepValueGetter: Method? = null
    private var stepStateGetter: Method? = null
    private var bridge: TransitionBridge? = null
    @Volatile private var currentEdge: TransitionEdge? = null

    /** 防重放代次账本（时间全部由调用方传入，纯逻辑可 JVM 单测）。 */
    val guard = HandoffReplayGuard()

    /** 装载：两个 hook 各自独立 try（一个失败另一个照装），全链 fail-open。 */
    fun install(api: XposedInterface, classLoader: ClassLoader) {
        if (installed) {
            logThrottled("aod-install-dup", "已装载，跳过 repeat=${startHits.get()}/${updateHits.get()}")
            return
        }
        this.api = api
        refreshGate()
        var hook = 0
        try {
            val loader = hostLoader(classLoader)
            val repo = Class.forName(CLASS_REPO, false, loader)
            val info = Class.forName(CLASS_TRANSITION_INFO, false, loader)
            val state = Class.forName(CLASS_TRANSITION_STATE, false, loader)
            val cont = Class.forName("kotlin.coroutines.Continuation", false, loader)
            fromGetter = info.getMethod("getFrom")
            toGetter = info.getMethod("getTo")
            try {
                val start = repo.getDeclaredMethod(METHOD_START, info, cont)
                api.hook(start)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        try {
                            recordStart(chain.getArg(0))
                        } catch (t: Throwable) {
                            logThrottled("aod-arg", "start 参数解析失败 ${t.javaClass.simpleName} start=${startHits.get()}")
                        }
                        report("aod-hit")
                        chain.proceed()
                    }
                hook++
            } catch (t: Throwable) {
                Lg.e(TAG, "aod-install $METHOD_START 失败 ${t.javaClass.simpleName}: ${t.message}", t)
            }
            hook += mountEmit(api, repo, loader)
            val update = resolveUpdateMethod(repo, state, cont)
            if (update == null) {
                Lg.e(TAG, "aod-install $METHOD_UPDATE 两种签名都取不到，进度泵不可用")
            } else {
                try {
                    api.hook(update)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            try {
                                recordUpdate(chain.getArg(0), chain.getArg(1), chain.getArg(2))
                            } catch (t: Throwable) {
                                logThrottled("aod-arg", "update 参数解析失败 ${t.javaClass.simpleName} upd=${updateHits.get()}")
                            }
                            report("aod-hit")
                            chain.proceed()
                        }
                    hook++
                    Lg.i(TAG, "aod-mount $METHOD_UPDATE params=${update.parameterTypes.size}")
                } catch (t: Throwable) {
                    Lg.e(TAG, "aod-install $METHOD_UPDATE 失败 ${t.javaClass.simpleName}: ${t.message}", t)
                }
            }
        } catch (t: Throwable) {
            Lg.e(TAG, "aod-install 类解析失败 ${t.javaClass.simpleName}: ${t.message}", t)
        }
        installed = hook > 0
        registerScreenReceiver()
        bridge = createBridge()
        val gate = refreshGate()
        Lg.i(
            TAG,
            "aod-install hook=$hook/3 installed=$installed gate=$gate " +
                "hits start=${startHits.get()} upd=${updateHits.get()} emit=${emitHits.get()} " +
                "bridge=${bridge != null}",
        )
    }

    /**
     * 宿主 loader 解析：入参能加载仓库类就用入参，否则退到进程 Application loader。
     *
     * 热重载线程的 contextClassLoader 可能是模块自己的，用它加载宿主类必然 CNFE。
     */
    private fun hostLoader(fallback: ClassLoader): ClassLoader {
        if (canLoadRepo(fallback)) return fallback
        val host = runCatching { Lg.processContext()?.classLoader }.getOrNull()
        if (host != null && canLoadRepo(host)) {
            Lg.i(TAG, "aod-loader 入参不可用，改用宿主 Application loader")
            return host
        }
        logThrottled("aod-loader-miss", "两种 loader 都加载不到转场仓库类")
        return fallback
    }

    private fun canLoadRepo(loader: ClassLoader): Boolean =
        runCatching { Class.forName(CLASS_REPO, false, loader) }.isSuccess

    /**
     * 热重载停机点：清装载标志与全部缓存/取值反射，让新类能重新装载。
     *
     * 不清 installed 会让新类的 install 走重复装载分支直接 return，整条进度泵停在旧类上。
     */
    fun shutdownForHotReload() {
        unregisterScreenReceiver()
        installed = false
        bridge = null
        currentEdge = null
        lastRealScene = null
        fromGetter = null
        toGetter = null
        stepFromGetter = null
        stepToGetter = null
        stepValueGetter = null
        stepStateGetter = null
        throttleAt.clear()
        gateReadAt = 0L
        revealReadAt = 0L
        maskCenterX = MaskCenterPolicy.SCREEN_CENTER
        maskCenterY = MaskCenterPolicy.SCREEN_CENTER
        homeOverrideId = null
        homeOverrideMs = 0L
        lastProgress = Float.NaN
        lastState = "?"
        lastStartToken = -1L
        selfTimeSerial.incrementAndGet()
        watchHandler?.removeCallbacksAndMessages(null)
        watchHandler = null
        Lg.i(
            TAG,
            "handoff-hotreload-shutdown start=${startHits.get()} upd=${updateHits.get()} " +
                "emit=${emitHits.get()}",
        )
    }

    /**
     * 挂状态变更汇聚点：所有转场状态都经它，含逐帧 RUNNING 进度。
     *
     * 有动画时进度由帧回调直接送这里，不经手动推进方法；
     * 只挂手动方法会让有动画的转场整条进度泵静默失效。
     */
    private fun mountEmit(
        api: XposedInterface,
        repo: Class<*>,
        classLoader: ClassLoader,
    ): Int {
        return try {
            val step = Class.forName(CLASS_TRANSITION_STEP, false, classLoader)
            stepFromGetter = step.getMethod("getFrom")
            stepToGetter = step.getMethod("getTo")
            stepValueGetter = step.getMethod("getValue")
            stepStateGetter = step.getMethod("getTransitionState")
            val emit = repo.getDeclaredMethod(
                METHOD_EMIT, step, Boolean::class.javaPrimitiveType,
            )
            api.hook(emit)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        recordEmit(chain.getArg(0))
                    } catch (t: Throwable) {
                        logThrottled(
                            "aod-arg",
                            "emit 参数解析失败 ${t.javaClass.simpleName} emit=${emitHits.get()}",
                        )
                    }
                    chain.proceed()
                }
            Lg.i(TAG, "aod-mount $METHOD_EMIT params=${emit.parameterTypes.size}")
            1
        } catch (t: Throwable) {
            Lg.e(TAG, "aod-install $METHOD_EMIT 失败 ${t.javaClass.simpleName}: ${t.message}", t)
            0
        }
    }

    /** 汇聚点回调：一次拿全起点/终点/进度/状态，按状态分流建边与推进度。 */
    private fun recordEmit(step: Any?) {
        emitHits.incrementAndGet()
        if (step == null) return
        val from = stepFromGetter?.let { safeEnumName(it, step) }
        val to = stepToGetter?.let { safeEnumName(it, step) }
        if (from != null) lastFrom = from
        if (to != null) lastTo = to
        lastProgress = normalizeProgress(stepValueGetter?.invoke(step) as? Float ?: Float.NaN)
        lastState = stepStateGetter?.invoke(step)?.toString() ?: "?"
        val edge = resolveEdge()
        if (currentEdge != edge) {
            currentEdge = edge
            if (edge != null) {
                logThrottled("aod-edge", "edge=$edge from=$lastFrom to=$lastTo src=emit${fallbackTag()}")
            } else {
                if (SceneStateMapper.isSameScene(lastFrom, lastTo)) {
                    logThrottled(
                        "aod-edge-samestate",
                        "同态子步骤（非未识别边） from=$lastFrom to=$lastTo src=emit",
                        UNKNOWN_EDGE_LOG_MS,
                    )
                } else {
                    logThrottled(
                        "aod-edge-unknown",
                        "边未识别 from=$lastFrom to=$lastTo src=emit",
                        UNKNOWN_EDGE_LOG_MS,
                    )
                }
            }
            notifyStageEdge(edge)
        }
        if (!guard.stillCurrent(lastStartToken)) {
            replayDrops.incrementAndGet()
        }
        feedBridge()
        if (lastState == STATE_STARTED) {
            armSelfTimedWatch()
        }
    }

    /**
     * 系统宣告开边后的一次性延后判定：到点仍没见过中间帧就交内部时间轴补播。
     *
     * 单次延迟、边变了就作废，不是固定间隔轮询。
     */
    private fun armSelfTimedWatch() {
        val edge = currentEdge ?: return
        val b = bridge ?: return
        if (!refreshGate()) return
        val token = selfTimeSerial.incrementAndGet()
        val handler = watchHandler() ?: run {
            logThrottled("aod-selftime-nolooper", "主线程Looper取不到，无帧边不补播 edge=$edge")
            return
        }
        handler.postDelayed(
            {
                if (token != selfTimeSerial.get()) return@postDelayed
                if (currentEdge != edge) return@postDelayed
                if (!refreshGate()) return@postDelayed
                if (!PumpTakeoverPolicy.shouldSelfTime(edge, true, b.clock.pumpSawMovement())) {
                    return@postDelayed
                }
                try {
                    com.coloros16.liquidglass.hook.aod.AodStageHook.playSystemEdge(edge)
                } catch (t: Throwable) {
                    logThrottled(
                        "aod-selftime",
                        "内部时间轴接管失败 ${t.javaClass.simpleName} edge=$edge",
                    )
                }
            },
            SELFTIME_WATCH_MS,
        )
    }

    private var screenReceiver: BroadcastReceiver? = null
    private var watchHandler: Handler? = null

    private fun watchHandler(): Handler? {
        watchHandler?.let { return it }
        val looper = runCatching { Looper.getMainLooper() }.getOrNull() ?: return null
        val created = Handler(looper)
        watchHandler = created
        return created
    }

    /**
     * 进度方法签名按档解析：suspend 版四参（带 Continuation）与非 suspend 版三参。
     *
     * 两档实机签名不同，只取一种会让另一档整条进度泵静默失效。
     * 前三参顺序一致，回调取 arg0/1/2 两档通用。
     */
    private fun resolveUpdateMethod(
        repo: Class<*>,
        state: Class<*>,
        cont: Class<*>,
    ): Method? {
        val candidates = updateSignatureCandidates(state, cont)
        val index = MethodSignaturePicker.pickIndex(METHOD_UPDATE, candidates) { name, params ->
            try {
                repo.getDeclaredMethod(name, *params)
            } catch (t: Throwable) {
                logThrottled(
                    "aod-update-sig-${params.size}",
                    "${params.size} 参签名不存在 ${t.javaClass.simpleName}",
                )
                null
            }
        }
        if (index >= 0) {
            Lg.i(TAG, "aod-update-sig 命中候选 $index（${candidates[index].size} 参）")
        }
        return if (index >= 0) repo.getDeclaredMethod(METHOD_UPDATE, *candidates[index]) else null
    }

    /** 候选签名：带协程续体的四参在前，不带的三参在后。 */
    internal fun updateSignatureCandidates(state: Class<*>, cont: Class<*>): List<Array<Class<*>>> {
        val uuid = UUID::class.java
        val progress = Float::class.javaPrimitiveType!!
        return listOf(
            arrayOf(uuid, progress, state, cont),
            arrayOf(uuid, progress, state),
        )
    }

    /** 建求值桥：默认配方 = L2 圆形揭开 + L3 内容交叠；屏幕尺寸取宿主资源，取不到用兜底值。 */
    private fun createBridge(): TransitionBridge {
        val dm = Lg.processContext()?.resources?.displayMetrics
        val w = dm?.widthPixels?.toFloat()?.takeIf { it > 0f } ?: 1080f
        val h = dm?.heightPixels?.toFloat()?.takeIf { it > 0f } ?: 2400f
        val evaluator = TransitionEvaluator(
            defaultProfile = TransitionProfile(
                mask = MaskLayerConfig(enabled = true),
                content = ContentLayerConfig(),
            ),
        )
        for (edge in TransitionEdge.values()) {
            val profile = TransitionProfile(
                mask = MaskLayerConfig(enabled = true),
                content = ContentLayerConfig(),
            )
            SeamProfilePolicy.apply(edge, profile)
            evaluator.setProfile(edge, profile)
        }
        val created = TransitionBridge(ScreenMetrics(w, h, dm?.density ?: 1f), evaluator = evaluator)
        created.setCapabilities(HOST_CAPABILITIES, HOST_CAPABILITIES)
        Lg.i(
            TAG,
            "aod-bridge ready ${w.toInt()}x${h.toInt()} caps host=mask+crossfade " +
                "seam=reveal(noOuterFade)+slide(${SeamProfilePolicy.SLIDE_FRACTION})",
        )
        return created
    }

    /** 读键（TTL 500ms 缓存）：master ∧ 息屏衔接键；读失败保持上次值（fail-open）。 */
    private fun refreshGate(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (gateReadAt != 0L && now - gateReadAt < GATE_TTL_MS) return gateEnabled
        gateReadAt = now
        val a = api ?: return gateEnabled
        gateEnabled = try {
            val prefs = Prefs.read(a)
            prefs.getBoolean(Prefs.KEY_MASTER, false) &&
                prefs.getBoolean(Prefs.KEY_ALIVE_AOD_HANDOFF, false)
        } catch (t: Throwable) {
            logThrottled("aod-gate-read", "读键失败 ${t.javaClass.simpleName} 保持 $gateEnabled")
            gateEnabled
        }
        return gateEnabled
    }

    private fun recordStart(info: Any?) {
        startHits.incrementAndGet()
        if (info != null) {
            fromGetter?.let { m -> safeEnumName(m, info)?.let { lastFrom = it } }
            toGetter?.let { m -> safeEnumName(m, info)?.let { lastTo = it } }
        }
        val previous = lastStartToken
        lastStartToken = guard.generation()
        lastStartInWindow = guard.startWithinWindow(SystemClock.uptimeMillis())
        if (ReplayLedger.superseded(previous, lastStartToken)) {
            replayDrops.incrementAndGet()
        }
        val edge = resolveEdge()
        if (currentEdge != edge) {
            currentEdge = edge
            if (edge != null) {
                logThrottled("aod-edge", "edge=$edge from=$lastFrom to=$lastTo${fallbackTag()}")
            } else {
                if (SceneStateMapper.isSameScene(lastFrom, lastTo)) {
                    logThrottled(
                        "aod-edge-samestate",
                        "同态子步骤（非未识别边） from=$lastFrom to=$lastTo",
                        UNKNOWN_EDGE_LOG_MS,
                    )
                } else {
                    logThrottled(
                        "aod-edge-unknown",
                        "边未识别 from=$lastFrom to=$lastTo（待真机校准命名）",
                        UNKNOWN_EDGE_LOG_MS,
                    )
                }
            }
            notifyStageEdge(edge)
        }
    }

    private fun recordUpdate(id: Any?, raw: Any?, st: Any?) {
        updateHits.incrementAndGet()
        lastUuid = (id as? UUID)?.toString()?.take(8) ?: "?"
        lastProgress = normalizeProgress(raw as? Float ?: Float.NaN)
        lastState = st?.toString() ?: "?"
        if (!guard.stillCurrent(lastStartToken)) {
            replayDrops.incrementAndGet()
        }
        feedBridge()
    }

    private fun resolveEdge(): TransitionEdge? {
        val edge = SceneStateMapper.resolveEdge(lastFrom, lastTo, lastRealScene)
        if (!SceneStateMapper.isAmbiguousStart(lastFrom)) {
            SceneStateMapper.fromTransitionStateName(lastTo)?.let { lastRealScene = it }
        }
        return edge
    }

    private fun fallbackTag(): String =
        if (SceneStateMapper.isAmbiguousStart(lastFrom)) " fb=$lastRealScene" else ""

    /** 供确定来源（锁屏边沿等）记录当前真实态。 */
    fun noteScene(state: SceneState) {
        lastRealScene = state
    }

    /** 边变化通知舞台武装帧回调；舞台异常不影响进度记账（fail-open）。 */
    private fun notifyStageEdge(edge: TransitionEdge?) {
        try {
            com.coloros16.liquidglass.hook.aod.AodStageHook.noteSystemEdge(edge)
        } catch (t: Throwable) {
            logThrottled("aod-stage-notify", "舞台通知失败 ${t.javaClass.simpleName} edge=$edge")
        }
    }

    /** 门控开着且有有效边/进度时，把系统进度写进求值桥；每 500ms 打一条参数摘要。 */
    private fun feedBridge() {
        if (!refreshGate()) return
        val bridge = bridge ?: return
        val edge = currentEdge ?: return
        val progress = lastProgress
        if (progress.isNaN()) return
        noteSupersede(bridge, edge)
        val before = bridge.clock.state()
        bridge.onSystemProgress(edge, progress)
        val after = bridge.clock.state()
        if (before.t != after.t || before.source != after.source || before.playing != after.playing) {
            logThrottled(
                "stage-pump-write",
                String.format(
                    Locale.US,
                    "edge=%s t=%.3f src=%s playing=%b saw=%b",
                    edge.name, after.t, after.source, after.playing, bridge.clock.pumpSawMovement(),
                ),
                PUMP_WRITE_LOG_MS,
            )
        }
        val p = bridge.evaluateNow()
        logThrottled(
            "aod-bridge",
            String.format(
                Locale.US,
                "edge=$edge t=%.3f mask=%b byFrame=%b r=%.0f dstA=%.2f srcA=%.2f cam=%.2f/%.2f",
                p.t, p.maskEnabled, p.maskByFrame, p.maskRadius, p.dstAlpha, p.srcAlpha, p.cameraX, p.cameraY,
            ),
            BRIDGE_LOG_MS,
        )
    }

    /**
     * 新边在旧边未播完时到达：留痕以区分「后继边」与「误判边」。
     *
     * 只在新旧边不同且旧边仍在播放时打一条，后续帧边已相等，不会连打，也不轮询。
     */
    private fun noteSupersede(bridge: TransitionBridge, edge: TransitionEdge) {
        val st = bridge.clock.state()
        val old = st.edge ?: return
        if (!st.playing || old == edge) return
        Lg.i(
            TAG,
            String.format(
                Locale.US,
                "aod-edge-supersede old=%s tOld=%.3f src=%s new=%s",
                old, st.t, st.source, edge,
            ),
        )
    }

    /** 供后续渲染消费方取同一份桥（未装载时返回 null）。 */
    fun bridgeOrNull(): TransitionBridge? = bridge

    /** 当前已识别的系统转场边；舞台挂层时据此补武装帧回调。 */
    fun currentEdgeOrNull(): TransitionEdge? = currentEdge

    /**
     * 按当前息屏样式刷新揭开能力与遮罩中心：照片类交给色键帧，其余走几何遮罩。
     *
     * 读键走 TTL 缓存，能力与中心各自只在真变时写桥；两者共用一次读键。
     */
    fun refreshRevealCapability() {
        val b = bridge ?: return
        val now = SystemClock.uptimeMillis()
        if (revealReadAt != 0L && now - revealReadAt < GATE_TTL_MS) return
        revealReadAt = now
        val a = api ?: return
        val styleId = try {
            val prefs = Prefs.read(a)
            val styleOn = prefs.getBoolean(Prefs.KEY_ALIVE_AOD_STYLE, false)
            val id = prefs.getString(Prefs.KEY_ALIVE_AOD_STYLE_ID, Prefs.DEFAULT_ALIVE_AOD_STYLE_ID)
            val byFrame = styleOn &&
                ChromakeyMask.revealsByFrame(AodSceneCatalog.photoOf(id ?: "")?.id ?: 0)
            applyReveal(b, byFrame)
            if (styleOn) id else null
        } catch (t: Throwable) {
            logThrottled("aod-reveal-read", "读样式失败 ${t.javaClass.simpleName} 保持 $revealByFrame")
            null
        }
        applyMaskCenter(b, styleId)
        applyHomeTransition(b)
    }

    /**
     * 桌面过渡选项覆盖：用户主动选了才改配方，默认「不接管」保持接缝配方。
     *
     * 与揭开能力共用同一次 TTL 读键节奏；选项与时长都只在真变时写桥。
     */
    private fun applyHomeTransition(b: TransitionBridge) {
        val a = api ?: return
        val settings = try {
            EffectConfigStore.read(a)
        } catch (t: Throwable) {
            logThrottled("aod-home-read", "桌面过渡配置读失败 ${t.javaClass.simpleName} 保持 $homeOverrideId")
            return
        }
        val id = HomeMotion.transitionForEdge(TransitionEdge.LOCK_TO_HOME, settings)
        val ms = HomeTransitionOverride.durationMs(TransitionEdge.LOCK_TO_HOME, settings)
        if (id == homeOverrideId && ms == homeOverrideMs) return
        homeOverrideId = id
        homeOverrideMs = ms
        for (edge in TransitionEdge.values()) {
            val override = HomeTransitionOverride.profileFor(edge, settings)
            val profile = TransitionProfile(
                mask = MaskLayerConfig(enabled = true),
                content = ContentLayerConfig(),
            )
            if (override != null) {
                profile.mask.enabled = override.mask.enabled
                profile.content.enabled = override.content.enabled
                profile.content.easing = override.content.easing
                profile.content.direction = override.content.direction
                profile.content.slideFraction = override.content.slideFraction
            } else {
                SeamProfilePolicy.apply(edge, profile)
            }
            b.evaluator.setProfile(edge, profile)
        }
        Lg.i(TAG, "aod-home-transition id=$id durationMs=$ms governed=${HomeTransitionOverride.governs(TransitionEdge.LOCK_TO_HOME)}")
    }

    /** 桌面过渡选项生效时该边的时长；返回 0 表示交回样式时长。 */
    fun homeTransitionMs(edge: TransitionEdge): Long {
        if (homeOverrideMs <= 0L) return 0L
        return if (HomeTransitionOverride.governs(edge)) homeOverrideMs else 0L
    }

    /** 能力位只在档位真变时写桥，避免逐帧重设。 */
    private fun applyReveal(b: TransitionBridge, byFrame: Boolean) {
        if (byFrame == revealByFrame) return
        revealByFrame = byFrame
        val caps = if (byFrame) FRAME_CAPABILITIES else HOST_CAPABILITIES
        b.setCapabilities(caps, caps)
        Lg.i(TAG, "aod-reveal caps: frame=$byFrame")
    }

    /**
     * 把样式声明的遮罩中心写进两条揭开边的配方，与预览侧同源。
     *
     * 样式关闭时回正中；中心未变则不写，避免逐帧改配方触发无谓重算。
     */
    private fun applyMaskCenter(b: TransitionBridge, styleId: String?) {
        val style = styleId?.let { AodStyleCatalog.find(it) }
        val center = FloatArray(2)
        MaskCenterPolicy.resolve(
            style?.maskCenterX ?: MaskCenterPolicy.SCREEN_CENTER,
            style?.maskCenterY ?: MaskCenterPolicy.SCREEN_CENTER,
            center,
        )
        if (!MaskCenterPolicy.changed(maskCenterX, maskCenterY, center[0], center[1])) return
        maskCenterX = center[0]
        maskCenterY = center[1]
        for (edge in MASK_CENTER_EDGES) {
            val mask = b.evaluator.profileOf(edge).mask
            mask.centerXFraction = center[0]
            mask.centerYFraction = center[1]
        }
        Lg.i(
            TAG,
            String.format(
                Locale.US,
                "aod-mask-center style=%s cx=%.3f cy=%.3f declared=%b",
                styleId ?: "-", center[0], center[1],
                style != null && MaskCenterPolicy.declared(style.maskCenterX, style.maskCenterY),
            ),
        )
    }

    private fun safeEnumName(m: Method, target: Any): String? =
        m.invoke(target)?.toString()

    /** 进度/代次/门控全字段日志；停机守卫触发时 kind 换 aod-stopped（仍是节流一条）。 */
    private fun report(kind: String) {
        val gate = refreshGate()
        val msg = String.format(
            Locale.US,
            "start=%d upd=%d emit=%d drop=%d from=%s to=%s uuid=%s p=%s state=%s " +
                "gen=%d wake=%d win=%b gate=%b stop=%b",
            startHits.get(), updateHits.get(), emitHits.get(), replayDrops.get(),
            lastFrom, lastTo, lastUuid, progressText(lastProgress), lastState,
            guard.generation(), guard.wakeSerial(), lastStartInWindow, gate, stopped,
        )
        logThrottled(if (stopped) "aod-stopped" else kind, msg)
    }

    /**
     * 停机点：SCREEN_ON 恢复打印并武装等待窗，SCREEN_OFF 置 stopped 停进度打印。
     *
     * 每代自己注册、自己在热重载停机点摘掉；不摘就会逐代堆叠，
     * 同一次屏幕事件被 N 代各处理一次（代次跳 N、停机标志互相覆盖）。
     */
    private fun registerScreenReceiver() {
        val ctx = Lg.processContext()?.applicationContext
        if (ctx == null) {
            logThrottled("aod-screen-reg", "进程 context 为空，停机点未挂（打印不停机）")
            return
        }
        unregisterScreenReceiver()
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_WALLPAPER_CHANGED)
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (screenReceiver !== this) {
                        runCatching { ctx.unregisterReceiver(this) }
                        return
                    }
                    try {
                        when (intent?.action) {
                            Intent.ACTION_SCREEN_ON -> {
                                stopped = false
                                val gen = guard.onScreenOn(SystemClock.uptimeMillis())
                                com.coloros16.liquidglass.hook.aod.AodStageHook.noteInteraction()
                                Lg.i(TAG, "aod-screen on gen=$gen wake=${guard.wakeSerial()}")
                            }
                            Intent.ACTION_SCREEN_OFF -> {
                                stopped = true
                                val gen = guard.onScreenOff()
                                Lg.i(TAG, "aod-screen off gen=$gen wake=${guard.wakeSerial()}")
                            }
                            Intent.ACTION_WALLPAPER_CHANGED -> {
                                com.coloros16.liquidglass.hook.aod.AodWorldLayer.noteWallpaperChanged()
                                com.coloros16.liquidglass.hook.aod.AodStageHook.anyLayer()?.let { l ->
                                    com.coloros16.liquidglass.hook.aod.AodWorldLayer
                                        .ensureWallpaper { l.postInvalidate() }
                                }
                                Lg.i(TAG, "aod-wallpaper changed, world source refresh queued")
                            }
                        }
                    } catch (t: Throwable) {
                        logThrottled("aod-screen-err", "屏幕广播处理失败 ${t.javaClass.simpleName}")
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                ctx.registerReceiver(receiver, filter)
            }
            screenReceiver = receiver
            Lg.i(TAG, "aod-screen 停机点已挂 stop=$stopped")
        } catch (t: Throwable) {
            logThrottled("aod-screen-reg", "屏幕广播注册失败 ${t.javaClass.simpleName} 停机点为 stub")
        }
    }

    /** 摘本代广播：热重载停机点与重复注册前都要调，否则接收器逐代堆叠。 */
    private fun unregisterScreenReceiver() {
        val old = screenReceiver ?: return
        screenReceiver = null
        val ctx = Lg.processContext()?.applicationContext ?: return
        runCatching { ctx.unregisterReceiver(old) }
            .onFailure { logThrottled("aod-screen-unreg", "摘广播失败 ${it.javaClass.simpleName}") }
    }
}

/** 进度夹取：超界压回 0..1，NaN 保留（= 本帧无进度值）。 */
internal fun normalizeProgress(raw: Float): Float = when {
    raw.isNaN() -> Float.NaN
    raw < 0f -> 0f
    raw > 1f -> 1f
    else -> raw
}

/** 进度日志文本：NaN 显示 na，其余三位小数。 */
internal fun progressText(p: Float): String =
    if (p.isNaN()) "na" else String.format(Locale.US, "%.3f", p)

/**
 * 解锁防重放骨架：屏幕亮武装等待窗、屏幕灭/亮都推进代次，
 * 过期回调按代次丢弃、start 超过等待上限 [DEFAULT_MAX_WAIT_MS] 记为不在窗内。
 * 只记数不拦截；时间由调用方传入（纯逻辑，JVM 单测覆盖）。
 */
class HandoffReplayGuard(private val maxWaitMs: Long = DEFAULT_MAX_WAIT_MS) {

    companion object {
        const val DEFAULT_MAX_WAIT_MS = 200L
    }

    private val lock = Any()
    private var generation = 0L
    private var wakeSerial = 0L
    private var armedAtMs = -1L

    /** 屏幕亮：唤醒序号+1、代次+1、武装等待窗；返回新代次。 */
    fun onScreenOn(nowMs: Long): Long = synchronized(lock) {
        wakeSerial++
        generation++
        armedAtMs = nowMs
        generation
    }

    /** 屏幕灭：代次+1（在途回调按代次作废）、关窗；返回新代次。 */
    fun onScreenOff(): Long = synchronized(lock) {
        generation++
        armedAtMs = -1L
        generation
    }

    fun generation(): Long = synchronized(lock) { generation }

    fun wakeSerial(): Long = synchronized(lock) { wakeSerial }

    /** 本次 start 是否落在最近一次屏幕亮武装的等待窗内（≤maxWaitMs）。 */
    fun startWithinWindow(nowMs: Long): Boolean = synchronized(lock) {
        armedAtMs >= 0 && nowMs >= armedAtMs && nowMs - armedAtMs <= maxWaitMs
    }

    /** 回调按代次验收：代次没被后续屏幕状态变化刷掉才算当前有效。 */
    fun stillCurrent(token: Long): Boolean = synchronized(lock) { token == generation }
}
