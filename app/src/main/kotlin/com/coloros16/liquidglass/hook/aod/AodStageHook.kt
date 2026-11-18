package com.coloros16.liquidglass.hook.aod

import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.coloros16.liquidglass.config.EffectConfigStore
import com.coloros16.liquidglass.config.LayoutConfigStore
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.engine.AodClockEntry
import com.coloros16.liquidglass.engine.AodDrift
import com.coloros16.liquidglass.engine.AodDriftSchedule
import com.coloros16.liquidglass.engine.AodStageAnchors
import com.coloros16.liquidglass.engine.AodSurfaceGate
import com.coloros16.liquidglass.engine.ClockAnchor
import com.coloros16.liquidglass.engine.ClockStateMachine
import com.coloros16.liquidglass.engine.ContentLayerMapper
import com.coloros16.liquidglass.engine.EffectOverlayPolicy
import com.coloros16.liquidglass.engine.KeyedTtlCache
import com.coloros16.liquidglass.engine.LockOnlyLayerPolicy
import com.coloros16.liquidglass.engine.SailLensMotion
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.SystemSceneMotion
import com.coloros16.liquidglass.engine.TransitionEdge
import com.coloros16.liquidglass.engine.TransitionParams
import com.coloros16.liquidglass.engine.edit.EditSurface
import com.coloros16.liquidglass.engine.edit.LockLayoutSettings
import com.coloros16.liquidglass.engine.edit.WidgetCatalog
import com.coloros16.liquidglass.engine.effect.AodDisplayGate
import com.coloros16.liquidglass.engine.effect.AodDisplayMode
import com.coloros16.liquidglass.engine.effect.AodMotion
import com.coloros16.liquidglass.engine.effect.AodMotionState
import com.coloros16.liquidglass.engine.effect.EffectParams
import com.coloros16.liquidglass.engine.effect.EffectResolver
import com.coloros16.liquidglass.engine.from
import com.coloros16.liquidglass.engine.to
import com.coloros16.liquidglass.hook.AodHandoffHook
import com.coloros16.liquidglass.hook.DesktopClockAnchorBridge
import com.coloros16.liquidglass.hook.DeviceClockFont
import com.coloros16.liquidglass.hook.HookAssetSource
import com.coloros16.liquidglass.ui.aod.AodSceneCatalog
import com.coloros16.liquidglass.ui.aod.AodSceneSpec
import com.coloros16.liquidglass.ui.aod.AodStyleCatalog
import com.coloros16.liquidglass.log.Lg
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * 三态转场舞台挂载：把自绘层挂进 AOD 根布局，逐帧喂引擎输出。
 *
 * 锚点 = AOD 根布局（`aod_off_layout`）的 attach/detach；找不到则退到窗口根。
 * 事件驱动，无轮询；全链 fail-open，任一解析失败只降级本功能。
 */
object AodStageHook {

    private const val TAG = "LgAodStage"
    private const val PKG_SYSTEMUI = AodStageAnchors.PKG_SYSTEMUI
    private const val ID_AOD_ROOT = AodStageAnchors.ID_AOD_ROOT
    private const val ID_LOCK_ROOT = AodStageAnchors.ID_LOCK_ROOT
    private const val CLASS_AOD_CLOCK_SUFFIX = AodStageAnchors.SUFFIX_AOD_CLOCK
    private const val CLASS_LOCK_CLOCK_SUFFIX = AodStageAnchors.SUFFIX_LOCK_CLOCK
    private const val ID_LOCK_CLOCK = AodStageAnchors.ID_LOCK_CLOCK
    private const val MAX_CLOCK_DEPTH = 8
    private const val SCAN_MAX_DEPTH = 40

    /** 转场插值需要的两端锚点；桌面锚点由桌面桥自己维护。 */
    private val CLOCK_ANCHOR_STATES = arrayOf(SceneState.AOD, SceneState.LOCK)

    private val SECURE_SUFFIXES = AodStageAnchors.SECURE_SUFFIXES
    private const val CLASS_AOD_ROOT = AodStageAnchors.CLASS_AOD_ROOT
    private const val CLASS_AOD_BASE = AodStageAnchors.CLASS_AOD_BASE
    private const val CLASS_SHADE_ROOT = AodStageAnchors.CLASS_SHADE_ROOT
    private const val GATE_TTL_MS = 500L
    private const val EFFECT_TTL_MS = 500L
    private const val THROTTLE_MS = 2000L
    private const val LAYER_TAG = "lg-aod-stage"
    private const val WIDGET_LAYER_TAG = "lg-lock-widgets"

    private val throttleAt = ConcurrentHashMap<String, Long>()
    private val mounted = WeakHashMap<View, AodTransitionLayer>()
    private val ids = ConcurrentHashMap<String, Int>()

    @Volatile private var installed = false
    @Volatile private var gateEnabled = false
    @Volatile private var gateReadAt = 0L
    @Volatile private var screenShow = Prefs.DEFAULT_ALIVE_AOD_SCREEN
    @Volatile private var lockShow = Prefs.DEFAULT_ALIVE_AOD_LOCK_SHOW
    @Volatile private var attachHits = 0
    @Volatile private var detachHits = 0
    @Volatile private var effectReadAt = 0L
    @Volatile private var effectId: String? = null
    @Volatile private var homeLogged = false
    @Volatile private var lastInteractAt = 0L
    @Volatile private var displayVisible = true
    @Volatile private var displayReason = "always"

    private class LayoutEntry(val settings: LockLayoutSettings, val readAtMs: Long)

    private val layoutCache = ConcurrentHashMap<EditSurface, LayoutEntry>()

    private data class DisplayEntry(
        val mode: AodDisplayMode,
        val startMinute: Int,
        val endMinute: Int,
        val timeoutMs: Long,
        val allowDynamic: Boolean,
        val readAtMs: Long,
    )

    private val DEFAULT_DISPLAY = DisplayEntry(
        AodDisplayMode.ALWAYS, 22 * 60, 7 * 60,
        AodDisplayGate.DEFAULT_POWER_SAVE_TIMEOUT_MS, false, 0L,
    )

    @Volatile private var displayCache: DisplayEntry? = null

    private data class StyleEntry(val styleId: String, val readAtMs: Long)

    @Volatile private var styleCache: StyleEntry? = null
    private val widgetNames = ConcurrentHashMap<String, String>()

    @Volatile private var widgetLayer: LockWidgetLayer? = null

    private var api: XposedInterface? = null
    private val sceneLayers = SystemSceneMotion.Layers()
    private val contentPoses = ContentLayerMapper.Poses()

    private fun logThrottled(key: String, msg: String) {
        val now = SystemClock.uptimeMillis()
        val last = throttleAt[key]
        if (last != null && now - last < THROTTLE_MS) return
        throttleAt[key] = now
        if (throttleAt.size > 128) throttleAt.clear()
        Lg.i(TAG, "$key: $msg")
    }

    fun install(api: XposedInterface, classLoader: ClassLoader) {
        if (installed) {
            logThrottled("stage-dup", "已装载 attach=$attachHits detach=$detachHits")
            return
        }
        this.api = api
        AodEffectOverlay.bind(api)
        AodWorldLayer.bind(api)
        refreshGate()
        var hook = 0
        hook += mountLifecycle(api, classLoader, CLASS_SHADE_ROOT, "窗口根")
        hook += mountAodInvalidate(api, classLoader)
        installed = hook > 0
        val stale = sweepStaleOnInstall()
        Lg.i(TAG, "stage-install hook=$hook/3 installed=$installed gate=${refreshGate()} stale=$stale")
    }

    /** 窗口根负责挂层/摘层：它的 attach/detach 是稳定锚点（反编译实证声明）。 */
    private fun mountLifecycle(
        api: XposedInterface,
        loader: ClassLoader,
        className: String,
        label: String,
    ): Int {
        val cls = try {
            Class.forName(className, false, loader)
        } catch (t: Throwable) {
            Lg.i(TAG, "stage-class-miss $label ${t.javaClass.simpleName}: ${t.message}")
            return 0
        }
        var n = 0
        if (mount(api, cls, "onAttachedToWindow", true)) n++
        if (mount(api, cls, "onDetachedFromWindow", false)) n++
        return n
    }

    /**
     * AOD 根布局的 onInvalidated：AOD 内容变化时被调用，用作「舞台已就绪」的补挂时机。
     *
     * 方法声明在 BaseRootLayout（子类不声明），必须按声明类取，否则 getDeclaredMethod 必 miss。
     */
    private fun mountAodInvalidate(api: XposedInterface, loader: ClassLoader): Int {
        val cls = try {
            Class.forName(CLASS_AOD_BASE, false, loader)
        } catch (t: Throwable) {
            Lg.i(TAG, "stage-class-miss AOD 根基类 ${t.javaClass.simpleName}: ${t.message}")
            return 0
        }
        val m = try {
            cls.getDeclaredMethod("onInvalidated")
        } catch (t: Throwable) {
            Lg.i(TAG, "stage-method-miss BaseRootLayout#onInvalidated ${t.javaClass.simpleName}")
            return 0
        }
        return try {
            api.hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val result = chain.proceed()
                try {
                    (chain.getThisObject() as? View)?.let { onAttach(it) }
                } catch (t: Throwable) {
                    Lg.e(TAG, "stage-invalidate-err ${t.javaClass.simpleName}: ${t.message}", t)
                }
                result
            }
            Lg.i(TAG, "stage-mount $CLASS_AOD_BASE#onInvalidated")
            1
        } catch (t: Throwable) {
            Lg.e(TAG, "stage-mount-fail onInvalidated ${t.javaClass.simpleName}: ${t.message}", t)
            0
        }
    }

    private fun mount(
        api: XposedInterface,
        cls: Class<*>,
        name: String,
        attach: Boolean,
    ): Boolean {
        val m = try {
            cls.getDeclaredMethod(name)
        } catch (t: Throwable) {
            Lg.i(TAG, "stage-method-miss ${cls.simpleName}#$name ${t.javaClass.simpleName}")
            return false
        }
        return try {
            api.hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val result = chain.proceed()
                try {
                    val view = chain.getThisObject() as? View
                    if (view != null) {
                        if (attach) onAttach(view) else onDetach(view)
                    }
                } catch (t: Throwable) {
                    Lg.e(TAG, "stage-callback-err $name ${t.javaClass.simpleName}: ${t.message}", t)
                }
                result
            }
            Lg.i(TAG, "stage-mount ${cls.name}#$name")
            true
        } catch (t: Throwable) {
            Lg.e(TAG, "stage-mount-fail $name ${t.javaClass.simpleName}: ${t.message}", t)
            false
        }
    }

    private fun onAttach(host: View) {
        attachHits++
        noteRoot(host)
        if (!refreshGate()) {
            logThrottled("stage-gate", "门控关闭，不挂层 attach=$attachHits")
            return
        }
        if (!host.isAttachedToWindow) {
            logThrottled("stage-notattached", "宿主未上屏 ${host.javaClass.simpleName}")
            return
        }
        val stage = resolveStage(host)
        if (stage == null) {
            logThrottled("stage-nostage", "找不到 AOD 根布局 host=${host.javaClass.simpleName}")
            return
        }
        if (stage !is ViewGroup) {
            logThrottled("stage-notgroup", "舞台不是 ViewGroup ${stage.javaClass.simpleName}")
            return
        }
        if (!surfaceGate(stateOfStage(stage))) {
            logThrottled("stage-surface-gate", "该面开关关闭，不挂层 stage=${stage.javaClass.simpleName}")
            return
        }
        if (stateOfStage(stage) == SceneState.AOD && !displayAllowsAod()) {
            logThrottled("stage-display-gate", "息屏显示门控判否，不挂层 reason=$displayReason")
            return
        }
        val existing = findLayer(stage)
        if (existing != null) {
            mounted[host] = existing
            logThrottled("stage-reuse", "复用已挂层 attach=$attachHits")
            armFrameLoop(existing)
            return
        }
        val orphan = sweepTagged(stage.rootView ?: stage, LAYER_TAG)
        if (orphan > 0) Lg.i(TAG, "stage-orphan-swept kind=stage n=$orphan host=${host.javaClass.simpleName}")
        val ctx = stage.context ?: run {
            logThrottled("stage-noctx", "舞台 context 为空")
            return
        }
        val layer = AodTransitionLayer(ctx)
        layer.tag = LAYER_TAG
        stage.addView(
            layer,
            0,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        mounted[host] = layer
        Lg.i(TAG, "stage-mounted host=${host.javaClass.simpleName} stage=${stage.javaClass.simpleName} size=${stage.width}x${stage.height}")
        armFrameLoop(layer)
    }

    private fun onDetach(host: View) {
        detachHits++
        val layer = mounted.remove(host) ?: return
        disarmFrameLoop(layer)
        releaseContentLayers(layer)
        ContentLayerApply.forget(layer.parent as? View)
        ContentLayerApply.forget(findLockRoot(layer))
        (layer.parent as? ViewGroup)?.let {
            ClockAnchorApply.forget(findAodClock(it))
            ClockAnchorApply.forget(findLockClock(it))
        }
        PillPlacementApply.forget(layer.rootView)
        releaseWidgets()
        layer.release()
        (layer.parent as? ViewGroup)?.removeView(layer)
        if (mounted.isEmpty()) AodEffectOverlay.clear()
        Lg.i(TAG, "stage-unmounted detach=$detachHits")
    }

    /**
     * 热重载前撤层：卸 hook 不会撤已挂进宿主树的自绘层，必须显式摘掉。
     *
     * 逐层撤帧回调/延迟任务、还原写过的属性、从树上移除，并清静态账本，
     * 使新一代 install 从干净状态开始（避免旧实例带着旧求值逻辑继续跑）。
     */
    fun shutdownForHotReload() {
        val roots = (sweepRoots() + windowRoots()).distinctBy { System.identityHashCode(it) }
        val hosts = ArrayList(mounted.keys)
        var removed = 0
        for (host in hosts) {
            val layer = mounted.remove(host) ?: continue
            runCatching {
                disarmFrameLoop(layer)
                releaseContentLayers(layer)
                ContentLayerApply.forget(layer.parent as? View)
                ContentLayerApply.forget(findLockRoot(layer))
                (layer.parent as? ViewGroup)?.let {
                    ClockAnchorApply.forget(findAodClock(it))
                    ClockAnchorApply.forget(findLockClock(it))
                }
                PillPlacementApply.forget(layer.rootView)
                layer.release()
                (layer.parent as? ViewGroup)?.removeView(layer)
                removed++
            }
        }
        runCatching { releaseWidgets() }
        var swept = 0
        for (root in roots) {
            swept += runCatching { sweepTagged(root, LAYER_TAG) }.getOrDefault(0)
            swept += runCatching { sweepTagged(root, WIDGET_LAYER_TAG) }.getOrDefault(0)
        }
        runCatching { AodEffectOverlay.clear() }
        runCatching { AodWorldLayer.release() }
        runCatching { HookAssetSource.reset() }
        runCatching { DeviceClockFont.reset() }
        worldCache = null
        ids.clear()
        layoutCache.clear()
        anchorKeys.clear()
        throttleAt.clear()
        widgetNames.clear()
        displayCache = null
        styleCache = null
        effectReadAt = 0L
        gateReadAt = 0L
        clockSource = ""
        clockSourceClass = ""
        installed = false
        Lg.i(TAG, "stage-hotreload-shutdown removed=$removed hosts=${hosts.size} swept=$swept roots=${roots.size}")
    }

    /** 舞台优先取 AOD 根布局；取不到退到宿主自身（窗口根）。 */
    private fun resolveStage(host: View): View? {
        val id = id(ID_AOD_ROOT)
        if (id != 0) {
            val root = host.rootView ?: host
            val found = root.findViewById<View>(id)
            if (found != null) return found
        }
        return host as? ViewGroup
    }

    private fun findLayer(stage: ViewGroup): AodTransitionLayer? {
        for (i in 0 until stage.childCount) {
            val child = stage.getChildAt(i)
            if (child is AodTransitionLayer && child.tag == LAYER_TAG) return child
        }
        return null
    }

    /**
     * 按 tag 扫本模块自绘层（含旧代次实例）：热重载换类后 `is` 判型对旧实例恒假。
     *
     * 旧实例的帧泵与延迟任务仍挂在宿主上，只清账本摘不掉它，必须按 tag 找到并从树上移除。
     */
    private fun sweepTagged(root: View?, tag: String, depth: Int = 0): Int {
        if (root !is ViewGroup || depth > SCAN_MAX_DEPTH) return 0
        var removed = 0
        for (i in root.childCount - 1 downTo 0) {
            val child = root.getChildAt(i) ?: continue
            if (child.tag == tag) {
                runCatching {
                    child.visibility = View.GONE
                    root.removeView(child)
                    removed++
                }
                continue
            }
            removed += sweepTagged(child, tag, depth + 1)
        }
        return removed
    }

    /**
     * 扫孤儿层的起点：记住每次 attach 过的窗口根。
     *
     * 不能只从 mounted 取 —— 热重载后新类 mounted 为空而旧层仍挂树上，
     * 那正是最需要清扫的时刻，按 mounted 取根会恒空。
     */
    private fun sweepRoots(): List<View> {
        val roots = ArrayList<View>()
        for (ref in seenRoots) {
            val r = ref.get() ?: continue
            if (roots.none { it === r }) roots.add(r)
        }
        for (host in mounted.keys) {
            val r = runCatching { host.rootView }.getOrNull() ?: continue
            if (roots.none { it === r }) roots.add(r)
        }
        return roots
    }

    /**
     * 装载即清扫：从进程窗口根找旧代次留下的自绘层。
     *
     * 旧层的帧泵与延迟任务仍挂宿主，且新类账本为空找不到它们；
     * 唯一跨代次不变的锚点是宿主视图树，故按 WindowManagerGlobal 的 mViews 取全部窗口根。
     */
    private fun sweepStaleOnInstall(): Int {
        val roots = windowRoots()
        var n = 0
        for (root in roots) {
            n += runCatching { sweepTagged(root, LAYER_TAG) }.getOrDefault(0)
            n += runCatching { sweepTagged(root, WIDGET_LAYER_TAG) }.getOrDefault(0)
        }
        if (n > 0 || roots.isNotEmpty()) {
            Lg.i(TAG, "stage-stale-sweep n=$n roots=${roots.size}")
        }
        return n
    }

    /** 进程内全部窗口根：WindowManagerGlobal.mViews 每项是 ViewRootImpl，取其 mView。 */
    private fun windowRoots(): List<View> {
        val out = ArrayList<View>()
        runCatching {
            val global = Class.forName("android.view.WindowManagerGlobal")
            val inst = global.getDeclaredMethod("getInstance").invoke(null) ?: return@runCatching
            val viewsField = global.getDeclaredField("mViews").apply { isAccessible = true }
            val views = viewsField.get(inst) as? java.util.ArrayList<*> ?: return@runCatching
            val rootClass = Class.forName("android.view.ViewRootImpl")
            val viewField = rootClass.getDeclaredField("mView").apply { isAccessible = true }
            for (item in views) {
                if (item == null) continue
                val v = runCatching { viewField.get(item) as? View }.getOrNull() ?: continue
                if (out.none { it === v }) out.add(v)
            }
        }.onFailure {
            logThrottled("stage-windowroots", "窗口根枚举失败 ${it.javaClass.simpleName}")
        }
        return out
    }

    private val seenRoots = ArrayList<java.lang.ref.WeakReference<View>>()

    /** 记下窗口根供后续清扫；已记过或已回收的不重复登记。 */
    private fun noteRoot(view: View?) {
        val r = runCatching { view?.rootView }.getOrNull() ?: return
        seenRoots.removeAll { it.get() == null || it.get() === r }
        seenRoots.add(java.lang.ref.WeakReference(r))
        if (seenRoots.size > 8) seenRoots.removeAt(0)
    }

    private val frameLoops = WeakHashMap<AodTransitionLayer, ViewTreeObserver.OnPreDrawListener>()

    /**
     * 帧泵：跟随宿主绘制节奏取参数；内部时间轴播放期用 Choreographer 续帧。
     *
     * 静止时不武装任何回调，只有转场在跑才逐帧（无固定间隔轮询）。
     */
    private fun armFrameLoop(layer: AodTransitionLayer) {
        if (frameLoops.containsKey(layer)) return
        val listener = ViewTreeObserver.OnPreDrawListener {
            try {
                pump(layer)
            } catch (t: Throwable) {
                Lg.e(TAG, "stage-pump-err ${t.javaClass.simpleName}: ${t.message}", t)
            }
            true
        }
        frameLoops[layer] = listener
        layer.viewTreeObserver.addOnPreDrawListener(listener)
        logThrottled("stage-arm", "帧泵已挂")
    }

    private fun disarmFrameLoop(layer: AodTransitionLayer) {
        val listener = frameLoops.remove(layer) ?: return
        runCatching { layer.viewTreeObserver.removeOnPreDrawListener(listener) }
        stopChoreographer(layer)
        disarmDrift(layer)
        disarmDisplayWake(layer)
        clockTails.remove(layer)
        logThrottled("stage-disarm", "帧泵已撤")
    }

    private val choreoCallbacks = WeakHashMap<AodTransitionLayer, Choreographer.FrameCallback>()

    /** 内部时间轴需要自己续帧：宿主静止时不会重绘，只靠 OnPreDraw 会停在中途。 */
    private fun startChoreographer(layer: AodTransitionLayer) {
        if (choreoCallbacks.containsKey(layer)) return
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val playing = try {
                    pump(layer)
                } catch (t: Throwable) {
                    Lg.e(TAG, "stage-choreo-err ${t.javaClass.simpleName}: ${t.message}", t)
                    false
                }
                if (playing && layer.isAttachedToWindow) {
                    Choreographer.getInstance().postFrameCallback(this)
                } else {
                    choreoCallbacks.remove(layer)
                    logThrottled("stage-choreo-stop", "转场收尾，帧回调已撤")
                }
            }
        }
        choreoCallbacks[layer] = callback
        Choreographer.getInstance().postFrameCallback(callback)
        logThrottled("stage-choreo-start", "内部时间轴起播，帧回调已武装")
    }

    private fun stopChoreographer(layer: AodTransitionLayer) {
        val callback = choreoCallbacks.remove(layer) ?: return
        runCatching { Choreographer.getInstance().removeFrameCallback(callback) }
    }

    /** 返回本帧是否仍在播放（供帧回调决定是否续帧）。 */
    private fun pump(layer: AodTransitionLayer): Boolean {
        if (!refreshGate()) {
            layer.bind(null, null, playing = false)
            layer.bindEffect(null, 0f)
            layer.bindWorld(null, SceneState.AOD, null, 0f, 0f, 0f, false, 0f)
            releaseContentLayers(layer)
            clockTails.remove(layer)
            return false
        }
        val pumpSurface = surfaceStateOf(layer)
        if (!surfaceGate(pumpSurface)) {
            logThrottled("stage-surface-off", "该面开关关闭，撤效果 surface=$pumpSurface")
            layer.bind(null, null, playing = false)
            layer.bindEffect(null, 0f)
            layer.bindWorld(null, SceneState.AOD, null, 0f, 0f, 0f, false, 0f)
            releaseContentLayers(layer)
            clockTails.remove(layer)
            disarmDrift(layer)
            disarmDisplayWake(layer)
            return false
        }
        if (pumpSurface == SceneState.AOD && !displayAllowsAod()) {
            releaseForDisplay(layer)
            return false
        }
        if (secureInputVisible(layer)) {
            logThrottled("stage-secure", "安全输入界面可见，撤效果")
            layer.bind(null, null, playing = false)
            layer.bindEffect(null, 0f)
            layer.bindWorld(null, SceneState.AOD, null, 0f, 0f, 0f, false, 0f)
            releaseContentLayers(layer)
            clockTails.remove(layer)
            return false
        }
        val bridge = AodHandoffHook.bridgeOrNull()
        if (bridge == null) {
            logThrottled("stage-nobridge", "求值桥未就绪")
            layer.bind(null, null, playing = false)
            layer.bindEffect(null, 0f)
            layer.bindWorld(null, SceneState.AOD, null, 0f, 0f, 0f, false, 0f)
            releaseContentLayers(layer)
            clockTails.remove(layer)
            return false
        }
        AodHandoffHook.refreshRevealCapability()
        bridge.advance()
        val params = bridge.evaluateNow()
        val edge = params.edge
        if (edge != null && !AodSurfaceGate.edge(gateEnabled, edge, screenShow, lockShow)) {
            logThrottled("stage-edge-gate", "该边两端均未开，不起播 edge=$edge")
            layer.bind(null, null, playing = false)
            clockTails.remove(layer)
            applyStatic(layer)
            return false
        }
        if (edge == null || !params.active) {
            layer.bind(null, null, playing = false)
            clockTails.remove(layer)
            applyStatic(layer)
            return false
        }
        val running = clockTails[layer]
        if (running != null && running.edge != edge) clockTails.remove(layer)
        SystemSceneMotion.sample(edge, params.t, sceneLayers)
        layer.bind(params, sceneLayers, playing = true)
        applyWorld(layer, staticState(layer), edge, params.t, sceneLayers.aodAlpha)
        applyClockAnchor(layer, edge, params.t, sceneLayers.aodClockScale)
        applyEffect(layer, EffectOverlayPolicy.visibilityAt(staticState(layer), edge, params.t))
        applyContentLayers(layer, params)
        applyPills(layer)
        applyWidgets(layer)
        val edgeDone = params.t >= 1f
        if (edgeDone) noteClockTail(layer, edge)
        if (clockTails.containsKey(layer)) startChoreographer(layer)
        logThrottled(
            "stage-frame",
            "edge=$edge t=${"%.3f".format(params.t)} mask=${params.maskEnabled} byFrame=${params.maskByFrame} r=${"%.0f".format(params.maskRadius)} dim=${"%.2f".format(sceneLayers.dim)} clock=${"%.3f".format(sceneLayers.aodClockScale)}",
        )
        return !edgeDone || clockTailPending(layer)
    }

    /** 静止态：按当前舞台可见性取静态分层，壁纸效果满强度，内容层交回系统。 */
    private fun applyStatic(layer: AodTransitionLayer) {
        val state = staticState(layer)
        SystemSceneMotion.sampleStatic(state, sceneLayers)
        layer.bind(null, sceneLayers, playing = false)
        applyWorld(layer, state, null, 0f, sceneLayers.aodAlpha)
        applyEffect(layer, EffectOverlayPolicy.visibilityOf(state))
        releaseContentLayers(layer, keepClocks = true)
        applyStaticClock(layer, state)
        if (state == SceneState.AOD) {
            armDrift(layer)
            armDisplayWake(layer)
        } else {
            disarmDrift(layer)
            disarmDisplayWake(layer)
        }
    }

    /** 静止态时钟落位：按面取宿主与锚点，息屏叠防烧屏漂移。 */
    private fun applyStaticClock(layer: AodTransitionLayer, state: SceneState) {
        val stage = layer.parent as? ViewGroup ?: return
        val surface = surfaceOf(layer)
        val clock = clockViewOf(stage, surface)
        if (clock == null) {
            logThrottled(
                "stage-static-noclock",
                "静止态未找到时钟 surface=$surface state=$state ${clockMissDiag(stage, surface)}",
            )
            return
        }
        val machine = DesktopClockAnchorBridge.machine
        syncClockAnchors(machine)
        val anchorState = if (surface == EditSurface.AOD) SceneState.AOD else SceneState.LOCK
        val anchor = machine.anchorOf(anchorState)
        ClockAnchorApply.apply(clock, anchorState, withDrift(anchor, surface), "static")
    }

    private val driftRuns = WeakHashMap<AodTransitionLayer, Runnable>()

    /**
     * 防烧屏唤醒链：一次只排一个延迟任务，到点算下一次时刻再排。
     *
     * 过渡期用帧回调跟随，停留期完全静止不武装任何回调（无固定间隔轮询）。
     */
    private fun armDrift(layer: AodTransitionLayer) {
        if (driftRuns.containsKey(layer)) return
        if (!layer.isAttachedToWindow) return
        if (!surfaceGate(SceneState.AOD)) return
        if (!displayAllowsAod()) return
        val now = SystemClock.uptimeMillis()
        val run = Runnable {
            driftRuns.remove(layer)
            if (!layer.isAttachedToWindow || !surfaceGate(SceneState.AOD)) return@Runnable
            if (!displayAllowsAod()) return@Runnable
            if (staticState(layer) != SceneState.AOD) return@Runnable
            applyStaticClock(layer, SceneState.AOD)
            refreshStillWorld(layer)
            if (AodDriftSchedule.gliding(SystemClock.uptimeMillis()) || stillWorldAnimating(layer)) {
                startDriftFrames(layer)
            } else {
                armDrift(layer)
            }
        }
        val delay = AodDriftSchedule.delayToNext(now)
        val handler = layer.handler
        val posted = handler != null && runCatching { handler.postDelayed(run, delay) }.getOrDefault(false)
        if (!posted) {
            logThrottled("stage-drift-nopost", "延迟任务未排上 handler=${handler != null}")
            return
        }
        driftRuns[layer] = run
        logThrottled("stage-drift-arm", "防烧屏下次唤醒 delay=${delay}ms")
    }

    private fun disarmDrift(layer: AodTransitionLayer) {
        val run = driftRuns.remove(layer) ?: return
        runCatching { layer.handler?.removeCallbacks(run) }
        stopDriftFrames(layer)
        logThrottled("stage-drift-disarm", "防烧屏唤醒已撤")
    }

    private val driftFrames = WeakHashMap<AodTransitionLayer, Choreographer.FrameCallback>()

    /** 过渡期逐帧跟随；过渡结束即撤回调并排下一次唤醒。 */
    private fun startDriftFrames(layer: AodTransitionLayer) {
        if (driftFrames.containsKey(layer)) return
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!layer.isAttachedToWindow || !surfaceGate(SceneState.AOD) || staticState(layer) != SceneState.AOD) {
                    driftFrames.remove(layer)
                    return
                }
                if (!displayAllowsAod()) {
                    driftFrames.remove(layer)
                    releaseForDisplay(layer)
                    return
                }
                applyStaticClock(layer, SceneState.AOD)
                refreshStillWorld(layer)
                if (AodDriftSchedule.gliding(SystemClock.uptimeMillis()) || stillWorldAnimating(layer)) {
                    Choreographer.getInstance().postFrameCallback(this)
                } else {
                    driftFrames.remove(layer)
                    armDrift(layer)
                }
            }
        }
        driftFrames[layer] = callback
        Choreographer.getInstance().postFrameCallback(callback)
        logThrottled("stage-drift-glide", "防烧屏过渡期帧回调已武装")
    }

    private fun stopDriftFrames(layer: AodTransitionLayer) {
        val callback = driftFrames.remove(layer) ?: return
        runCatching { Choreographer.getInstance().removeFrameCallback(callback) }
    }

    /** 息屏静止态是否需要逐帧：世界层在位且允许动态、且引擎真的在动才武装。 */
    private fun stillWorldAnimating(layer: AodTransitionLayer): Boolean {
        if (worldSpec() == null) return false
        val e = displayEntry()
        if (!e.allowDynamic) return false
        return motionNow(true).active
    }

    /** 逐帧重推静止态世界层输入：自转与混色随时间走，不重算系统分层。 */
    private fun refreshStillWorld(layer: AodTransitionLayer) {
        if (worldSpec() == null) return
        applyWorld(layer, SceneState.AOD, null, 0f, sceneLayers.aodAlpha)
    }

    /**
     * 内容层变换：src=边起点态、dst=边终点态，第三态不碰。
     *
     * AOD 容器 = 舞台自身（AOD 根布局）；LOCK 容器 = `keyguard_root_view`；
     * HOME 在 Launcher 进程，本进程无容器 ⇒ 只记账不写（跨进程另走桥）。
     */
    private fun applyContentLayers(layer: AodTransitionLayer, params: TransitionParams) {
        ContentLayerMapper.map(params, contentPoses)
        val stage = layer.parent as? View
        val aodContainer = if (surfaceOf(layer) == EditSurface.AOD) stage else null
        val lockContainer = findLockRoot(layer)
        ContentLayerApply.apply(aodContainer, SceneState.AOD, contentPoses.aod)
        ContentLayerApply.apply(lockContainer, SceneState.LOCK, contentPoses.lock)
        if (contentPoses.home.identity.not() && !homeLogged) {
            homeLogged = true
            Lg.i(TAG, "stage-content-home: 桌面容器在 Launcher 进程，本进程不写")
        }
    }

    /** 转场收尾：把两个容器、两侧时钟与大丸都还原成系统原样。 */
    private fun releaseContentLayers(layer: AodTransitionLayer, keepClocks: Boolean = false) {
        val stage = layer.parent as? View
        ContentLayerApply.restore(stage, SceneState.AOD)
        ContentLayerApply.restore(findLockRoot(layer), SceneState.LOCK)
        if (!keepClocks) restoreClocks(layer)
        PillPlacementApply.restoreAll(layer.rootView)
        widgetLayer?.bind(null) { it }
    }

    /** 两面时钟各自还原：只撤本件写过的那一面，另一面不受影响。 */
    private fun restoreClocks(layer: AodTransitionLayer) {
        val stage = layer.parent as? ViewGroup ?: return
        ClockAnchorApply.restore(findAodClock(stage), SceneState.AOD)
        ClockAnchorApply.restore(findLockClock(stage), SceneState.LOCK)
    }

    /** 锁屏根容器：反编译实证 `ShadeViewProviderModule` 用 `R.id.keyguard_root_view` 取。 */
    private fun findLockRoot(layer: AodTransitionLayer): View? {
        val id = id(ID_LOCK_ROOT)
        if (id == 0) return null
        val root = layer.rootView ?: return null
        return root.findViewById(id)
    }

    /** 舞台是 AOD 根布局时静止态即息屏；退到窗口根时按锁屏处理。 */
    private fun staticState(layer: AodTransitionLayer): SceneState {
        val stage = layer.parent as? View ?: return SceneState.LOCK
        return stateOfStage(stage)
    }

    /** 按舞台类名判面：息屏根布局 = AOD，其余 = LOCK。 */
    private fun stateOfStage(stage: View): SceneState =
        AodStageAnchors.surfaceOfStage(stage.javaClass.name)

    /**
     * 壁纸效果叠加：构建门只看有没有选效果，显示门按跨三态可见度给强度。
     *
     * 构建与当前态无关（息屏态选中也预处理），构建按需单飞，就绪后只重绘本层。
     */
    private fun applyEffect(layer: AodTransitionLayer, visibility: Float) {
        val id = effectiveWallpaperEffect()
        if (!EffectOverlayPolicy.needsBuild(id, null)) {
            layer.bindEffect(null, 0f)
            return
        }
        layer.bindEffect(id, visibility)
        if (layer.width <= 0 || layer.height <= 0) return
        AodEffectOverlay.request(id, layer.width, layer.height) { layer.postInvalidate() }
    }

    private class WorldEntry(val spec: AodSceneSpec?, val styleId: String, val readAtMs: Long)

    @Volatile private var worldCache: WorldEntry? = null

    /** 样式世界契约 TTL 惰性取；非 SCENE 样式返回 null（本层只画遮罩与压暗）。 */
    private fun worldSpec(): AodSceneSpec? {
        val now = SystemClock.uptimeMillis()
        val id = styleId()
        val cached = worldCache
        if (cached != null && cached.styleId == id &&
            KeyedTtlCache.fresh(cached.readAtMs, now, EFFECT_TTL_MS)
        ) return cached.spec
        val spec = runCatching { AodSceneCatalog.forStyle(id) }.getOrNull()
        worldCache = WorldEntry(spec, id, now)
        if (spec != null) AodWorldLayer.ensureWallpaper { anyLayer()?.postInvalidate() }
        return spec
    }

    /**
     * 世界层输入：按样式取契约，自转角与混色只在息屏静止态且允许动态时给。
     *
     * 无契约时显式 bindWorld(null) 清掉上一次的世界，避免换样式后残留。
     */
    private fun applyWorld(
        layer: AodTransitionLayer,
        state: SceneState,
        edge: TransitionEdge?,
        t: Float,
        aodAlpha: Float,
    ) {
        val spec = worldSpec()
        if (spec == null) {
            layer.bindWorld(null, state, edge, t, 0f, 0f, false, 0f)
            return
        }
        val dynamic = displayEntry().allowDynamic
        val motion = motionNow(dynamic)
        val still = edge == null && state == SceneState.AOD
        val spin = motion.rotationDeg
        val mix = motion.colorMix
        val tilt = if (still) motion.photoTiltDeg else 0f
        val alpha = if (spec.lens) lensAlpha(edge, t, aodAlpha) else 1f
        layer.bindWorld(
            spec, state, edge, t, alpha, spin, dynamic, mix,
            tiltDeg = tilt,
            wallpaperScale = sceneLayers.wallpaperScale,
            accent = AodStyleCatalog.find(styleId()).accent,
            api = api,
        )
    }

    /** 镜片息屏层透明度：播放期走镜片自身交叠曲线，静止态用系统分层值。 */
    private fun lensAlpha(edge: TransitionEdge?, t: Float, staticAlpha: Float): Float {
        if (edge == null) return staticAlpha
        SailLensMotion.fade(edge, t, worldFade)
        return worldFade.aodAlpha
    }

    private val worldFade = SailLensMotion.Fade()

    /** 息屏自转角与混色一次求值：只走引擎 AodMotion，与预览侧同一份真值。 */
    private fun motionNow(allowDynamic: Boolean): AodMotionState {
        worldMotion.reset()
        val a = api ?: return worldMotion
        val s = runCatching { EffectConfigStore.read(a) }.getOrNull() ?: return worldMotion
        AodMotion.evaluate(
            SystemClock.uptimeMillis(), allowDynamic && s.allowAodDynamic,
            s.param(EffectParams.AOD_COLOR_MIX),
            s.param(EffectParams.AOD_ROTATE_SPEED),
            s.param(EffectParams.AOD_PHOTO_TILT),
            worldMotion,
        )
        return worldMotion
    }

    private val worldMotion = AodMotionState()

    /** 效果配置 TTL 惰性读取；解析失败或被门槛拦住一律回 null。 */
    private fun effectiveWallpaperEffect(): String? {
        val now = SystemClock.uptimeMillis()
        if (effectReadAt != 0L && now - effectReadAt < EFFECT_TTL_MS) return effectId
        effectReadAt = now
        val a = api ?: return effectId
        effectId = try {
            val settings = EffectConfigStore.read(a)
            val resolved = EffectResolver.resolve(settings)
            val slot = resolved.lockWallpaper
            if (slot.applied && !slot.noop) slot.effectiveId else null
        } catch (t: Throwable) {
            logThrottled("stage-effect-read", "效果配置读失败 ${t.javaClass.simpleName}")
            effectId
        }
        return effectId
    }

    /**
     * 息屏显示配置 TTL 惰性读取：显示模式 / 定时区间 / 节能时长 / 动态总闸。
     *
     * 读失败保持上一份（首次失败回默认 = 全天可见），只降级本判定不连坐其它链。
     */
    private fun displayEntry(): DisplayEntry {
        val now = SystemClock.uptimeMillis()
        val cached = displayCache
        if (cached != null && KeyedTtlCache.fresh(cached.readAtMs, now, EFFECT_TTL_MS)) return cached
        val a = api ?: return cached ?: DEFAULT_DISPLAY
        val next = try {
            val s = EffectConfigStore.read(a)
            DisplayEntry(
                s.displayMode, s.scheduleStartMinute, s.scheduleEndMinute,
                (s.param(EffectParams.AOD_POWER_SAVE_TIMEOUT) * 1000f).toLong(),
                s.allowAodDynamic, now,
            )
        } catch (t: Throwable) {
            logThrottled("stage-display-read", "息屏显示配置读失败 ${t.javaClass.simpleName}")
            cached?.copy(readAtMs = now) ?: DisplayEntry(
                DEFAULT_DISPLAY.mode, DEFAULT_DISPLAY.startMinute, DEFAULT_DISPLAY.endMinute,
                DEFAULT_DISPLAY.timeoutMs, DEFAULT_DISPLAY.allowDynamic, now,
            )
        }
        displayCache = next
        return next
    }

    /**
     * 息屏显示门控：全天 / 定时 / 节能三档决定息屏面是否上岗。
     *
     * 只管息屏面，锁屏面与转场边不受影响（锁屏有自己的开关）。
     * 节能档的空闲时长按「上次交互到现在」算，交互由屏幕广播与解锁事件推。
     */
    private fun displayAllowsAod(): Boolean {
        val e = displayEntry()
        if (e.mode == AodDisplayMode.ALWAYS) {
            noteDisplay(true, "always")
            return true
        }
        val now = SystemClock.uptimeMillis()
        val idle = if (lastInteractAt == 0L) 0L else now - lastInteractAt
        val decision = AodDisplayGate.decide(
            e.mode, e.startMinute, e.endMinute, minuteOfDay(), idle, e.timeoutMs, e.allowDynamic,
        )
        noteDisplay(decision.visible, decision.reason)
        return decision.visible
    }

    private fun noteDisplay(visible: Boolean, reason: String) {
        if (visible == displayVisible && reason == displayReason) return
        displayVisible = visible
        displayReason = reason
        Lg.i(TAG, "stage-display visible=$visible reason=$reason")
    }

    /** 当前本地时刻的当日分钟数；取不到时钟回 0。 */
    private fun minuteOfDay(): Int {
        val cal = runCatching { java.util.Calendar.getInstance() }.getOrNull() ?: return 0
        return cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
    }

    /** 交互事件：节能档据此重算空闲时长（屏幕亮起 / 解锁都算交互）。 */
    fun noteInteraction() {
        lastInteractAt = SystemClock.uptimeMillis()
        val layer = anyLayer() ?: return
        if (staticState(layer) != SceneState.AOD) return
        if (!displayAllowsAod()) return
        armDisplayWake(layer)
        layer.postInvalidate()
    }

    private val displayWakes = WeakHashMap<AodTransitionLayer, Runnable>()

    /**
     * 显示门控的一次性唤醒：算出下次翻转时刻排一个延迟任务，到点再算下一个。
     *
     * 全天档不排任何任务；已超时且无自然翻转点也不排（靠交互事件），无固定间隔轮询。
     */
    private fun armDisplayWake(layer: AodTransitionLayer) {
        if (displayWakes.containsKey(layer)) return
        if (!layer.isAttachedToWindow) return
        val e = displayEntry()
        val now = SystemClock.uptimeMillis()
        val idle = if (lastInteractAt == 0L) 0L else now - lastInteractAt
        val delay = AodDisplayGate.nextFlipDelayMs(
            e.mode, e.startMinute, e.endMinute, minuteOfDay(), idle, e.timeoutMs,
        )
        if (delay <= 0L) {
            logThrottled("stage-display-nowake", "无自然翻转点 mode=${e.mode} reason=$displayReason")
            return
        }
        val run = Runnable {
            displayWakes.remove(layer)
            if (!layer.isAttachedToWindow) return@Runnable
            if (staticState(layer) != SceneState.AOD) return@Runnable
            if (!displayAllowsAod()) {
                releaseForDisplay(layer)
            } else {
                layer.postInvalidate()
                armDisplayWake(layer)
            }
        }
        val handler = layer.handler
        val posted = handler != null && runCatching { handler.postDelayed(run, delay) }.getOrDefault(false)
        if (!posted) {
            logThrottled("stage-display-nopost", "显示唤醒未排上 handler=${handler != null}")
            return
        }
        displayWakes[layer] = run
        logThrottled("stage-display-wake", "显示门控下次翻转 delay=${delay}ms mode=${e.mode}")
    }

    private fun disarmDisplayWake(layer: AodTransitionLayer) {
        val run = displayWakes.remove(layer) ?: return
        runCatching { layer.handler?.removeCallbacks(run) }
        logThrottled("stage-display-disarm", "显示门控唤醒已撤")
    }

    /** 显示门控判否：撤本层全部效果并交回系统原样。 */
    private fun releaseForDisplay(layer: AodTransitionLayer) {
        logThrottled("stage-display-off", "息屏显示门控判否，撤效果 reason=$displayReason")
        layer.bind(null, null, playing = false)
        layer.bindEffect(null, 0f)
        layer.bindWorld(null, SceneState.AOD, null, 0f, 0f, 0f, false, 0f)
        releaseContentLayers(layer)
        clockTails.remove(layer)
        disarmDrift(layer)
        disarmDisplayWake(layer)
        stopChoreographer(layer)
    }

    /** 任取一个在位的自绘层（边沿事件触发时舞台可能尚未与宿主关联）。 */
    fun anyLayer(): AodTransitionLayer? =
        mounted.values.firstOrNull { it.isAttachedToWindow }

    /**
     * 系统只给状态不给进度的边（锁屏↔桌面）：起内部时间轴。
     *
     * 时长取引擎真值；已在播放或系统泵在喂时不重复起播。
     */
    fun playEdge(layer: AodTransitionLayer, edge: TransitionEdge) {
        if (!refreshGate()) {
            logThrottled("stage-play-gate", "门控关闭，不起播 edge=$edge")
            return
        }
        if (!AodSurfaceGate.edge(gateEnabled, edge, screenShow, lockShow)) {
            logThrottled("stage-play-surface", "该边两端均未开，不起播 edge=$edge")
            return
        }
        if ((edge.from == SceneState.AOD || edge.to == SceneState.AOD) && !displayAllowsAod()) {
            logThrottled("stage-play-display", "息屏显示门控判否，不起播 edge=$edge reason=$displayReason")
            return
        }
        val bridge = AodHandoffHook.bridgeOrNull() ?: run {
            logThrottled("stage-play-nobridge", "求值桥未就绪 edge=$edge")
            return
        }
        val duration = edgeDurationMs(edge)
        if (bridge.ensurePlaying(edge, duration)) {
            Lg.i(TAG, "stage-play edge=$edge duration=$duration style=${styleId()}")
            startChoreographer(layer)
        } else {
            logThrottled("stage-play-skip", "已在播放或系统泵接管 edge=$edge")
        }
    }

    /**
     * 当前息屏样式 id：TTL 惰性读，读失败保持上一份。
     *
     * 样式开关关闭时回默认样式（与预览侧同一口径：未启用样式即按默认样式的时长走）。
     */
    private fun styleId(): String {
        val now = SystemClock.uptimeMillis()
        val cached = styleCache
        if (cached != null && KeyedTtlCache.fresh(cached.readAtMs, now, EFFECT_TTL_MS)) return cached.styleId
        val a = api ?: return cached?.styleId ?: Prefs.DEFAULT_ALIVE_AOD_STYLE_ID
        val next = try {
            val prefs = Prefs.read(a)
            val on = prefs.getBoolean(Prefs.KEY_ALIVE_AOD_STYLE, false)
            val id = prefs.getString(Prefs.KEY_ALIVE_AOD_STYLE_ID, Prefs.DEFAULT_ALIVE_AOD_STYLE_ID)
            if (on && !id.isNullOrEmpty()) id else Prefs.DEFAULT_ALIVE_AOD_STYLE_ID
        } catch (t: Throwable) {
            logThrottled("stage-style-read", "样式键读失败 ${t.javaClass.simpleName}")
            cached?.styleId ?: Prefs.DEFAULT_ALIVE_AOD_STYLE_ID
        }
        styleCache = StyleEntry(next, now)
        return next
    }

    /**
     * 该边总时长：按当前样式取真值（启航最长轨 / 照片类各轨最大值 / 其余系统内容层时长）。
     *
     * 与预览侧走同一个 [AodSceneCatalog.durationOf]，避免两侧各算一套导致真机比预览快。
     */
    private fun edgeDurationMs(edge: TransitionEdge): Long {
        val id = styleId()
        val ms = runCatching { AodSceneCatalog.durationOf(id, edge) }.getOrDefault(0L)
        if (ms > 0L) return ms
        logThrottled("stage-duration-fallback", "样式时长取不到，回系统时长 style=$id edge=$edge")
        return SystemSceneMotion.durationOf(edge)
    }

    /**
     * 时钟落位：转场期用状态机两端锚点插值，再乘系统进场缩放。
     *
     * [systemScale] 只对息屏面生效；锁屏面没有息屏进场动画，乘上去会凭空缩小。
     */
    private fun applyClockAnchor(
        layer: AodTransitionLayer,
        edge: TransitionEdge,
        t: Float,
        systemScale: Float,
    ) {
        val stage = layer.parent as? ViewGroup ?: return
        val surface = surfaceOf(layer)
        val clock = clockViewOf(stage, surface) ?: run {
            logThrottled("stage-noclock", "未找到时钟视图 surface=$surface edge=$edge")
            return
        }
        val machine = DesktopClockAnchorBridge.machine
        syncClockAnchors(machine)
        machine.onTransition(edge, t)
        val pose = machine.pose
        val entry = if (surface == EditSurface.AOD) systemScale else 1f
        val base = ClockAnchor(
            pose.centerX, pose.centerY, pose.scale * entry, pose.alpha, pose.rotationDeg,
        )
        val state = if (surface == EditSurface.AOD) SceneState.AOD else SceneState.LOCK
        ClockAnchorApply.apply(clock, state, withDrift(base, surface), "stage")
    }

    private data class AnchorKey(val x: Float, val y: Float, val scale: Float)

    private val anchorKeys = HashMap<SceneState, AnchorKey>()

    /**
     * 把编辑配置灌成状态机两端锚点，让转场期位置与大小真插值。
     *
     * 锚点存静止终值（含用户缩放），故转场链不再叠编辑覆盖，避免位置被覆盖、缩放被乘两次。
     * 值未变则不写，避免每帧触发状态机跟随补间。
     */
    private fun syncClockAnchors(machine: ClockStateMachine) {
        for (state in CLOCK_ANCHOR_STATES) {
            val surface = if (state == SceneState.AOD) EditSurface.AOD else EditSurface.LOCK
            val layout = layoutFor(surface)
            val key = AnchorKey(layout.clockX, layout.clockY, layout.clockScale)
            if (anchorKeys[state] == key) continue
            anchorKeys[state] = key
            machine.setAnchor(
                state,
                ClockAnchor(centerX = key.x, centerY = key.y, scale = key.scale),
                SystemClock.uptimeMillis(),
                follow = false,
            )
            logThrottled(
                "stage-anchor-sync",
                "state=$state x=${"%.3f".format(key.x)} y=${"%.3f".format(key.y)} scale=${"%.3f".format(key.scale)}",
            )
        }
    }

    private class ClockTail(val edge: TransitionEdge, val startMs: Long, val edgeMs: Long)

    private val clockTails = WeakHashMap<AodTransitionLayer, ClockTail>()

    /** 边结束时登记补尾起点；只有终点为息屏且时钟时长超出边长才需要补。 */
    private fun noteClockTail(layer: AodTransitionLayer, edge: TransitionEdge) {
        if (!AodClockEntry.entering(edge)) {
            clockTails.remove(layer)
            return
        }
        val edgeMs = edgeDurationMs(edge)
        if (AodClockEntry.tailMs(edgeMs) <= 0L) {
            clockTails.remove(layer)
            return
        }
        if (clockTails.containsKey(layer)) return
        clockTails[layer] = ClockTail(edge, SystemClock.uptimeMillis(), edgeMs)
        logThrottled("stage-clock-tail", "时钟补尾起 edge=$edge tail=${AodClockEntry.tailMs(edgeMs)}ms")
    }

    /** 补尾是否仍需续帧；已走完则清账并落一次终态。 */
    private fun clockTailPending(layer: AodTransitionLayer): Boolean {
        val tail = clockTails[layer] ?: return false
        val edgeMs = tail.edgeMs
        val elapsed = SystemClock.uptimeMillis() - tail.startMs
        if (AodClockEntry.tailDone(elapsed, edgeMs)) {
            clockTails.remove(layer)
            applyStatic(layer)
            logThrottled("stage-clock-tail-done", "时钟补尾走完 elapsed=${elapsed}ms")
            return false
        }
        val scale = AodClockEntry.scaleAt(AodClockEntry.tailProgress(elapsed, edgeMs))
        applyClockTailScale(layer, scale)
        return true
    }

    /** 补尾期只改缩放：锚点已含用户缩放，此处只乘系统进场因子；补尾只属息屏面。 */
    private fun applyClockTailScale(layer: AodTransitionLayer, scale: Float) {
        val stage = layer.parent as? ViewGroup ?: return
        val surface = surfaceOf(layer)
        if (surface != EditSurface.AOD) return
        val clock = findAodClock(stage) ?: return
        val machine = DesktopClockAnchorBridge.machine
        syncClockAnchors(machine)
        val anchor = machine.anchorOf(SceneState.AOD)
        val scaled = anchor.copy(scale = anchor.scale * scale)
        ClockAnchorApply.apply(clock, SceneState.AOD, withDrift(scaled, surface), "clock-tail")
    }

    /** 息屏防烧屏：纵向叠漂移；转场期不叠（避免与转场位移打架）。 */
    private fun withDrift(anchor: ClockAnchor, surface: EditSurface): ClockAnchor {
        if (surface != EditSurface.AOD) return anchor
        val dy = AodDrift.offsetY(SystemClock.uptimeMillis())
        if (kotlin.math.abs(dy) < 0.00001f) return anchor
        return anchor.copy(centerY = (anchor.centerY + dy).coerceIn(0f, 1f))
    }

    /** 大丸落位：只在锁屏面生效，息屏面没有底部按钮。 */
    private fun applyPills(layer: AodTransitionLayer) {
        val mount = LockOnlyLayerPolicy.shouldMount(
            LockOnlyLayerPolicy.Kind.PILLS,
            surfaceOf(layer),
            lockGate = surfaceGate(SceneState.LOCK),
            aodGate = surfaceGate(SceneState.AOD),
        )
        if (!mount) {
            logThrottled("stage-pill-gate", "锁屏面开关关闭，大丸不落位")
            if (LockOnlyLayerPolicy.shouldRelease(
                    LockOnlyLayerPolicy.Kind.PILLS,
                    mounted = PillPlacementApply.tracked(),
                    lockGate = surfaceGate(SceneState.LOCK),
                    aodGate = surfaceGate(SceneState.AOD),
                    current = surfaceOf(layer),
                )
            ) PillPlacementApply.restoreAll(layer.rootView)
            return
        }
        PillPlacementApply.apply(layer.rootView, layoutFor(EditSurface.LOCK))
    }

    /**
     * 小组件自绘层：挂进锁屏根容器，按锁屏编辑配置画。
     *
     * 小组件是本模块能力，系统锁屏无对应容器，故自绘不去找系统容器。
     */
    private fun applyWidgets(layer: AodTransitionLayer) {
        val mount = LockOnlyLayerPolicy.shouldMount(
            LockOnlyLayerPolicy.Kind.WIDGETS,
            surfaceOf(layer),
            lockGate = surfaceGate(SceneState.LOCK),
            aodGate = surfaceGate(SceneState.AOD),
        )
        if (!mount) {
            logThrottled("stage-widget-gate", "锁屏面开关关闭，小组件层不挂")
            if (LockOnlyLayerPolicy.shouldRelease(
                    LockOnlyLayerPolicy.Kind.WIDGETS,
                    mounted = widgetLayer != null,
                    lockGate = surfaceGate(SceneState.LOCK),
                    aodGate = surfaceGate(SceneState.AOD),
                    current = surfaceOf(layer),
                )
            ) releaseWidgets()
            return
        }
        val host = findLockRoot(layer) as? ViewGroup
        if (host == null) {
            logThrottled("stage-widget-nohost", "锁屏根未就绪，小组件层不挂")
            return
        }
        val existing = findWidgetLayer(host)
        val target = existing ?: run {
            val orphan = sweepTagged(host.rootView ?: host, WIDGET_LAYER_TAG)
            if (orphan > 0) Lg.i(TAG, "stage-orphan-swept kind=widgets n=$orphan")
            val created = LockWidgetLayer(host.context)
            created.tag = WIDGET_LAYER_TAG
            host.addView(
                created,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            Lg.i(TAG, "stage-widget-mounted host=${host.javaClass.simpleName}")
            created
        }
        widgetLayer = target
        target.bind(layoutFor(EditSurface.LOCK)) { typeId -> widgetName(host, typeId) }
    }

    private fun findWidgetLayer(host: ViewGroup): LockWidgetLayer? {
        for (i in 0 until host.childCount) {
            val child = host.getChildAt(i)
            if (child is LockWidgetLayer && child.tag == WIDGET_LAYER_TAG) return child
        }
        return null
    }

    /** 小组件名称：宿主资源里查模块文案键，查不到回退类型 id（结果缓存）。 */
    private fun widgetName(host: View, typeId: String): String {
        widgetNames[typeId]?.let { return it }
        val key = WidgetCatalog.find(typeId)?.nameKey ?: return typeId
        val id = runCatching {
            host.resources.getIdentifier(key, "string", host.context.packageName)
        }.getOrDefault(0)
        val name = if (id != 0) {
            runCatching { host.resources.getString(id) }.getOrDefault(typeId)
        } else {
            logThrottled("stage-widget-name-$typeId", "文案键 $key 在宿主资源里查不到，回退 id")
            typeId
        }
        widgetNames[typeId] = name
        return name
    }

    /** 撤小组件层：清空内容并从宿主摘除。 */
    private fun releaseWidgets() {
        val target = widgetLayer ?: return
        target.release()
        (target.parent as? ViewGroup)?.removeView(target)
        widgetLayer = null
    }

    /** 舞台归属面：AOD 根布局 = 息屏，其余按锁屏。 */
    private fun surfaceOf(layer: AodTransitionLayer): EditSurface {
        val stage = layer.parent as? View ?: return EditSurface.LOCK
        return if (AodStageAnchors.isAodRoot(stage.javaClass.name)) EditSurface.AOD else EditSurface.LOCK
    }

    /** 编辑配置 TTL 惰性读取，按面各自计时；读失败回该面默认（等于不改锚点）。 */
    private fun layoutFor(surface: EditSurface): LockLayoutSettings {
        val now = SystemClock.uptimeMillis()
        val cached = layoutCache[surface]
        if (cached != null && KeyedTtlCache.fresh(cached.readAtMs, now, EFFECT_TTL_MS)) return cached.settings
        val a = api ?: return cached?.settings ?: LockLayoutSettings.defaults(surface)
        val settings = try {
            if (surface == EditSurface.AOD) LayoutConfigStore.readAod(a) else LayoutConfigStore.readLock(a)
        } catch (t: Throwable) {
            logThrottled("stage-layout-read", "编辑配置读失败 ${t.javaClass.simpleName} surface=$surface")
            cached?.settings ?: LockLayoutSettings.defaults(surface)
        }
        layoutCache[surface] = LayoutEntry(settings, now)
        return settings
    }

    /**
     * AOD 时钟：优先按 id 认容器，取不到退类名后缀。
     *
     * 时钟内容可能由插件提供（类名不在本进程类空间），故 id 优先；
     * 每一步命中来源都记账，miss 时按记账打可裁决诊断。
     */
    private fun findAodClock(stage: ViewGroup): View? {
        val root = (stage.rootView as? ViewGroup) ?: stage
        for (name in AodStageAnchors.AOD_CLOCK_IDS) {
            val rid = id(name)
            if (rid == 0) continue
            val byId = runCatching { root.findViewById<View>(rid) }.getOrNull()
            if (byId != null && byId.width > 0 && byId.height > 0) {
                noteClockSource("id:$name", byId)
                return byId
            }
        }
        val byClass = findByClassSuffix(root, CLASS_AOD_CLOCK_SUFFIX, depth = 0)
        if (byClass != null) {
            noteClockSource("class:$CLASS_AOD_CLOCK_SUFFIX", byClass)
            return byClass
        }
        val inner = findByClassSuffix(root, AodStageAnchors.SUFFIX_AOD_CLOCK_INNER, depth = 0)
        if (inner != null) {
            noteClockSource("class:${AodStageAnchors.SUFFIX_AOD_CLOCK_INNER}", inner)
            return inner
        }
        return null
    }

    /** 命中来源只在真变时打一条，避免逐帧刷屏。 */
    private fun noteClockSource(source: String, view: View) {
        if (source == clockSource && view.javaClass.name == clockSourceClass) return
        clockSource = source
        clockSourceClass = view.javaClass.name
        Lg.i(TAG, "stage-clock-source via=$source class=$clockSourceClass size=${view.width}x${view.height}")
    }

    @Volatile private var clockSource = ""
    @Volatile private var clockSourceClass = ""

    /** 锁屏时钟：优先稳定 id，取不到退类名（宿主是 FrameLayout 子类，插件加载前后类名不变）。 */
    private fun findLockClock(stage: ViewGroup): View? {
        val root = (stage.rootView as? ViewGroup) ?: stage
        val byId = runCatching { root.findViewById<View>(id(ID_LOCK_CLOCK)) }.getOrNull()
        if (byId != null) return byId
        return findByClassSuffix(root, CLASS_LOCK_CLOCK_SUFFIX, depth = 0)
    }

    /** 按面取时钟视图：息屏面与锁屏面是两个不同宿主。 */
    private fun clockViewOf(stage: ViewGroup, surface: EditSurface): View? =
        if (surface == EditSurface.AOD) findAodClock(stage) else findLockClock(stage)

    private fun findByClassSuffix(group: ViewGroup, suffix: String, depth: Int): View? {
        if (depth > MAX_CLOCK_DEPTH) return null
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i) ?: continue
            if (child.javaClass.name.endsWith(suffix)) return child
            if (child is ViewGroup) {
                findByClassSuffix(child, suffix, depth + 1)?.let { return it }
            }
        }
        return null
    }

    /**
     * 时钟 miss 的可裁决诊断：扫全树（不限深度）报候选 id 是否存在、时钟类名是否出现、树规模。
     *
     * 只在 miss 时按需算一次并由调用方节流，不进逐帧热路径。
     */
    private fun clockMissDiag(stage: ViewGroup, surface: EditSurface): String {
        val root = (stage.rootView as? ViewGroup) ?: stage
        val scan = ClockTreeScan()
        scan.walk(root, 0)
        val idHits = StringBuilder()
        for (name in AodStageAnchors.AOD_CLOCK_IDS) {
            val rid = ids[name] ?: 0
            if (rid == 0) continue
            val v = runCatching { root.findViewById<View>(rid) }.getOrNull() ?: continue
            idHits.append(name).append('=').append(v.width).append('x').append(v.height)
            idHits.append('@').append(scan.depthOf(v)).append(' ')
        }
        return "root=${root.javaClass.simpleName} nodes=${scan.nodes} maxDepth=${scan.maxDepth} " +
            "clockClassHits=${scan.clockHits} deepClockHits=${scan.deepClockHits} " +
            "idPresent=[${idHits.toString().trim()}] surface=$surface"
    }

    /** 一次性全树扫描：统计规模、时钟类名命中与深度，供 miss 诊断裁决三种可能。 */
    private class ClockTreeScan {
        var nodes = 0
        var maxDepth = 0
        var clockHits = 0
        var deepClockHits = 0
        private val depths = HashMap<Int, Int>()

        fun walk(group: ViewGroup, depth: Int) {
            if (depth > SCAN_MAX_DEPTH) return
            if (depth > maxDepth) maxDepth = depth
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i) ?: continue
                nodes++
                depths[System.identityHashCode(child)] = depth + 1
                val name = child.javaClass.name
                if (name.endsWith(CLASS_AOD_CLOCK_SUFFIX) ||
                    name.endsWith(AodStageAnchors.SUFFIX_AOD_CLOCK_INNER)
                ) {
                    clockHits++
                    if (depth + 1 > MAX_CLOCK_DEPTH) deepClockHits++
                }
                if (child is ViewGroup) walk(child, depth + 1)
            }
        }

        fun depthOf(view: View): Int = depths[System.identityHashCode(view)] ?: -1
    }

    /** 安全门：密码键盘 / 生物识别提示可见时不做任何效果，避免盖住安全界面。 */
    private fun secureInputVisible(layer: AodTransitionLayer): Boolean {
        val root = (layer.rootView as? ViewGroup) ?: return false
        for (suffix in SECURE_SUFFIXES) {
            val v = findByClassSuffix(root, suffix, depth = 0) ?: continue
            if (v.isShown && v.alpha > 0.01f && AodStageAnchors.isSecureInput(v.javaClass.name)) return true
        }
        return false
    }

    private fun id(name: String): Int {
        ids[name]?.let { return it }
        val ctx = Lg.processContext() ?: return 0
        val value = runCatching { ctx.resources.getIdentifier(name, "id", PKG_SYSTEMUI) }.getOrDefault(0)
        if (value != 0) {
            ids[name] = value
        } else {
            logThrottled("stage-id-miss", "id.$name 解析不到")
        }
        return value
    }

    private fun refreshGate(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (gateReadAt != 0L && now - gateReadAt < GATE_TTL_MS) return gateEnabled
        gateReadAt = now
        val a = api ?: return gateEnabled
        val prev = gateEnabled
        val prevScreen = screenShow
        val prevLock = lockShow
        gateEnabled = try {
            val prefs = Prefs.read(a)
            screenShow = prefs.getBoolean(Prefs.KEY_ALIVE_AOD_SCREEN, Prefs.DEFAULT_ALIVE_AOD_SCREEN)
            lockShow = prefs.getBoolean(Prefs.KEY_ALIVE_AOD_LOCK_SHOW, Prefs.DEFAULT_ALIVE_AOD_LOCK_SHOW)
            AodSurfaceGate.global(
                prefs.getBoolean(Prefs.KEY_MASTER, false),
                prefs.getBoolean(Prefs.KEY_ALIVE_AOD_HANDOFF, false),
            )
        } catch (t: Throwable) {
            logThrottled("stage-gate-read", "读键失败 ${t.javaClass.simpleName} 保持 $prev")
            screenShow = prevScreen
            lockShow = prevLock
            prev
        }
        if (prev != gateEnabled || prevScreen != screenShow || prevLock != lockShow) {
            Lg.i(TAG, "stage-gate global=$gateEnabled screen=$screenShow lock=$lockShow")
        }
        return gateEnabled
    }

    /** 该面是否接管：全局闸 ∧ 分面开关。读键走 [refreshGate] 的 TTL 缓存。 */
    private fun surfaceGate(surface: SceneState): Boolean {
        refreshGate()
        return AodSurfaceGate.surface(gateEnabled, surface, screenShow, lockShow)
    }

    /** 自绘层所属面：息屏根布局 = AOD，其余（窗口根）= LOCK。 */
    private fun surfaceStateOf(layer: AodTransitionLayer): SceneState = staticState(layer)
}
