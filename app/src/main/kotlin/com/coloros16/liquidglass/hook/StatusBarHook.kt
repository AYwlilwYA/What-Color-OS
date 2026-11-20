package com.coloros16.liquidglass.hook

import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.hook.statusbar.StatusBarCompat
import com.coloros16.liquidglass.hook.statusbar.StatusBarConfig
import com.coloros16.liquidglass.hook.statusbar.StatusBarDuoHost
import com.coloros16.liquidglass.hook.statusbar.StatusBarEnforcer
import com.coloros16.liquidglass.hook.statusbar.StatusBarSettings
import com.coloros16.liquidglass.log.Lg
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 状态栏 hook 线唯一入口。
 *
 * 装四观察点 + 两道事件闸，行为全部落在 [StatusBarEnforcer]：
 * - CollapsedStatusBarFragment.onViewCreated → 状态栏根视图到手（通知图标/边距）
 * - StatusIconContainer.onLayout → 系统每次摆位后幂等纠正（复合视图内部元素显隐）
 * - StatusIconContainer.onViewAdded → 新图标进来时合并补一次
 * - StatusIconContainer.updateStates → 排位前把 GONE 子清零（藏图标不留大空白）
 * - View.setVisibility → 三合一开启期间 battery 翻回 VISIBLE 同栈拦回 GONE（防闪现）
 *
 * 全链 fail-open：任一 hook 装不上只降级对应功能；回调内任何异常只打节流日志。
 * 日志 tag = `LgStatusBar`；计数 key（sb-hit-*）不带过滤条件，用于确认 hook 真被调用。
 */
object StatusBarHook {

    private const val TAG = "LgStatusBar"
    private const val THROTTLE_MS = 2000L

    private val throttleAt = ConcurrentHashMap<String, Long>()
    private val viewCreatedHits = AtomicLong()
    private val layoutHits = AtomicLong()
    private val addedHits = AtomicLong()
    private val applyErrors = AtomicLong()
    private val pendingApply = AtomicBoolean(false)

    private var api: XposedInterface? = null

    @Volatile
    private var installed = false

    @Volatile
    private var lastEnabled = false

    /** 完整门禁探测排到主线程（避开注入/热重载回调线程的类初始化锁）。 */
    private fun scheduleFullGate(cl: ClassLoader) {
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    val host = StatusBarCompat.hostLoader(cl)
                    val g = StatusBarCompat.gate(host, allowProbe = true)
                    writeInstallReceipt("sb-gate-deferred ok ${g.summary()}")
                    api?.let { retryCarrierOnHostLoader(it, cl) }
                } catch (t: Throwable) {
                    Lg.i(TAG, "sb-gate-deferred 失败 ${t.javaClass.simpleName}")
                    writeInstallReceipt("sb-gate-deferred FAIL ${t.javaClass.simpleName}")
                }
            }
        } catch (t: Throwable) {
            Lg.i(TAG, "sb-gate-deferred 排程失败 ${t.javaClass.simpleName}")
        }
    }

    /** 装载：各 hook 独立 try，一个失败其它照装。 */
    fun install(api: XposedInterface, classLoader: ClassLoader) {
        if (installed) {
            logThrottled("sb-install-dup", "sb-install 已装载，跳过")
            return
        }
        this.api = api
        // 热重载回调线程内取 PM / 触发类初始化会 futex 卡死整条安装链：
        // 关键路径只用框架入参 loader + 零探测定档，宿主 loader 解析与完整 gate 挪到主线程补。
        val cl = StatusBarCompat.cachedLoaderOr(classLoader)
        val gates = StatusBarCompat.gate(cl, allowProbe = false)
        scheduleFullGate(classLoader)
        val profile = gates.structure.profile
        var hook = 0
        if (profile.hasFragmentEntry) {
            hook += hookViewCreated(api, cl)
        } else {
            logThrottled("sb-install-no-fragment", "sb-install 跳过 viewCreated（档位无 Fragment 入口，根视图走上溯）")
        }
        hook += hookContainerLayout(api, cl)
        hook += hookContainerAdded(api, cl)
        hook += hookDarkDispatcher(api, cl)
        hook += hookUpdateStatesGoneClear(api, cl)
        if (hook > 0) hook += hookBatteryVis(api, cl)
        val carrier = hookCarrierText(api, cl)
        val footer = hookQsFooter(api, cl)
        installed = hook > 0 || carrier > 0 || footer > 0
        try {
            StatusBarEnforcer.attachOrderPrefs(Prefs.read(api))
        } catch (t: Throwable) {
            Lg.i(TAG, "sb-install 顺序回传通道装载失败 ${t.javaClass.simpleName}")
        }
        val cfg = StatusBarSettings.snapshot(api)
        lastEnabled = cfg.enabled
        val summary = "sb-install hook=$hook/6 carrier=$carrier/2 footer=$footer/1 installed=$installed gate=${cfg.enabled} any=${cfg.anyFeature} " +
            "fragmentEntry=${profile.hasFragmentEntry} clockSec=${profile.hasClockSeconds} " +
            "gates=${gates.summary()}"
        Lg.i(TAG, summary)
        writeInstallReceipt(summary)
    }

    /**
     * 安装回执落盘：logcat 有单进程配额（LOG_FLOWCTRL 300 条/秒），安装期整批被丢导致
     * 「汇总行零命中」无法区分「没跑」与「跑了但日志丢」。回执写模块私有目录，不受配额影响。
     */
    private fun writeInstallReceipt(summary: String) {
        val pid = android.os.Process.myPid()
        val ts = System.currentTimeMillis()
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    // 宿主进程内 processContext 可能为 null（实测回执长期缺失），
                    // 回退到宿主 dataDir 固定路径，保证判据一定落盘。
                    val dir = Lg.processContext()?.filesDir
                        ?: java.io.File("/data/data/${StatusBarCompat.PKG_SYSTEMUI}/files").apply { mkdirs() }
                    val f = java.io.File(dir, "sb-install-receipt.txt")
                    f.appendText("$ts pid=$pid $summary\n")
                    if (f.length() > 32_768L) {
                        f.writeText(f.readLines().takeLast(40).joinToString("\n") + "\n")
                    }
                    logThrottled("sb-receipt-path", "sb-receipt path=${f.absolutePath}")
                } catch (t: Throwable) {
                    Lg.i(TAG, "sb-receipt write failed ${t.javaClass.simpleName}: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            Lg.i(TAG, "sb-receipt post failed ${t.javaClass.simpleName}")
        }
    }

    private fun hookViewCreated(api: XposedInterface, cl: ClassLoader): Int = try {
        val cls = Class.forName(StatusBarCompat.FQCN_FRAGMENT, false, cl)
        val method = cls.getDeclaredMethod("onViewCreated", View::class.java, Bundle::class.java)
        api.hook(method)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                viewCreatedHits.incrementAndGet()
                try {
                    val root = chain.getArg(0) as? View
                    if (root != null) {
                        // 根视图到手即证宿主类可加载，用它自己的 loader 补装运营商 hook
                        if (!carrierInstalled.get()) {
                            hookCarrierText(api, root.javaClass.classLoader ?: cl)
                        }
                        withConfig { cfg -> StatusBarEnforcer.onStatusBarView(root, cfg) }
                    }
                } catch (t: Throwable) {
                    applyErrors.incrementAndGet()
                    logThrottled("sb-viewcreated-err", "sb-skip viewCreated ${t.javaClass.simpleName}")
                }
                report("sb-hit-viewcreated", viewCreatedHits.get())
                result
            }
        1
    } catch (t: Throwable) {
        Lg.e(TAG, "sb-install onViewCreated 失败 ${t.javaClass.simpleName}: ${t.message}", t)
        0
    }

    /**
     * 运营商文字替换：两代宿主各挂一处，任一装上即生效，都装不上只降级本功能。
     * 新世代 setTextWithAnima 入口改入参；旧世代在回调内改字段后由系统自己 setText。
     */
    private fun hookCarrierText(api: XposedInterface, cl: ClassLoader): Int {
        if (carrierInstalled.get()) return carrierCount.get()
        var ok = 0
        ok += hookCarrierAnim(api, cl)
        ok += hookCarrierLegacy(api, cl)
        if (ok > 0) {
            carrierInstalled.set(true)
            carrierCount.set(ok)
        } else {
            logThrottled("sb-carrier-none", "sb-carrier 两代宿主均未命中，等宿主 loader 就绪后重试")
        }
        return ok
    }

    private val carrierInstalled = AtomicBoolean(false)
    private val carrierCount = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * 宿主 loader 就绪后补装运营商 hook：安装线程只有框架入参 loader（实测在 C15 加载不到
     * SystemUI 主 dex），完整 gate 在主线程解析出宿主 loader 后由该事件驱动补一次。
     */
    private fun retryCarrierOnHostLoader(api: XposedInterface, fallback: ClassLoader) {
        if (carrierInstalled.get()) return
        val host = StatusBarCompat.cachedLoaderOr(fallback)
        val n = hookCarrierText(api, host)
        writeInstallReceipt("sb-carrier-deferred n=$n/2 loader=${host.javaClass.name}")
    }

    /** 新世代：AnimateChangeTextView.setTextWithAnima(CharSequence, float)，只改运营商实例。 */
    private fun hookCarrierAnim(api: XposedInterface, cl: ClassLoader): Int = try {
        val cls = Class.forName(StatusBarCompat.FQCN_CARRIER_ANIM, false, cl)
        val second = try {
            Class.forName(StatusBarCompat.FQCN_CARRIER_SECOND, false, cl)
        } catch (_: Throwable) {
            null
        }
        val method = cls.getDeclaredMethod(
            "setTextWithAnima", CharSequence::class.java, Float::class.javaPrimitiveType,
        )
        api.hook(method)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                try {
                    val self = chain.getThisObject()
                    // 基类方法体内会强转子类，非运营商实例一律放行不动
                    if (self != null && (second == null || second.isInstance(self))) {
                        val raw = chain.getArg(0) as? CharSequence
                        val cfg = StatusBarSettings.snapshot(api)
                        val repl = if (cfg.enabled) cfg.carrierReplacement else ""
                        if (repl.isNotEmpty() && raw?.toString() != repl) {
                            val args = chain.getArgs().toTypedArray()
                            args[0] = repl
                            logThrottled("sb-carrier-apply", "sb-carrier apply gen=anim len=${repl.length}")
                            return@intercept chain.proceed(args)
                        }
                    }
                } catch (t: Throwable) {
                    applyErrors.incrementAndGet()
                    logThrottled("sb-carrier-anim-err", "sb-skip carrier anim ${t.javaClass.simpleName}")
                }
                chain.proceed()
            }
        Lg.i(TAG, "sb-carrier gen=anim hooked second=${second != null}")
        1
    } catch (t: Throwable) {
        Lg.i(TAG, "sb-carrier gen=anim 未命中 ${t.javaClass.simpleName}: ${t.message}")
        0
    }

    /**
     * QS 页脚布局观察点：合并式面板的大时钟挂在 OplusQSFooterImpl 下的
     * settings_button_container，与状态栏图标容器不同树，需独立入口。
     */
    private fun hookQsFooter(api: XposedInterface, cl: ClassLoader): Int = try {
        val cls = Class.forName(StatusBarCompat.FQCN_QS_FOOTER, false, cl)
        // 该类继承 FrameLayout 但未声明 onLayout，getDeclaredMethod 取不到父类方法；
        // onFinishInflate 是本类声明的，子 View 此时已齐备。
        val method = cls.getDeclaredMethod("onFinishInflate")
        api.hook(method)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                try {
                    val footer = chain.getThisObject() as? View
                    if (footer != null) {
                        footer.post {
                            withConfig { cfg -> StatusBarEnforcer.onQsFooterLayout(footer, cfg) }
                        }
                    }
                } catch (t: Throwable) {
                    applyErrors.incrementAndGet()
                    logThrottled("sb-qsfooter-inflate-err", "sb-skip qsfooter inflate ${t.javaClass.simpleName}")
                }
                result
            }
        Lg.i(TAG, "sb-qsfooter hooked onFinishInflate")
        1
    } catch (t: Throwable) {
        Lg.i(TAG, "sb-qsfooter 未命中 ${t.javaClass.simpleName}: ${t.message}")
        0
    }

    /** 旧世代：OplusQSCarrierText$1.updateCarrierInfo，改回调信息里的 carrierText 字段。 */
    private fun hookCarrierLegacy(api: XposedInterface, cl: ClassLoader): Int = try {
        val owner = Class.forName(StatusBarCompat.FQCN_CARRIER_LEGACY, false, cl)
        val infoCls = Class.forName(
            StatusBarCompat.FQCN_CARRIER_CONTROLLER + "\$OplusCarrierTextCallbackInfo", false, cl,
        )
        val field = infoCls.getDeclaredField("carrierText").apply { isAccessible = true }
        var hooked = 0
        // ART 不保留 InnerClasses 属性，declaredClasses 恒空（实测 inners=0）⇒ 按名逐个试
        val candidates = ArrayList<Class<*>>()
        for (i in 1..6) {
            try {
                candidates.add(Class.forName("${StatusBarCompat.FQCN_CARRIER_LEGACY}\$$i", false, cl))
            } catch (_: Throwable) {
            }
        }
        Lg.i(TAG, "sb-carrier gen=legacy owner=${owner.name} candidates=${candidates.size}")
        for (inner in candidates) {
            val m = try {
                inner.getDeclaredMethod("updateCarrierInfo", infoCls)
            } catch (t: Throwable) {
                Lg.i(TAG, "sb-carrier gen=legacy ${inner.name} 无 updateCarrierInfo ${t.javaClass.simpleName}")
                continue
            }
            try {
                api.hook(m)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        try {
                            val cfg = StatusBarSettings.snapshot(api)
                            val repl = if (cfg.enabled) cfg.carrierReplacement else ""
                            val info = chain.getArg(0)
                            if (repl.isNotEmpty() && info != null && field.get(info)?.toString() != repl) {
                                field.set(info, repl)
                                logThrottled("sb-carrier-apply", "sb-carrier apply gen=legacy len=${repl.length}")
                            }
                        } catch (t: Throwable) {
                            applyErrors.incrementAndGet()
                            logThrottled("sb-carrier-legacy-err", "sb-skip carrier legacy ${t.javaClass.simpleName}")
                        }
                        chain.proceed()
                    }
                hooked++
            } catch (t: Throwable) {
                Lg.i(TAG, "sb-carrier gen=legacy hook 失败 ${t.javaClass.simpleName}")
            }
        }
        Lg.i(TAG, "sb-carrier gen=legacy hooked n=$hooked")
        if (hooked > 0) 1 else 0
    } catch (t: Throwable) {
        Lg.i(TAG, "sb-carrier gen=legacy 未命中 ${t.javaClass.simpleName}: ${t.message}")
        0
    }

    private fun hookContainerLayout(api: XposedInterface, cl: ClassLoader): Int = try {
        val cls = Class.forName(StatusBarCompat.FQCN_ICON_CONTAINER, false, cl)
        val method = cls.getDeclaredMethod(
            "onLayout",
            Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        api.hook(method)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                layoutHits.incrementAndGet()
                try {
                    val container = chain.getThisObject() as? View
                    if (container is ViewGroup) StatusBarEnforcer.publishOrder(container)
                    if (container != null) {
                        withConfig { cfg -> StatusBarEnforcer.onContainerLayout(container, cfg) }
                    }
                } catch (t: Throwable) {
                    applyErrors.incrementAndGet()
                    logThrottled("sb-layout-err", "sb-skip layout ${t.javaClass.simpleName}")
                }
                report("sb-hit-layout", layoutHits.get())
                result
            }
        1
    } catch (t: Throwable) {
        Lg.e(TAG, "sb-install onLayout 失败 ${t.javaClass.simpleName}: ${t.message}", t)
        0
    }

    private fun hookContainerAdded(api: XposedInterface, cl: ClassLoader): Int = try {
        val cls = Class.forName(StatusBarCompat.FQCN_ICON_CONTAINER, false, cl)
        val method = cls.getDeclaredMethod("onViewAdded", View::class.java)
        api.hook(method)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                addedHits.incrementAndGet()
                try {
                    val container = chain.getThisObject() as? View
                    if (container is ViewGroup) scheduleApply(container)
                } catch (t: Throwable) {
                    applyErrors.incrementAndGet()
                    logThrottled("sb-added-err", "sb-skip viewAdded ${t.javaClass.simpleName}")
                }
                report("sb-hit-added", addedHits.get())
                result
            }
        1
    } catch (t: Throwable) {
        Lg.e(TAG, "sb-install onViewAdded 失败 ${t.javaClass.simpleName}: ${t.message}", t)
        0
    }

    /** 捕获暗色分发实例：三合一自绘视图据此注册 tint 接收器。 */
    private fun hookDarkDispatcher(api: XposedInterface, cl: ClassLoader): Int = try {
        val cls = Class.forName(StatusBarCompat.FQCN_DARK_DISPATCHER, false, cl)
        val ctors = cls.declaredConstructors
        if (ctors.isEmpty()) {
            0
        } else {
            var ok = 0
            for (ctor in ctors) {
                try {
                    ctor.isAccessible = true
                    api.hook(ctor)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            val result = chain.proceed()
                            try {
                                chain.getThisObject()?.let { StatusBarDuoHost.onDispatcher(it) }
                            } catch (t: Throwable) {
                                applyErrors.incrementAndGet()
                                logThrottled("sb-dispatcher-err", "sb-skip dispatcher ${t.javaClass.simpleName}")
                            }
                            result
                        }
                    ok++
                } catch (t: Throwable) {
                    Lg.e(TAG, "sb-install dispatcher ctor 失败 ${t.javaClass.simpleName}: ${t.message}", t)
                }
            }
            if (ok > 0) 1 else 0
        }
    } catch (t: Throwable) {
        Lg.e(TAG, "sb-install darkDispatcher 失败 ${t.javaClass.simpleName}: ${t.message}", t)
        0
    }

    /** 空白闸：排位扣宽前把 GONE 子的测量/布局尺寸清零，藏掉的图标不再白占位。 */
    private fun hookUpdateStatesGoneClear(api: XposedInterface, cl: ClassLoader): Int = try {
        val cls = Class.forName(StatusBarCompat.FQCN_ICON_CONTAINER, false, cl)
        val method = cls.getDeclaredMethod("updateStates")
        api.hook(method)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                try {
                    clearGoneSlots(chain.getThisObject() as? ViewGroup)
                } catch (t: Throwable) {
                    applyErrors.incrementAndGet()
                    logThrottled("sb-gap-err", "sb-skip gapClear ${t.javaClass.simpleName}")
                }
                chain.proceed()
            }
        1
    } catch (t: Throwable) {
        Lg.e(TAG, "sb-install updateStates 失败 ${t.javaClass.simpleName}: ${t.message}", t)
        0
    }

    /** 电池闪现闸：三合一期间系统把 battery 翻回 VISIBLE 时同栈压回 GONE，不隔帧。 */
    private fun hookBatteryVis(api: XposedInterface, cl: ClassLoader): Int = try {
        val method = View::class.java.getDeclaredMethod("setVisibility", Int::class.javaPrimitiveType)
        api.hook(method)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val block = try {
                    (chain.getArg(0) as? Int) == View.VISIBLE && isDuoBattery(chain.getThisObject() as? View)
                } catch (t: Throwable) {
                    applyErrors.incrementAndGet()
                    logThrottled("sb-battvis-err", "sb-skip battVis ${t.javaClass.simpleName}")
                    false
                }
                val result = chain.proceed()
                if (block) {
                    try {
                        val v = chain.getThisObject() as? View
                        if (v != null) StatusBarEnforcer.noteVisibilityOrig(v, View.VISIBLE)
                        v?.visibility = View.GONE
                        logThrottled("sb-block-batt", "sb-block battery VISIBLE 回压 GONE")
                    } catch (t: Throwable) {
                        applyErrors.incrementAndGet()
                        logThrottled("sb-battvis-err2", "sb-skip battPress ${t.javaClass.simpleName}")
                    }
                }
                result
            }
        1
    } catch (t: Throwable) {
        Lg.e(TAG, "sb-install setVisibility 失败 ${t.javaClass.simpleName}: ${t.message}", t)
        0
    }

    private fun clearGoneSlots(container: ViewGroup?) {
        if (container == null || !StatusBarDuoHost.isMounted() || !duoEnabledNow()) return
        var cleared = 0
        val spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.EXACTLY)
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i) ?: continue
            if (child.visibility != View.GONE) continue
            if (child.width == 0 && child.height == 0 && child.measuredWidth == 0) continue
            child.measure(spec, spec)
            child.layout(0, 0, 0, 0)
            cleared++
        }
        if (cleared > 0) {
            logThrottled("sb-gap-clear", "sb-gap-clear n=$cleared")
            container.requestLayout()
        }
    }

    private fun isDuoBattery(v: View?): Boolean {
        if (v == null || !StatusBarDuoHost.isMounted() || !duoEnabledNow()) return false
        if (!v.javaClass.name.contains("BatteryMeter")) return false
        val idMain = StatusBarCompat.id("battery")
        val idQuickQs = StatusBarCompat.id("batteryRemainingIcon")
        return (idMain != 0 && v.id == idMain) || (idQuickQs != 0 && v.id == idQuickQs)
    }

    private fun duoEnabledNow(): Boolean = try {
        StatusBarSettings.snapshot(api).duoEnable
    } catch (_: Throwable) {
        false
    }

    /** 新子 View 到达时合并成一次异步应用（避免在布局过程中改结构/可见性）。 */
    private fun scheduleApply(container: View) {
        if (!pendingApply.compareAndSet(false, true)) return
        try {
            container.post {
                pendingApply.set(false)
                val target = container
                withConfig { cfg -> StatusBarEnforcer.onChildAdded(target, cfg) }
            }
        } catch (t: Throwable) {
            pendingApply.set(false)
            logThrottled("sb-post-err", "sb-skip post ${t.javaClass.simpleName}")
        }
    }

    /**
     * 统一门控：每次回调都按 500ms TTL 重读配置；关闸时只还原一次。
     * 读键失败保持上次快照（fail-open 不误关用户功能）。
     */
    private fun withConfig(block: (StatusBarConfig) -> Unit) {
        val cfg = StatusBarSettings.snapshot(api)
        if (!cfg.enabled) {
            if (lastEnabled) {
                lastEnabled = false
                StatusBarEnforcer.restoreAll()
            }
            return
        }
        lastEnabled = true
        try {
            block(cfg)
        } catch (t: Throwable) {
            applyErrors.incrementAndGet()
            logThrottled("sb-apply-err", "sb-skip apply ${t.javaClass.simpleName}")
        }
    }

    private fun report(key: String, count: Long) {
        logThrottled(
            key,
            "$key total=$count err=${applyErrors.get()} " +
                "layout=${layoutHits.get()} added=${addedHits.get()} applied=${StatusBarEnforcer.isApplied()}",
        )
    }

    /** 节流诊断日志：同 key 2s 一条；key 回显进打印文本（只进节流表按 key grep 恒 0）。 */
    private fun logThrottled(key: String, msg: String) {
        val now = SystemClock.uptimeMillis()
        val last = throttleAt[key]
        if (last != null && now - last < THROTTLE_MS) return
        throttleAt[key] = now
        if (throttleAt.size > 256) throttleAt.clear()
        Lg.i(TAG, msg)
    }
}
