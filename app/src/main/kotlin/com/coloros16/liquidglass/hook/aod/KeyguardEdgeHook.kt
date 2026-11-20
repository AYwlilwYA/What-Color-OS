package com.coloros16.liquidglass.hook.aod

import android.os.Bundle
import android.os.SystemClock
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.engine.EdgeDebounce
import com.coloros16.liquidglass.engine.SceneState
import com.coloros16.liquidglass.hook.AodHandoffHook
import com.coloros16.liquidglass.engine.TransitionEdge
import com.coloros16.liquidglass.hook.MethodSignaturePicker
import com.coloros16.liquidglass.log.Lg
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.util.concurrent.ConcurrentHashMap

/**
 * 锁屏↔桌面边沿事件：系统这两条边没有统一进度泵，用锁屏显隐与退场动画当触发点。
 *
 * 触发后交 [AodStageHook.playEdge] 起内部时间轴，由帧回调推进（无轮询）。
 * 方法签名以设备 dexdump 为准，全链 fail-open。
 */
object KeyguardEdgeHook {

    private const val TAG = "LgKgdEdge"
    private const val CLASS_MEDIATOR = "com.android.systemui.keyguard.KeyguardViewMediator"
    private const val METHOD_HIDE = "handleHide"
    private const val METHOD_SHOW = "handleShowInner"
    private const val METHOD_SHOW_NEST = "-\$\$Nest\$mhandleShow"
    private const val METHOD_EXIT_ANIM = "handleStartKeyguardExitAnimation"
    private const val GATE_TTL_MS = 500L
    private const val THROTTLE_MS = 2000L
    private const val SHOW_DEBOUNCE_MS = 400L

    private val throttleAt = ConcurrentHashMap<String, Long>()

    @Volatile private var installed = false
    @Volatile private var gateEnabled = false
    @Volatile private var gateReadAt = 0L
    @Volatile private var hideHits = 0
    @Volatile private var showHits = 0
    @Volatile private var exitHits = 0
    @Volatile private var lastHideAt = 0L

    private var api: XposedInterface? = null

    private fun logThrottled(key: String, msg: String) {
        val now = SystemClock.uptimeMillis()
        val last = throttleAt[key]
        if (last != null && now - last < THROTTLE_MS) return
        throttleAt[key] = now
        if (throttleAt.size > 64) throttleAt.clear()
        Lg.i(TAG, "$key: $msg")
    }

    fun install(api: XposedInterface, classLoader: ClassLoader) {
        if (installed) {
            logThrottled("kgd-dup", "已装载 hide=$hideHits show=$showHits exit=$exitHits")
            return
        }
        this.api = api
        refreshGate()
        val cls = try {
            Class.forName(CLASS_MEDIATOR, false, classLoader)
        } catch (t: Throwable) {
            Lg.i(TAG, "kgd-class-miss ${t.javaClass.simpleName}: ${t.message}")
            return
        }
        var hook = 0
        if (mount(api, cls, METHOD_HIDE, emptyArray()) { onLockToHome() }) hook++
        val showMounted = mountShow(api, cls)
        if (showMounted) hook++ else Lg.e(TAG, "kgd-install show 边沿两种取法都失败，HOME→LOCK 不可用")
        if (mountExitAnim(api, cls)) hook++
        installed = hook > 0
        Lg.i(TAG, "kgd-install hook=$hook/3 installed=$installed show=$showMounted gate=${refreshGate()}")
    }

    private fun mount(
        api: XposedInterface,
        cls: Class<*>,
        name: String,
        params: Array<Class<*>>,
        action: () -> Unit,
    ): Boolean {
        val m = try {
            cls.getDeclaredMethod(name, *params)
        } catch (t: Throwable) {
            Lg.i(TAG, "kgd-method-miss $name ${t.javaClass.simpleName}")
            return false
        }
        return try {
            api.hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val result = chain.proceed()
                try {
                    action()
                } catch (t: Throwable) {
                    Lg.e(TAG, "kgd-callback-err $name ${t.javaClass.simpleName}: ${t.message}", t)
                }
                result
            }
            Lg.i(TAG, "kgd-mount $CLASS_MEDIATOR#$name")
            true
        } catch (t: Throwable) {
            Lg.e(TAG, "kgd-mount-fail $name ${t.javaClass.simpleName}: ${t.message}", t)
            false
        }
    }

    /**
     * 上屏边沿按档取法：实例方法 `handleShowInner(Bundle)`，或编译器内联后的静态 nest 桥。
     *
     * nest 桥首参是宿主实例、次参才是 Bundle，回调不取参数故两档通用。
     */
    private fun mountShow(api: XposedInterface, cls: Class<*>): Boolean {
        val candidates = listOf(
            MethodSignaturePicker.Candidate(METHOD_SHOW, arrayOf(Bundle::class.java)),
            MethodSignaturePicker.Candidate(METHOD_SHOW_NEST, arrayOf(cls, Bundle::class.java)),
        )
        val hit = MethodSignaturePicker.pickNamed(candidates) { name, params ->
            try {
                cls.getDeclaredMethod(name, *params)
            } catch (t: Throwable) {
                logThrottled("kgd-show-sig-$name", "$name 取不到 ${t.javaClass.simpleName}")
                null
            }
        }
        if (hit == null) return false
        Lg.i(TAG, "kgd-show-sig 命中 ${hit.name}（${hit.params.size} 参）")
        return mount(api, cls, hit.name, hit.params) { onHomeToLock() }
    }

    /** 退场动画签名按设备 dexdump：(JJ[RemoteAnimationTarget×3;IRemoteAnimationFinishedCallback;String)V。 */
    private fun mountExitAnim(api: XposedInterface, cls: Class<*>): Boolean {
        val target = try {
            Class.forName("android.view.RemoteAnimationTarget", false, cls.classLoader)
        } catch (t: Throwable) {
            Lg.i(TAG, "kgd-exit-class-miss ${t.javaClass.simpleName}")
            return false
        }
        val finished = try {
            Class.forName("android.view.IRemoteAnimationFinishedCallback", false, cls.classLoader)
        } catch (t: Throwable) {
            Lg.i(TAG, "kgd-exit-cb-miss ${t.javaClass.simpleName}")
            return false
        }
        val arrayType = java.lang.reflect.Array.newInstance(target, 0).javaClass
        val m = try {
            cls.getDeclaredMethod(
                METHOD_EXIT_ANIM,
                Long::class.javaPrimitiveType!!,
                Long::class.javaPrimitiveType!!,
                arrayType, arrayType, arrayType,
                finished,
                String::class.java,
            )
        } catch (t: Throwable) {
            Lg.i(TAG, "kgd-exit-method-miss ${t.javaClass.simpleName}: ${t.message}")
            return false
        }
        return try {
            api.hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val result = chain.proceed()
                try {
                    onExitAnim()
                } catch (t: Throwable) {
                    Lg.e(TAG, "kgd-exit-err ${t.javaClass.simpleName}: ${t.message}", t)
                }
                result
            }
            Lg.i(TAG, "kgd-mount $CLASS_MEDIATOR#$METHOD_EXIT_ANIM")
            true
        } catch (t: Throwable) {
            Lg.e(TAG, "kgd-exit-mount-fail ${t.javaClass.simpleName}: ${t.message}", t)
            false
        }
    }

    private fun onLockToHome() {
        hideHits = EdgeDebounce.bump(hideHits)
        noteUnlock()
        if (!refreshGate()) {
            logThrottled("kgd-gate", "门控关闭 hide=$hideHits")
            return
        }
        fire(TransitionEdge.LOCK_TO_HOME, EdgeDebounce.sourceTag(hideHits, exitHits, fromExit = false))
    }

    /** 退场动画与 handleHide 是同一次解锁的两个触发点，各自计数但共用去抖时刻。 */
    private fun onExitAnim() {
        exitHits = EdgeDebounce.bump(exitHits)
        noteUnlock()
        if (!refreshGate()) {
            logThrottled("kgd-gate", "门控关闭 exit=$exitHits")
            return
        }
        fire(TransitionEdge.LOCK_TO_HOME, EdgeDebounce.sourceTag(hideHits, exitHits, fromExit = true))
    }

    private fun noteUnlock() {
        lastHideAt = SystemClock.uptimeMillis()
        AodStageHook.noteInteraction()
    }

    private fun onHomeToLock() {
        showHits = EdgeDebounce.bump(showHits)
        val now = SystemClock.uptimeMillis()
        if (EdgeDebounce.suppress(now, lastHideAt, SHOW_DEBOUNCE_MS)) {
            logThrottled("kgd-show-debounce", "紧跟退场的 show 视为同一次解锁 show=$showHits")
            return
        }
        if (!refreshGate()) {
            logThrottled("kgd-gate", "门控关闭 show=$showHits")
            return
        }
        fire(TransitionEdge.HOME_TO_LOCK, "show=$showHits")
    }

    private fun fire(edge: TransitionEdge, reason: String) {
        when (edge) {
            TransitionEdge.HOME_TO_LOCK -> AodHandoffHook.noteScene(SceneState.LOCK)
            TransitionEdge.LOCK_TO_HOME -> AodHandoffHook.noteScene(SceneState.HOME)
            else -> Unit
        }
        val layer = AodStageHook.anyLayer()
        if (layer == null) {
            logThrottled("kgd-nolayer", "自绘层未挂，边沿丢弃 edge=$edge")
            return
        }
        Lg.i(TAG, "kgd-fire edge=$edge $reason")
        AodStageHook.playEdge(layer, edge)
    }

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
            logThrottled("kgd-gate-read", "读键失败 ${t.javaClass.simpleName} 保持 $prev")
            prev
        }
        if (prev != gateEnabled) Lg.i(TAG, "kgd-gate $prev -> $gateEnabled")
        return gateEnabled
    }
}
