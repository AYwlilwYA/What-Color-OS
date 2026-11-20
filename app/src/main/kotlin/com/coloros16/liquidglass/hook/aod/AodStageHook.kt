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
import com.coloros16.liquidglass.engine.AodSpinGate
import com.coloros16.liquidglass.engine.AodStageAnchors
import com.coloros16.liquidglass.engine.AodSurfaceGate
import com.coloros16.liquidglass.engine.ClockAnchor
import com.coloros16.liquidglass.engine.ClockAnchorCompose
import com.coloros16.liquidglass.engine.ClockAnchorMapper
import com.coloros16.liquidglass.engine.ClockHandoffPolicy
import com.coloros16.liquidglass.engine.ClockStateMachine
import com.coloros16.liquidglass.engine.ContentLayerMapper
import com.coloros16.liquidglass.engine.ContentMotionSource
import com.coloros16.liquidglass.engine.EffectOverlayPolicy
import com.coloros16.liquidglass.engine.KeyedTtlCache
import com.coloros16.liquidglass.engine.LockOnlyLayerPolicy
import com.coloros16.liquidglass.engine.SailLensMotion
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.engine.SystemSceneMotion
import com.coloros16.liquidglass.engine.TransitionEdge
import com.coloros16.liquidglass.engine.WallpaperZoomSettle
import com.coloros16.liquidglass.engine.TransitionParams
import com.coloros16.liquidglass.engine.TransitionStartDelay
import com.coloros16.liquidglass.engine.edit.EditSurface
import com.coloros16.liquidglass.engine.edit.LockLayoutSettings
import com.coloros16.liquidglass.engine.edit.WidgetCatalog
import com.coloros16.liquidglass.engine.effect.AodDisplayGate
import com.coloros16.liquidglass.engine.effect.AodDisplayMode
import com.coloros16.liquidglass.engine.effect.AodMotion
import com.coloros16.liquidglass.engine.effect.AodMotionState
import com.coloros16.liquidglass.engine.effect.EffectParams
import com.coloros16.liquidglass.engine.effect.EffectCapabilities
import com.coloros16.liquidglass.engine.effect.EffectResolver
import com.coloros16.liquidglass.engine.effect.panoramaAodActiveOf
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

    /** 由编辑配置合成的两端锚点；桌面锚点由桌面桥按真机矩形自己维护。 */
    private val CLOCK_ANCHOR_STATES = arrayOf(SceneState.AOD, SceneState.LOCK)

    private data class HomeKey(val x: Float, val y: Float, val scale: Float, val alpha: Float)

    private var homeAnchorKey: HomeKey? = null

    private val SECURE_SUFFIXES = AodStageAnchors.SECURE_SUFFIXES
    private const val CLASS_AOD_ROOT = AodStageAnchors.CLASS_AOD_ROOT
    private const val CLASS_AOD_BASE = AodStageAnchors.CLASS_AOD_BASE
    private const val CLASS_SHADE_ROOT = AodStageAnchors.CLASS_SHADE_ROOT
    private const val GATE_TTL_MS = 500L
    private const val EFFECT_TTL_MS = 500L
    private const val THROTTLE_MS = 2000L
    private const val FAST_THROTTLE_MS = 120L
    private const val SPIN_GATE_LOG_MS = 1000L
    private const val LAYER_TAG = "lg-aod-stage"
    private const val WIDGET_LAYER_TAG = "lg-lock-widgets"

    private val fastAt = HashMap<String, Long>()
    private var fastDoneEdge: TransitionEdge? = null

    private var playEdge: TransitionEdge? = null
    private var playEdgeMs: Long = 0L
    private var clockDoneEdge: TransitionEdge? = null
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
    @Volatile private var effectSlot = EffectSlot(null, null, 1f)
    @Volatile private var gyroCached: Boolean? = null
    @Volatile private var lastEffectGateKey = ""
    @Volatile private var lastEffectInputKey = ""
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
    /** 入边补间：进边第一帧的缩放与上一静止态不同时补一段（`HOME_TO_LOCK` 起点 1.0、边内恒 1.1）。 */
    private var enterZoomFrom = 0f
    private var enterZoomTo = 0f
    private var enterZoomStartMs = 0L
    private var enterZoomEdge: TransitionEdge? = null
    private val enterFromLayers = SystemSceneMotion.Layers()
    private val contentPoses = ContentLayerMapper.Poses()

    private fun logThrottled(key: String, msg: String) {
        val now = SystemClock.uptimeMillis()
        val last = throttleAt[key]
        if (last != null && now - last < THROTTLE_MS) return
        throttleAt[key] = now
        if (throttleAt.size > 128) throttleAt.clear()
        Lg.i(TAG, "$key: $msg")
    }

    /** 转场期专用低节流诊断：只在播放路径里调用，静止态零输出。 */
    private fun logFast(key: String, msg: String) {
        val now = SystemClock.uptimeMillis()
        val last = fastAt[key]
        if (last != null && now - last < FAST_THROTTLE_MS) return
        fastAt[key] = now
        if (fastAt.size > 32) fastAt.clear()
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
        val loader = hostLoader(classLoader)
        hook += mountLifecycle(api, loader, CLASS_SHADE_ROOT, "窗口根")
        hook += mountAodInvalidate(api, loader)
        installed = hook > 0
        val stale = sweepStaleOnInstall()
        Lg.i(TAG, "stage-install hook=$hook/3 installed=$installed gate=${refreshGate()} stale=$stale")
    }

    /**
     * 宿主 loader 解析：入参加载不到窗口根就退到进程 Application loader。
     *
     * 热重载线程的 contextClassLoader 可能是模块自己的，用它加载宿主类必然 CNFE。
     */
    private fun hostLoader(fallback: ClassLoader): ClassLoader {
        if (canLoad(fallback, CLASS_SHADE_ROOT)) return fallback
        val host = runCatching { Lg.processContext()?.classLoader }.getOrNull()
        if (host != null && canLoad(host, CLASS_SHADE_ROOT)) {
            Lg.i(TAG, "stage-loader 入参不可用，改用宿主 Application loader")
            return host
        }
        logThrottled("stage-loader-miss", "两种 loader 都加载不到窗口根类")
        return fallback
    }

    private fun canLoad(loader: ClassLoader, name: String): Boolean =
        runCatching { Class.forName(name, false, loader) }.isSuccess

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
            layerStage[existing] = stage
            logThrottled("stage-reuse", "复用已挂层 attach=$attachHits")
            armFrameLoop(existing)
            resumeSystemEdge(existing)
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
        layerStage[layer] = stage
        Lg.i(TAG, "stage-mounted host=${host.javaClass.simpleName} stage=${stage.javaClass.simpleName} size=${stage.width}x${stage.height}")
        armFrameLoop(layer)
        resumeSystemEdge(layer)
    }

    /** 层刚挂上时若系统边已在跑，补武装帧回调（建边事件早于挂层会漏掉）。 */
    private fun resumeSystemEdge(layer: AodTransitionLayer) {
        val edge = AodHandoffHook.currentEdgeOrNull() ?: return
        if (!refreshGate()) return
        startChoreographer(layer)
        logThrottled("stage-sysedge-resume", "挂层补武装系统边 edge=$edge")
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
        gyroCached = null
        lastEffectGateKey = ""
        lastEffectInputKey = ""
        lastSpinGateKeys.clear()
        clockTails.clear()
        fastDoneEdge = null
        clockDoneEdge = null
        playEdge = null
        playEdgeMs = 0L
        lensSpinDeg = 0f
        lensSpinAtMs = 0L
        settingsCache = null
        settingsReadAt = 0L
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

    /**
     * 入边补间求值：只在刚进边那一帧起一段，与预览侧同一套 160ms 曲线。
     *
     * 世界层底与效果层取景都吃这个缩放，不补的话按锁屏键第一帧整屏跳 10%。
     */
    private fun enterZoomOf(edge: TransitionEdge, t: Float, sampled: Float): Float {
        if (enterZoomEdge != edge) {
            enterZoomEdge = edge
            // 起点必须是该边的起点态，不能取「上一次见过的静止值」：
            // 设备在 LOCK 停过就会把 1.1 一直挂着，连两端都是 1.0 的边也被补成 1.1→1.0
            SystemSceneMotion.sampleStatic(edge.from, enterFromLayers)
            val from = enterFromLayers.wallpaperScale
            if (t < 0.02f && from > 0f && kotlin.math.abs(from - sampled) > 1e-3f) {
                enterZoomFrom = from
                enterZoomTo = sampled
                enterZoomStartMs = SystemClock.uptimeMillis()
                logThrottled("stage-enter-zoom", "入边补间起 from=$from to=$sampled edge=$edge")
            } else {
                enterZoomFrom = 0f
            }
        }
        if (enterZoomFrom <= 0f) return sampled
        val elapsed = SystemClock.uptimeMillis() - enterZoomStartMs
        if (WallpaperZoomSettle.done(elapsed)) {
            enterZoomFrom = 0f
            return sampled
        }
        return WallpaperZoomSettle.zoomAt(enterZoomFrom, enterZoomTo, elapsed)
    }

    private val choreoCallbacks = WeakHashMap<AodTransitionLayer, Choreographer.FrameCallback>()

    /** 层 → 挂载舞台：转场中途宿主会把子视图全摘掉，靠这份账本把层挂回去。 */
    private val layerStage = WeakHashMap<AodTransitionLayer, ViewGroup>()

    /** 内部时间轴需要自己续帧：宿主静止时不会重绘，只靠 OnPreDraw 会停在中途。 */
    private fun startChoreographer(layer: AodTransitionLayer) {
        if (choreoCallbacks.containsKey(layer)) {
            logThrottled("stage-choreo-rearm", "帧回调本就在位，不重复武装 att=${layer.isAttachedToWindow}")
            return
        }
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

    /**
     * 宿主切换三态时会把舞台子视图整体摘掉，自绘层随之 detach（转场期内 onDraw 不再执行）。
     * 每帧只在「已 detach 且舞台仍上屏」时挂回一次，静止态由调用方保证不会走到这里。
     */
    private fun reattachIfDetached(layer: AodTransitionLayer) {
        if (layer.isAttachedToWindow) return
        val stage = layerStage[layer] ?: run {
            logThrottled("stage-reattach-nostage", "层已脱离且无舞台记录，无法挂回")
            return
        }
        if (!stage.isAttachedToWindow) {
            logThrottled(
                "stage-reattach-stagegone",
                "层已脱离且舞台已下屏 stage=${stage.javaClass.simpleName}",
            )
            return
        }
        if (layer.parent != null) return
        stage.addView(
            layer,
            0,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        logThrottled("stage-reattach", "层被宿主摘掉，已挂回 stage=${stage.javaClass.simpleName}")
    }

    /** 返回本帧是否仍在播放（供帧回调决定是否续帧）。 */
    private fun pump(layer: AodTransitionLayer): Boolean {
        if (!refreshGate()) {
            logThrottled("stage-pump-gate", "帧泵空转：门控关闭，本帧直接退出")
            layer.bind(null, null, playing = false)
            layer.bindEffect(null, 0f)
            layer.bindWorld(null, SceneState.AOD, null, 0f, 0f, 0f, false, 0f)
            releaseContentLayers(layer)
            clockTails.remove(layer)
            return false
        }
        reattachIfDetached(layer)
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
        val cs = bridge.clock.state()
        logFast(
            "stage-pump-tick",
            "edge=$edge t=${"%.3f".format(params.t)} ct=${"%.3f".format(cs.t)} " +
                "src=${cs.source} playing=${cs.playing} active=${params.active} " +
                "surface=$pumpSurface att=${layer.isAttachedToWindow}",
        )
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
        SystemSceneMotion.sample(edge, params.t, sceneLayers, edgeMs = stageEdgeMs(edge))
        sceneLayers.wallpaperScale = enterZoomOf(edge, params.t, sceneLayers.wallpaperScale)
        layer.bind(params, sceneLayers, playing = true)
        applyWorld(layer, staticState(layer), edge, params.t, sceneLayers.aodAlpha)
        applyClockAnchor(layer, edge, params.t, sceneLayers.aodClockScale)
        applyEffect(
            layer,
            EffectOverlayPolicy.visibilityAt(staticState(layer), edge, params.t),
            sceneLayers.wallpaperScale,
        )
        applyContentLayers(layer, params, sceneLayers, edge)
        applyPills(layer)
        applyWidgets(layer)
        val edgeDone = params.t >= 1f
        if (edgeDone) noteClockTail(layer, edge)
        if (clockTails.containsKey(layer) || enterZoomFrom > 0f) startChoreographer(layer)
        logThrottled(
            "stage-frame",
            "edge=$edge t=${"%.3f".format(params.t)} mask=${params.maskEnabled} byFrame=${params.maskByFrame} r=${"%.0f".format(params.maskRadius)} dim=${"%.2f".format(sceneLayers.dim)} clock=${"%.3f".format(sceneLayers.aodClockScale)}",
        )
        val pose = DesktopClockAnchorBridge.machine.pose
        val fastLine = "edge=$edge t=${"%.3f".format(params.t)} dim=${"%.3f".format(sceneLayers.dim)} " +
            "wp=${"%.3f".format(sceneLayers.wallpaperScale)} aod=${"%.3f".format(sceneLayers.aodAlpha)} " +
            "cx=${"%.4f".format(pose.centerX)} cy=${"%.4f".format(pose.centerY)} " +
            "s=${"%.4f".format(pose.scale)} a=${"%.3f".format(pose.alpha)} " +
            "maskR=${"%.0f".format(params.maskRadius)} " +
            "att=${layer.isAttachedToWindow} par=${layer.parent?.javaClass?.simpleName ?: "null"} " +
            "lk=${"%.3f".format(sceneLayers.lockAlpha)}"
        // 边内按 120ms 采；落位那一帧不受节流，否则端点会被吞掉、逐帧判据判不了终点
        if (params.t < 1f) {
            // 边还在跑 ⇒ 上次落位账本作废，否则同一条边第二次播放的末帧会被吞
            fastDoneEdge = null
            logFast("stage-frame-fast", fastLine)
        } else if (fastDoneEdge != edge) {
            fastDoneEdge = edge
            Lg.i(TAG, "stage-frame-fast: $fastLine")
        }
        return !edgeDone || clockTailPending(layer)
    }

    /** 静止态：按当前舞台可见性取静态分层，壁纸效果满强度，内容层交回系统。 */
    private fun applyStatic(layer: AodTransitionLayer) {
        val state = staticState(layer)
        SystemSceneMotion.sampleStatic(state, sceneLayers)
        enterZoomEdge = null
        enterZoomFrom = 0f
        layer.bind(null, sceneLayers, playing = false)
        applyWorld(layer, state, null, 0f, sceneLayers.aodAlpha)
        applyEffect(layer, EffectOverlayPolicy.visibilityOf(state), sceneLayers.wallpaperScale)
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
        val stage = layer.parent as? ViewGroup ?: run {
            logThrottled("stage-restage-static", "父容器非 ViewGroup，本帧不落位时钟 state=$state")
            return
        }
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
        DesktopClockAnchorBridge.publishHandoff(null, 0f, state)
        val anchorState = if (surface == EditSurface.AOD) SceneState.AOD else SceneState.LOCK
        val anchor = machine.anchorOf(anchorState)
        ClockAnchorApply.apply(
            clock, anchorState,
            withHandPaintFade(withDrift(anchor, surface), anchorState, null), "static",
        )
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

    /**
     * 息屏静止态是否需要逐帧：世界层在位且该样式的动参数非零才武装。
     *
     * 手绘完整重放不受动态总闸限制（它是内容本身而非装饰动效），播完即停。
     */
    private fun stillWorldAnimating(layer: AodTransitionLayer): Boolean {
        val spec = worldSpec() ?: return false
        if (AodWorldLayer.handPaintPlaying()) return true
        val e = displayEntry()
        if (!e.allowDynamic) return false
        val s = settingsCached() ?: return false
        val animating = AodSpinGate.stillAnimating(
            e.allowDynamic, spec.lens,
            s.param(EffectParams.AOD_ROTATE_SPEED),
            s.param(EffectParams.AOD_COLOR_MIX),
            s.param(EffectParams.AOD_PHOTO_TILT),
        )
        logSpinGate("still", spec.lens, SceneState.AOD, null, animating)
        return animating
    }

    /** 自转门诊断：按结论判重，门关时也要能分辨是哪一项挡的。 */
    private fun logSpinGate(
        phase: String,
        isLens: Boolean,
        state: SceneState,
        edge: TransitionEdge?,
        on: Boolean,
    ) {
        val key = "$isLens|$state|$on"
        val now = SystemClock.uptimeMillis()
        // 同帧内静止重推与播放路径交替给出不同判定，只按键判重挡不住，再加一道时间闸
        if (lastSpinGateKeys[phase] == key && now - lastSpinGateAt < SPIN_GATE_LOG_MS) return
        lastSpinGateKeys[phase] = key
        lastSpinGateAt = now
        Lg.i(TAG, "stage-spin-gate phase=$phase lens=$isLens state=$state edge=$edge on=$on")
    }

    /** 按 phase 分别记上次判定：同帧内 play/still 交替、edge 在 null 与真实边之间跳动，键里带它们会每帧都判「变了」。 */
    private val lastSpinGateKeys = HashMap<String, String>()
    private var lastSpinGateAt = 0L

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
    private fun applyContentLayers(
        layer: AodTransitionLayer,
        params: TransitionParams,
        layers: SystemSceneMotion.Layers? = null,
        edge: TransitionEdge? = null,
    ) {
        val hasWorld = worldSpec() != null
        val useLayers = layers != null && hasWorld
        when (ContentMotionSource.choose(layers != null, hasWorld)) {
            ContentMotionSource.Source.LAYERS ->
                ContentMotionSource.fill(layers!!, edge, contentGateNow(edge, params.t), contentPoses)
            ContentMotionSource.Source.PARAMS -> ContentLayerMapper.map(params, contentPoses)
        }
        logContentSource(useLayers, edge)
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
        if (!keepClocks) {
            restoreClocks(layer)
            DesktopClockAnchorBridge.releaseHandoff("teardown")
        }
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
        if (id == 0) {
            logThrottled("stage-lockroot-noid", "解析不到 keyguard_root_view 的 id，LOCK 内容层不施加")
            return null
        }
        val root = layer.rootView ?: return null
        val found = root.findViewById<View>(id)
        if (found == null) {
            logThrottled("stage-lockroot-miss", "树里没有 keyguard_root_view，LOCK 内容层不施加")
            return null
        }
        val kids = (found as? ViewGroup)?.childCount ?: -1
        logThrottled(
            "stage-lockroot",
            "lockRoot=${found.javaClass.simpleName} children=$kids size=${found.width}x${found.height}",
        )
        return found
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
     * 输入取世界层同一张底图（四路策略），纹理压在用户看到的那张图上。
     */
    private fun applyEffect(layer: AodTransitionLayer, visibility: Float, zoom: Float) {
        val slot = effectiveEffects()
        val id = slot.id
        val special = slot.specialId
        if (!EffectOverlayPolicy.needsBuild(id, special)) {
            layer.bindEffect(null, 0f)
            return
        }
        val src = backdropSource()
        val onBackdropReady = { layer.postInvalidate() }
        AodWorldLayer.ensureWallpaper(onBackdropReady)
        AodWorldLayer.ensureStylePhoto(src.stylePhotoUri, onBackdropReady)
        AodWorldLayer.ensureSceneSky(onBackdropReady)
        val wall = AodWorldLayer.backdropOrNull(src.useSystemWallpaper)
        logEffectInput(id, special, src.useSystemWallpaper, wall)
        layer.bindEffect(id, visibility, special, slot.intensity, wall, zoom)
        if (layer.width <= 0 || layer.height <= 0) return
        AodEffectOverlay.request(id, special, slot.intensity, wall, layer.width, layer.height) {
            layer.postInvalidate()
        }
    }

    /**
     * 效果层输入裁决：按结论判重，换底图或底图缺失各打一条。
     *
     * 无底图时构建侧直接拒（三类需底图的效果回 null），没有这条则「效果没生效」定不了位。
     */
    private fun logEffectInput(
        id: String?,
        special: String?,
        useSystemWallpaper: Boolean,
        wall: android.graphics.Bitmap?,
    ) {
        val key = "$id|$special|$useSystemWallpaper|${System.identityHashCode(wall)}"
        if (key == lastEffectInputKey) return
        lastEffectInputKey = key
        if (wall == null) {
            Lg.i(
                TAG,
                "stage-effect-input id=$id sp=$special none " +
                    AodWorldLayer.backdropMissReason(useSystemWallpaper),
            )
            return
        }
        Lg.i(
            TAG,
            "stage-effect-input id=$id sp=$special src=${wall.width}x${wall.height} " +
                "sys=$useSystemWallpaper from=${AodWorldLayer.backdropSourceTag(useSystemWallpaper)}",
        )
    }

    private data class BackdropEntry(
        val useSystemWallpaper: Boolean,
        val stylePhotoUri: String,
        val framePhotoUri: String,
        val framePair: Boolean,
        val cropX: Float,
        val cropY: Float,
        val cropSize: Float,
        val cropAngle: Float,
        val readAtMs: Long,
    )

    @Volatile private var backdropCache: BackdropEntry? = null

    private val DEFAULT_BACKDROP = BackdropEntry(
        Prefs.DEFAULT_ALIVE_AOD_WALLPAPER, "", "", true, 0.5f, 0.5f, 1f, 0f, 0L,
    )

    /**
     * 底图源与取景配置 TTL 惰性读取：接管开关 / 用户照片 URI / 相框照片与取景四滑杆。
     *
     * 读失败保持上一份；解码由 URI 变化事件驱动，本函数只读键不排任务。
     */
    private fun backdropSource(): BackdropEntry {
        val now = SystemClock.uptimeMillis()
        val cached = backdropCache
        if (cached != null && KeyedTtlCache.fresh(cached.readAtMs, now, EFFECT_TTL_MS)) return cached
        val a = api ?: return cached ?: DEFAULT_BACKDROP
        val next = try {
            val prefs = Prefs.read(a)
            val s = EffectConfigStore.read(a)
            BackdropEntry(
                prefs.getBoolean(Prefs.KEY_ALIVE_AOD_WALLPAPER, Prefs.DEFAULT_ALIVE_AOD_WALLPAPER),
                s.aodPhoto, s.aodFramePhoto, s.aodFramePair,
                s.param(EffectParams.AOD_FRAME_X) / 100f,
                s.param(EffectParams.AOD_FRAME_Y) / 100f,
                s.param(EffectParams.AOD_FRAME_SIZE) / 100f,
                s.param(EffectParams.AOD_FRAME_ANGLE),
                now,
            )
        } catch (t: Throwable) {
            logThrottled("stage-backdrop-read", "底图源配置读失败 ${t.javaClass.simpleName}")
            cached?.copy(readAtMs = now) ?: DEFAULT_BACKDROP.copy(readAtMs = now)
        }
        if (cached == null ||
            cached.useSystemWallpaper != next.useSystemWallpaper ||
            cached.stylePhotoUri != next.stylePhotoUri ||
            cached.framePhotoUri != next.framePhotoUri ||
            cached.framePair != next.framePair
        ) {
            Lg.i(
                TAG,
                "stage-backdrop sys=${next.useSystemWallpaper} photo=${next.stylePhotoUri.isNotEmpty()} " +
                    "frame=${next.framePhotoUri.isNotEmpty()} pair=${next.framePair}",
            )
        }
        backdropCache = next
        return next
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
        if (spec != null) {
            AodWorldLayer.ensureWallpaper { anyLayer()?.postInvalidate() }
            val src = backdropSource()
            AodWorldLayer.ensureStylePhoto(src.stylePhotoUri) { anyLayer()?.postInvalidate() }
            if (spec.photo != null) {
                AodWorldLayer.ensureFramePhoto(src.framePhotoUri) { anyLayer()?.postInvalidate() }
            }
            AodWorldLayer.ensureSceneSky { anyLayer()?.postInvalidate() }
        }
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
        val spinOn = AodSpinGate.active(dynamic, spec.lens, state, edge)
        logSpinGate("play", spec.lens, state, edge, spinOn)
        val spin = lensSpin(motion, dynamic, spec.lens, state, edge)
        val mix = motion.colorMix
        val tilt = if (still) motion.photoTiltDeg else 0f
        val alpha = if (spec.lens) lensAlpha(edge, t, aodAlpha) else 1f
        val src = backdropSource()
        layer.bindWorld(
            spec, state, edge, t, alpha, spin, dynamic, mix,
            tiltDeg = tilt,
            wallpaperScale = sceneLayers.wallpaperScale,
            accent = AodStyleCatalog.find(styleId()).accent,
            api = api,
            useSystemWallpaper = src.useSystemWallpaper,
            framePair = src.framePair && styleId() == AodStyleCatalog.ID_LEAVE_LIGHT,
            cropX = src.cropX,
            cropY = src.cropY,
            cropSize = src.cropSize,
            cropAngle = src.cropAngle,
        )
    }

    /**
     * 镜片自转角：与预览侧同一符号约定（负方向），按门控推进的累计角。
     *
     * 门控走共用判定；不该转的态只冻结不清零，否则每次落位都会跳角。
     * 不攒帧：增量按两次调用的真实间隔算，与调用节奏无关，也不需帧回调兜底。
     */
    private fun lensSpin(
        motion: AodMotionState,
        allowDynamic: Boolean,
        isLens: Boolean,
        state: SceneState,
        edge: TransitionEdge?,
    ): Float {
        val now = SystemClock.uptimeMillis()
        val last = lensSpinAtMs
        lensSpinAtMs = now
        if (!motion.active) return lensSpinDeg
        if (!AodSpinGate.active(allowDynamic, isLens, state, edge)) return lensSpinDeg
        if (last == 0L) return lensSpinDeg
        val advanced = SailLensMotion.spin(
            lensSpinDeg,
            (now - last) / 1000f,
            aodSpinSpeed(),
        )
        lensSpinDeg = if (advanced <= -360f) -((-advanced) % 360f) else advanced
        return lensSpinDeg
    }

    private var lensSpinDeg = 0f
    private var lensSpinAtMs = 0L

    /** 自转速度倍率：滑杆值除以引擎基准角速度，与预览侧 speed 语义一致。 */
    private fun aodSpinSpeed(): Float {
        val s = settingsCached() ?: return 1f
        val degPerSec = s.param(EffectParams.AOD_ROTATE_SPEED)
        return (degPerSec / SailLensMotion.SPIN_DEG_PER_SEC).coerceIn(0f, 2f)
    }

    /** 镜片息屏层透明度：播放期走镜片自身交叠曲线，静止态用系统分层值。 */
    private fun lensAlpha(edge: TransitionEdge?, t: Float, staticAlpha: Float): Float {
        if (edge == null) return staticAlpha
        SailLensMotion.fade(edge, t, worldFade)
        return worldFade.aodAlpha
    }

    private val worldFade = SailLensMotion.Fade()

    /** 内容闸：只有镜片款在交叠期压内容，其余款式恒 1（与预览侧同一份真值）。 */
    private fun contentGateNow(edge: TransitionEdge?, t: Float): Float {
        val spec = worldSpec() ?: return 1f
        if (!spec.lens) return 1f
        SailLensMotion.fade(edge, t, worldFade)
        return worldFade.contentGate
    }

    /** 内容动效来源诊断：分层真值 vs 求值器兜底，附三层实际写入值。 */
    private fun logContentSource(hasLayers: Boolean, edge: TransitionEdge?) {
        logThrottled(
            "stage-content-src",
            "src=${if (hasLayers) "layers" else "params"} edge=$edge " +
                "aod=${"%.3f".format(contentPoses.aod.alpha)} " +
                "lock=${"%.3f".format(contentPoses.lock.alpha)}/${"%.3f".format(contentPoses.lock.scale)} " +
                "dy=${"%.1f".format(contentPoses.lock.dy)} " +
                "home=${"%.3f".format(contentPoses.home.alpha)}/${"%.3f".format(contentPoses.home.scale)}",
        )
    }

    /** 息屏自转角与混色一次求值：只走引擎 AodMotion，与预览侧同一份真值。 */
    private fun motionNow(allowDynamic: Boolean): AodMotionState {
        worldMotion.reset()
        val s = settingsCached() ?: return worldMotion
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

    /** 生效的壁纸效果 / 特殊效果 / 强度；强度归一化到 0..1 供渲染侧直接用。 */
    private class EffectSlot(val id: String?, val specialId: String?, val intensity: Float)

    /**
     * 动态参数 TTL 惰性读取：自转、混色、倾斜与帧链判据共用一份。
     *
     * 逐帧路径原先各读各的（每帧要解析一次配置串），共用后每 TTL 至多一次；读失败保持上一份。
     */
    private fun settingsCached(): com.coloros16.liquidglass.engine.effect.EffectSettings? {
        val now = SystemClock.uptimeMillis()
        val cached = settingsCache
        if (cached != null && KeyedTtlCache.fresh(settingsReadAt, now, EFFECT_TTL_MS)) return cached
        val a = api ?: return cached
        val next = runCatching { EffectConfigStore.read(a) }.getOrNull() ?: return cached
        settingsCache = next
        settingsReadAt = now
        return next
    }

    @Volatile private var settingsCache: com.coloros16.liquidglass.engine.effect.EffectSettings? = null
    @Volatile private var settingsReadAt = 0L

    /** 效果配置 TTL 惰性读取；解析失败或被门槛拦住一律回 null，读失败保持上一份。 */
    private fun effectiveEffects(): EffectSlot {
        val now = SystemClock.uptimeMillis()
        if (effectReadAt != 0L && now - effectReadAt < EFFECT_TTL_MS) return effectSlot
        effectReadAt = now
        val a = api ?: return effectSlot
        effectSlot = try {
            val settings = EffectConfigStore.read(a)
            val caps = EffectCapabilities(
                hasGyro = gyroAvailable(),
                panoramaAodActive = panoramaAodActiveOf(styleId()),
                photoSceneAvailable = false,
            )
            val resolved = EffectResolver.resolve(settings, caps)
            val wp = resolved.lockWallpaper
            val sp = resolved.lockSpecial
            logEffectGate(caps, wp.reason, sp.reason, settings)
            EffectSlot(
                if (wp.applied && !wp.noop) wp.effectiveId else null,
                if (sp.applied && !sp.noop) sp.effectiveId else null,
                (settings.param(EffectParams.LOCK_WP_INTENSITY) / 100f).coerceIn(0f, 1f),
            )
        } catch (t: Throwable) {
            logThrottled("stage-effect-read", "效果配置读失败 ${t.javaClass.simpleName}")
            effectSlot
        }
        return effectSlot
    }

    /**
     * 门槛裁决日志：能力真值 + 两槽被拦原因，只在结论变化时打一条。
     *
     * 门槛回退是静默的（回 null 后渲染侧根本不请求），无日志则「补了渲染仍不生效」定不了位。
     */
    private fun logEffectGate(
        caps: EffectCapabilities,
        wpReason: com.coloros16.liquidglass.engine.effect.EffectBlockReason,
        spReason: com.coloros16.liquidglass.engine.effect.EffectBlockReason,
        settings: com.coloros16.liquidglass.engine.effect.EffectSettings,
    ) {
        val key = "$wpReason|$spReason|${caps.hasGyro}|${caps.panoramaAodActive}"
        if (key == lastEffectGateKey) return
        lastEffectGateKey = key
        Lg.i(
            TAG,
            "stage-effect-gate gyro=${caps.hasGyro} panorama=${caps.panoramaAodActive} " +
                "style=${styleId()} wp=${settings.lockWallpaperEffect}/$wpReason " +
                "sp=${settings.lockSpecialEffect}/$spReason",
        )
    }

    /**
     * 陀螺仪能力：进程内探一次即缓存（硬件能力不会在运行期变化）。
     *
     * 取不到 Context 时不写缓存，下次调用再试；无定时器无轮询。
     */
    private fun gyroAvailable(): Boolean {
        gyroCached?.let { return it }
        val ctx = Lg.processContext() ?: run {
            logThrottled("stage-gyro-noctx", "进程 Context 为空，陀螺仪能力待定")
            return false
        }
        val has = try {
            val sensors = ctx.getSystemService(android.hardware.SensorManager::class.java)
            val present = sensors?.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE) != null
            Lg.i(TAG, "stage-gyro present=$present")
            present
        } catch (t: Throwable) {
            logThrottled("stage-gyro-err", "陀螺仪探测失败 ${t.javaClass.simpleName}: ${t.message}")
            return false
        }
        gyroCached = has
        return has
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
        playEdge = edge
        playEdgeMs = duration
        if (bridge.ensurePlaying(edge, duration)) {
            Lg.i(
                TAG,
                "stage-play edge=$edge duration=$duration delay=${TransitionStartDelay.forEdge(edge)} " +
                    "style=${styleId()} att=${layer.isAttachedToWindow} " +
                    "par=${layer.parent?.javaClass?.simpleName ?: "null"}",
            )
            startChoreographer(layer)
        } else {
            logThrottled("stage-play-skip", "已在播放或系统泵接管 edge=$edge")
        }
    }

    /**
     * 系统只发状态不给帧的边：由内部时间轴补播，时长取样式/系统真值。
     *
     * 走与边沿 hook 同一条起播入口，门控与去重都由它统一判。
     */
    fun playSystemEdge(edge: TransitionEdge) {
        val layer = anyLayer() ?: run {
            logThrottled("stage-selftime-nolayer", "自绘层未挂，内部时间轴不接管 edge=$edge")
            return
        }
        playEdge(layer, edge)
    }

    /**
     * 系统转场建边事件：按边武装/撤帧回调，让系统逐帧进度能被消费。
     *
     * 只在边变化时调一次（事件驱动，非轮询）；边两端门控由帧泵自己判。
     */
    fun noteSystemEdge(edge: TransitionEdge?) {
        val layer = anyLayer() ?: run {
            logThrottled("stage-sysedge-nolayer", "自绘层未挂，系统边不武装 edge=$edge")
            return
        }
        if (edge == null) {
            stopChoreographer(layer)
            return
        }
        if (!refreshGate()) {
            logThrottled("stage-sysedge-gate", "门控关闭，系统边不武装 edge=$edge")
            return
        }
        startChoreographer(layer)
        logThrottled("stage-sysedge", "系统边武装帧回调 edge=$edge")
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
    /** 本次自播边的实际时长；系统泵驱动的边没有自播记录，回引擎表。 */
    private fun stageEdgeMs(edge: TransitionEdge): Long =
        if (edge == playEdge && playEdgeMs > 0L) playEdgeMs else SystemSceneMotion.durationOf(edge)

    private fun edgeDurationMs(edge: TransitionEdge): Long {
        val home = AodHandoffHook.homeTransitionMs(edge)
        if (home > 0L) {
            logThrottled("stage-duration-home", "桌面过渡选项接管时长 edge=$edge ms=$home")
            return home
        }
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
        val stage = layer.parent as? ViewGroup ?: run {
            logThrottled("stage-restage", "父容器非 ViewGroup，本帧不驱动时钟 edge=$edge")
            return
        }
        val machine = DesktopClockAnchorBridge.machine
        syncClockAnchors(machine)
        DesktopClockAnchorBridge.publishHandoff(edge, t, staticState(layer))
        machine.onTransition(edge, t, SystemSceneMotion.clockCurve(edge))
        val pose = machine.pose
        val fromAnchor = machine.anchorOf(edge.from)
        val toAnchor = machine.anchorOf(edge.to)
        val anchorLine = "edge=$edge t=${"%.3f".format(t)} " +
            "from=${edge.from}(${"%.3f".format(fromAnchor.centerX)},${"%.3f".format(fromAnchor.centerY)},a${"%.2f".format(fromAnchor.alpha)}) " +
            "to=${edge.to}(${"%.3f".format(toAnchor.centerX)},${"%.3f".format(toAnchor.centerY)},a${"%.2f".format(toAnchor.alpha)}) " +
            "pose=(${"%.3f".format(pose.centerX)},${"%.3f".format(pose.centerY)},a${"%.2f".format(pose.alpha)}) " +
            "curve=${clockCurveName(edge)}"
        // 边内按 120ms 采；落位那一帧不受节流，端点才不会被吞
        if (t < 1f) {
            // 边还在跑 ⇒ 上次落位账本作废，否则同一条边第二次播放的末帧会被吞
            clockDoneEdge = null
            logFast("stage-clock-anchors", anchorLine)
        } else if (clockDoneEdge != edge) {
            clockDoneEdge = edge
            Lg.i(TAG, "stage-clock-anchors: $anchorLine")
        }
        val surface = surfaceOf(layer)
        val host = clockHostOf(surface)
        val clock = clockViewOf(stage, surface) ?: run {
            logThrottled(
                "stage-noclock",
                "未找到时钟视图 surface=$surface edge=$edge ${clockMissDiag(stage, surface)}",
            )
            return
        }
        val entry = AodClockEntry.entryFor(
            surface == EditSurface.AOD, systemScale, AodClockEntry.weightOf(edge, t, SceneState.AOD),
        )
        val handoffAlpha = handoffAlphaOf(edge, t)
        val base = ClockAnchor(
            pose.centerX, pose.centerY, pose.scale * entry, pose.alpha * handoffAlpha, pose.rotationDeg,
            pose.stretchX, pose.stretchY,
        )
        val state = clockStateOf(surface, edge)
        if (state != host) {
            logThrottled(
                "stage-clock-target",
                "时钟目标态随边走 host=$host state=$state edge=$edge",
            )
        }
        val faded = withLensClockFade(
            withHandPaintFade(withDrift(base, surface), state, edge), surface, edge, t,
        )
        ClockAnchorApply.apply(clock, state, faded, "stage")
        logThrottled(
            "stage-clock-curve",
            "edge=$edge t=${"%.3f".format(t)} curve=${clockCurveName(edge)} " +
                "cx=${"%.3f".format(base.centerX)} cy=${"%.3f".format(base.centerY)} " +
                "scale=${"%.3f".format(base.scale)} a=${"%.3f".format(faded.alpha)}",
        )
    }

    /**
     * 交接期系统那颗钟的可见度：推到桌面坐标的同时淡出，桌面小部件同相淡入。
     *
     * 让位档（宿主自带灵感时钟平移）与没有桌面小部件时恒 1，保持既有行为不变。
     */
    private fun handoffAlphaOf(edge: TransitionEdge, t: Float): Float {
        if (!DesktopClockAnchorBridge.moduleTranslates()) return 1f
        if (!DesktopClockAnchorBridge.desktopClockVisible()) return 1f
        if (!ClockHandoffPolicy.handsOff(edge)) return 1f
        return ClockHandoffPolicy.alphaOf(
            ClockHandoffPolicy.Side.SYSTEM_CLOCK, ClockHandoffPolicy.progress(edge, t),
        )
    }

    /** 时钟宿主面：决定写哪个视图与哪本记账。 */
    private fun clockHostOf(surface: EditSurface): SceneState =
        if (surface == EditSurface.AOD) SceneState.AOD else SceneState.LOCK

    /** 时钟目标态：宿主面参与本次转场则按宿主，否则按边的终点（第三态由另一面的视图承载）。 */
    private fun clockStateOf(surface: EditSurface, edge: TransitionEdge): SceneState {
        val host = clockHostOf(surface)
        return if (edge.from == host || edge.to == host) host else edge.to
    }

    /** 时钟位移曲线名：解锁两条边跟随壁纸缩放，其余跟随蒙版。 */
    private fun clockCurveName(edge: TransitionEdge): String =
        if (SystemSceneMotion.clockCurve(edge) === SystemSceneMotion.WALLPAPER) "wallpaper" else "mask"

    private data class AnchorKey(
        val x: Float,
        val y: Float,
        val scale: Float,
        val stretchX: Float,
        val stretchY: Float,
        val baseX: Float,
        val baseY: Float,
        val baseScale: Float,
    )

    private val anchorKeys = HashMap<SceneState, AnchorKey>()

    /** 无样式锚点的标记值（不参与数值比较，只判两侧是否都有锚点）。 */
    private const val NO_BASE = -1f

    /** 锚点键比对：样式基线任一侧缺失时只看有无，避免 NaN 让相等判断恒假。 */
    private fun sameKey(a: AnchorKey?, b: AnchorKey): Boolean {
        if (a == null) return false
        if (a.x != b.x || a.y != b.y || a.scale != b.scale) return false
        if (a.stretchX != b.stretchX || a.stretchY != b.stretchY) return false
        val aHas = a.baseX != NO_BASE
        val bHas = b.baseX != NO_BASE
        if (aHas != bHas) return false
        if (!aHas) return true
        return a.baseX == b.baseX && a.baseY == b.baseY && a.baseScale == b.baseScale
    }

    /**
     * 把编辑配置灌成状态机两端锚点，让转场期位置与大小真插值。
     *
     * 锚点存静止终值（含用户缩放），故转场链不再叠编辑覆盖，避免位置被覆盖、缩放被乘两次。
     * 值未变则不写，避免每帧触发状态机跟随补间。
     */
    private fun syncClockAnchors(machine: ClockStateMachine) {
        val spec = worldSpec()
        for (state in CLOCK_ANCHOR_STATES) {
            val surface = if (state == SceneState.AOD) EditSurface.AOD else EditSurface.LOCK
            val layout = layoutFor(surface)
            val base = spec?.clockAnchors?.get(state)
            val key = AnchorKey(
                layout.clockX, layout.clockY, layout.clockScale,
                layout.clockStretchX, layout.clockStretchY,
                base?.centerX ?: NO_BASE, base?.centerY ?: NO_BASE,
                base?.scale ?: NO_BASE,
            )
            if (sameKey(anchorKeys[state], key)) continue
            anchorKeys[state] = key
            val anchor = ClockAnchorCompose.compose(base, layout)
            machine.setAnchor(state, anchor, SystemClock.uptimeMillis(), follow = false)
            logThrottled(
                "stage-anchor-sync",
                "state=$state x=${"%.3f".format(anchor.centerX)} y=${"%.3f".format(anchor.centerY)} " +
                    "scale=${"%.3f".format(anchor.scale)} " +
                    "sx=${"%.3f".format(anchor.stretchX)} sy=${"%.3f".format(anchor.stretchY)} " +
                    "style=${if (base != null) styleId() else "-"}",
            )
        }
        syncHomeAnchor(machine)
    }

    /**
     * 桌面端锚点：真机矩形优先（桌面大时钟 > 状态栏时钟），两路皆无才用预览兜底终点。
     *
     * 桌面锚点由桌面桥按事件写入，故这里逐帧只读一次、值未变不写；
     * 桌面态没有编辑面，不叠编辑配置也不叠样式基线。
     */
    private fun syncHomeAnchor(machine: ClockStateMachine) {
        val real = DesktopClockAnchorBridge.anchorOrNull()
        val anchor = real ?: ClockAnchorMapper.HOME_FALLBACK
        val key = HomeKey(anchor.centerX, anchor.centerY, anchor.scale, anchor.alpha)
        val prev = homeAnchorKey
        if (prev != null &&
            prev.x == key.x && prev.y == key.y && prev.scale == key.scale && prev.alpha == key.alpha
        ) return
        homeAnchorKey = key
        machine.setAnchor(SceneState.HOME, anchor, SystemClock.uptimeMillis(), follow = false)
        logThrottled(
            "stage-anchor-home",
            "x=${"%.3f".format(anchor.centerX)} y=${"%.3f".format(anchor.centerY)} " +
                "scale=${"%.3f".format(anchor.scale)} a=${"%.3f".format(anchor.alpha)} " +
                "src=${if (real != null) "device" else "fallback"}",
        )
    }

    private class ClockTail(val edge: TransitionEdge, val startMs: Long, val edgeMs: Long)

    private val clockTails = WeakHashMap<AodTransitionLayer, ClockTail>()

    /** 边结束时登记补尾起点；只有终点为息屏且时钟时长超出边长才需要补。 */
    private fun noteClockTail(layer: AodTransitionLayer, edge: TransitionEdge) {
        if (!AodClockEntry.entering(edge)) {
            clockTails.remove(layer)
            return
        }
        // 补尾起点必须接求值器实际用的边长（stageEdgeMs），否则系统泵边会在边末跳变
        val edgeMs = stageEdgeMs(edge)
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
        val stage = layer.parent as? ViewGroup ?: run {
            logThrottled("stage-restage-tail", "父容器非 ViewGroup，本帧不补尾缩放")
            return
        }
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
    /**
     * 手绘款时钟延迟淡入：笔画重放到官方延迟后才出时钟，其余款式恒原值。
     *
     * 只改 alpha 不动位置与缩放，故不影响落位对账。
     */
    private fun withHandPaintFade(
        anchor: ClockAnchor,
        state: SceneState,
        edge: TransitionEdge?,
    ): ClockAnchor {
        val spec = worldSpec() ?: return anchor
        if (spec.handPaintStyle <= 0) return anchor
        val fade = AodWorldLayer.handPaintClockFade(state, edge)
        if (fade >= 1f) return anchor
        return anchor.copy(alpha = anchor.alpha * fade)
    }

    /**
     * 镜片款息屏时钟淡入淡出：与息屏层同一条交叠曲线，只改 alpha。
     *
     * 只作用息屏面的时钟；锁屏面时钟由内容层通道负责，不叠这一层。
     */
    private fun withLensClockFade(
        anchor: ClockAnchor,
        surface: EditSurface,
        edge: TransitionEdge?,
        t: Float,
    ): ClockAnchor {
        if (surface != EditSurface.AOD || edge == null) return anchor
        val spec = worldSpec() ?: return anchor
        if (!spec.lens) return anchor
        SailLensMotion.fade(edge, t, worldFade)
        val a = worldFade.aodClockAlpha
        if (a >= 1f) return anchor
        return anchor.copy(alpha = anchor.alpha * a)
    }

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
