package com.coloros16.liquidglass.sf

import android.os.SystemClock
import android.view.View
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.log.Lg
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * SF 玻璃通道客户端：渲染模式读取、几何直调下发（[SfBlurCall] 进程内直调模糊通道）。
 * 互斥语义：模式 sf **且通道可用** = SF 独占——绘制点认领后本帧交 SF、四腿全关；
 * 下发失败 = 无模块玻璃（系统原厂绘制）+ 节流日志。通道不可用（系统模块未装 / 未生效）
 * = 降级回现役四腿，不让用户只剩透明背板。模式 app = 现役四腿，本通道零参与。
 */
object SfGlassChannel {

    private const val TAG = "SfGlassChannel"

    /** 日志落主标签：模块标签过滤的抓取（只收 LiquidGlass）也能拿到通道诊断。 */
    private const val LOG_TAG = "LiquidGlass"
    private const val MODE_TTL_MS = 500L
    private const val OFFER_MIN_INTERVAL_MS = 250L
    private const val SESSION_IDLE_OFF_MS = 5000L
    private const val WATCHDOG_TICK_MS = 2000L
    private const val SU_TIMEOUT_MS = 8000L
    private const val CONF_PATH = "/system_ext/lib64/oplusex/lg_glass.conf"

    /** 系统模块目录与开机背书文件（判通道是否真的装了并生效）。 */
    private const val MOD_DIR = "/data/adb/modules/lg_sf_channel"
    private const val STATE_FILE = "$MOD_DIR/state"
    private const val MODULE_PROP = "$MOD_DIR/module.prop"
    private const val LOADER_IN_TREE = "$MOD_DIR/system/system_ext/lib64/oplusex/libdisplayppex.so"

    /** 通道可用性复检间隔（只由渲染调用触发，无绘制即无探测，不起定时器）。 */
    private const val READY_OK_TTL_MS = 60_000L
    private const val READY_FAIL_TTL_MS = 15_000L

    /** su 执行结果：ok=exit 0，out=合并输出，err=失败原因（ok 时 null）。 */
    internal data class SuResult(val ok: Boolean, val out: String, val err: String?) {
        val summary: String get() = out.trim().take(400).ifBlank { "(无输出)" }
    }

    private class Geom(
        val id: Int,
        val l: Float,
        val t: Float,
        val w: Float,
        val h: Float,
        val corner: Float,
        val host: View?,
        val originX: Float,
        val originY: Float,
    )

    @Volatile
    private var modeCache: String = Prefs.DEFAULT_RENDER_MODE

    @Volatile
    private var modeCacheAtMs = 0L

    @Volatile
    private var readyCache = false

    @Volatile
    private var readyAtMs = 0L

    @Volatile
    private var readyReason = "unprobed"

    private val readyProbing = AtomicBoolean(false)

    @Volatile
    private var lastOfferKey = ""

    @Volatile
    private var lastOfferAttemptAtMs = 0L

    @Volatile
    private var lastClaimAtMs = 0L

    @Volatile
    private var sessionOn = false

    private val pendingOffer = AtomicReference<Geom?>(null)
    private val offerScheduled = AtomicBoolean(false)

    @Volatile
    private var apiRef: XposedInterface? = null

    @Volatile
    private var lastHostSc: Any? = null

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "sf-glass-io").apply { isDaemon = true }
    }

    private val watchdog = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "sf-glass-watchdog").apply { isDaemon = true }
    }

    init {
        watchdog.scheduleWithFixedDelay(
            { idleOffTick() },
            WATCHDOG_TICK_MS, WATCHDOG_TICK_MS, TimeUnit.MILLISECONDS,
        )
    }

    // ---------- 渲染模式 ----------

    /**
     * 渲染模式（500ms TTL 热读）：`app`=现役四腿 / `sf`=SF 下发链，变化打 `lg-sf-mode`；
     * sf→app 翻转顺手撤申报（防 SF 层残留）。
     */
    fun renderMode(api: XposedInterface?): String {
        if (api == null) return Prefs.DEFAULT_RENDER_MODE
        apiRef = api
        val now = SystemClock.uptimeMillis()
        if (now - modeCacheAtMs < MODE_TTL_MS) return effectiveMode(modeCache)
        modeCacheAtMs = now
        val v = try {
            Prefs.read(api).getString(Prefs.KEY_RENDER_MODE, Prefs.DEFAULT_RENDER_MODE)
                ?: Prefs.DEFAULT_RENDER_MODE
        } catch (t: Throwable) {
            Lg.w(LOG_TAG, "SfGlassChannel render_mode read failed, default ${Prefs.DEFAULT_RENDER_MODE}", t)
            Prefs.DEFAULT_RENDER_MODE
        }
        if (v != modeCache) {
            val prev = modeCache
            modeCache = v
            Lg.i(LOG_TAG, "SfGlassChannel lg-sf-mode mode=$v prev=$prev")
            if (v == Prefs.RENDER_MODE_APP && prev == Prefs.RENDER_MODE_SF) {
                sessionGlassOff("mode-switch-app")
            }
        }
        return effectiveMode(v)
    }

    /** sf 档但系统模块不在位/未生效 → 按 app 处理（四腿照常），不留透明背板。 */
    private fun effectiveMode(raw: String): String {
        if (raw != Prefs.RENDER_MODE_SF) return raw
        if (channelReady()) return raw
        throttled(
            "lg-sf-fallback",
            "sf 档不可用（$readyReason）→ 按应用级渲染；装好系统模块并重启后自动生效",
        )
        return Prefs.RENDER_MODE_APP
    }

    // ---------- 通道可用性（系统模块在位 + 开机背书） ----------

    /**
     * SF 通道是否真的可用：热路径只读缓存，过期时投一次异步探测（不阻塞绘制、不轮询）。
     * 首次未探完一律 false —— 宁可先走四腿，也不让画面空着。
     */
    fun channelReady(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (readyAtMs != 0L) {
            val ttl = if (readyCache) READY_OK_TTL_MS else READY_FAIL_TTL_MS
            if (now - readyAtMs < ttl) return readyCache
        }
        if (readyProbing.compareAndSet(false, true)) {
            io.execute {
                try {
                    val r = probeChannelSync()
                    val changed = r.first != readyCache || r.second != readyReason
                    readyCache = r.first
                    readyReason = r.second
                    readyAtMs = SystemClock.uptimeMillis()
                    if (changed) Lg.i(LOG_TAG, "SfGlassChannel lg-sf-ready ready=${r.first} reason=${r.second}")
                    if (!r.first) sessionGlassOff("channel-unavailable")
                } catch (t: Throwable) {
                    readyCache = false
                    readyReason = "probe error: ${t.javaClass.simpleName}"
                    readyAtMs = SystemClock.uptimeMillis()
                    Lg.w(LOG_TAG, "SfGlassChannel lg-sf-ready probe failed", t)
                } finally {
                    readyProbing.set(false)
                }
            }
        }
        return readyCache
    }

    /** 当前不可用原因（供设置页如实回显）。 */
    fun channelReason(): String = readyReason

    /**
     * 同步探测（阻塞，只许后台线程调）：模块树在位 + 未被禁用 + 装载件在树内 + state 非 disabled。
     * state 缺失/`booting` 视为可用（装机后未重启或稳定窗未走完，不该把玻璃关掉）。
     */
    internal fun probeChannelSync(): Pair<Boolean, String> {
        val script = """
            if [ ! -f "$MODULE_PROP" ]; then echo NO_MODULE; exit 0; fi
            if [ -f "$MOD_DIR/disable" ]; then echo DISABLED; exit 0; fi
            if [ -f "$MOD_DIR/remove" ]; then echo REMOVED; exit 0; fi
            if [ ! -f "$LOADER_IN_TREE" ]; then echo NO_LOADER; exit 0; fi
            if [ -f "$STATE_FILE" ]; then
              read -r s rest < "$STATE_FILE" 2>/dev/null
              echo "STATE=${'$'}{s:-empty}"
            else
              echo STATE=absent
            fi
        """.trimIndent()
        val r = runSu(script)
        if (!r.ok) return false to "root 不可用：${r.err ?: r.summary}"
        return classifyProbe(r.out)
    }

    /** 探测输出判定（与 shell 脚本一一对应，单测覆盖）。 */
    internal fun classifyProbe(out: String): Pair<Boolean, String> {
        val t = out.trim().lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }.orEmpty()
        return when {
            t == "NO_MODULE" -> false to "系统模块未安装（$MOD_DIR 不存在）"
            t == "DISABLED" -> false to "系统模块已被禁用"
            t == "REMOVED" -> false to "系统模块待移除"
            t == "NO_LOADER" -> false to "模块树内缺装载件"
            t == "STATE=disabled" -> false to "系统模块已熔断（连续两轮 SF 崩溃）"
            t.startsWith("STATE=") -> true to t.substringAfter('=')
            else -> false to "探测输出异常：${t.ifBlank { "(无输出)" }}"
        }
    }

    // ---------- 会话撤层（关玻璃/省电/热重载/模式翻转共用） ----------

    /**
     * 会话玻璃被关（总开关关 / 省电开 / hot-reload / 切回应用级）：本会话发过 on 才发 off，
     * 防残留层盖屏；异步执行不阻塞调用线程。
     */
    fun sessionGlassOff(reason: String) {
        if (!sessionOn) return
        sessionOn = false
        pendingOffer.set(null)
        io.execute {
            // 复检：off 在途期间若有新认领则撤回本次 off（新认领的 on 由其 flush 发出）
            if (sessionOn) return@execute
            lastOfferKey = ""
            val err = retract()
            if (err == null) {
                writeConf(0)
                Lg.i(LOG_TAG, "SfGlassChannel lg-sf-session-off reason=$reason")
            } else {
                throttled("lg-sf-session-off-fail", "撤层失败 reason=$reason: $err")
            }
        }
    }

    /** 空闲看门狗：本会话有 on、连续无认领达阈值 → 自动撤申报（元素停画防残留浮窗）。 */
    private fun idleOffTick() {
        try {
            if (!sessionOn) return
            val now = SystemClock.uptimeMillis()
            if (now - lastClaimAtMs < SESSION_IDLE_OFF_MS) return
            sessionOn = false
            pendingOffer.set(null)
            // 并入 io 单线程执行，防与下发并发操作同一宿主 SC
            io.execute {
                // 复检：排队期间有新认领则撤回本次 idle-off
                if (sessionOn) return@execute
                lastOfferKey = ""
                val err = retract()
                if (err == null) writeConf(0)
                Lg.i(
                    LOG_TAG,
                    "SfGlassChannel lg-sf-idle-off 无认领满 ${SESSION_IDLE_OFF_MS}ms 自动撤层" +
                        (if (err == null) "" else "（失败:$err）"),
                )
            }
        } catch (t: Throwable) {
            Lg.w(LOG_TAG, "SfGlassChannel idle off tick failed", t)
        }
    }

    // ---------- 绘制点认领 ----------

    /** 把任务投进通道 io 单线程：直调下发/撤层/测试框全走这里串行化。 */
    internal fun postIo(task: () -> Unit) {
        io.execute { task() }
    }

    /**
     * 绘制点认领：sf 档无条件认领——本帧交 SF、四腿不画，恒返回 true（消费端在不在不影响本决策；
     * 下发失败由通道节流日志记录，元素保持系统原厂绘制）。
     * 调用前须已判 [renderMode] == [Prefs.RENDER_MODE_SF]（app 档零开销零变化）。
     */
    fun claimForDraw(
        id: Int,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        corner: Float,
        host: View? = null,
    ): Boolean {
        lastClaimAtMs = SystemClock.uptimeMillis()
        sessionOn = true
        var originX = 0f
        var originY = 0f
        if (host != null) {
            try {
                val loc = intArrayOf(0, 0)
                host.rootView.getLocationOnScreen(loc)
                originX = loc[0].toFloat()
                originY = loc[1].toFloat()
            } catch (t: Throwable) {
                throttled("lg-sf-origin", "window origin fail: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        offer(id, left, top, width, height, corner, host, originX, originY)
        return true
    }

    // ---------- 直调下发（全局 250ms 节流 + 最新几何单槽） ----------

    private fun offer(
        id: Int,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        corner: Float,
        host: View?,
        originX: Float,
        originY: Float,
    ) {
        pendingOffer.set(Geom(id, left, top, width, height, corner, host, originX, originY))
        if (offerScheduled.compareAndSet(false, true)) {
            io.execute { flushOffers() }
        }
    }

    private fun flushOffers() {
        try {
            while (true) {
                val g = pendingOffer.getAndSet(null) ?: break
                val key = keyOf(g)
                if (key == lastOfferKey) continue
                val wait = OFFER_MIN_INTERVAL_MS - (SystemClock.uptimeMillis() - lastOfferAttemptAtMs)
                if (wait > 0) {
                    try {
                        Thread.sleep(wait)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
                lastOfferAttemptAtMs = SystemClock.uptimeMillis()
                val err = applyGeom(g)
                if (err == null) {
                    lastOfferKey = key
                    writeConf(1)
                    throttled(
                        "lg-sf-blur",
                        "已下发 id=${g.id} geom=(${g.l.toInt()},${g.t.toInt()},${g.w.toInt()}x${g.h.toInt()}) corner=${g.corner.toInt()}",
                    )
                } else {
                    throttled("lg-sf-blur-fail", "下发失败 id=${g.id}: $err（无模块玻璃，保持系统原厂绘制）")
                }
            }
        } finally {
            offerScheduled.set(false)
            if (pendingOffer.get() != null && offerScheduled.compareAndSet(false, true)) {
                io.execute { flushOffers() }
            }
        }
    }

    private fun keyOf(g: Geom): String =
        "${g.id}:${g.l.toInt()},${g.t.toInt()},${g.w.toInt()},${g.h.toInt()},${g.corner.toInt()}" +
            ":${g.originX.toInt()},${g.originY.toInt()}"

    /** 同步直调一帧模糊区域（阻塞，只许后台线程调）；origin 须在主线程取好传入。 */
    internal fun sendFrameSync(
        host: View,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        corner: Float,
        originX: Float,
        originY: Float,
    ): SuResult {
        val sc = SfBlurCall.hostScOf(host) ?: return SuResult(false, "", "host SurfaceControl 解析失败")
        val err = applyRegionsToSc(sc, left, top, width, height, corner, originX, originY)
        return if (err == null) SuResult(true, "ok", null) else SuResult(false, "", err)
    }

    /** 直调下发几何到宿主窗口 SC；返回 null=成功，否则失败原因。 */
    private fun applyGeom(g: Geom): String? {
        val sc = if (g.host != null) {
            SfBlurCall.hostScOf(g.host) ?: return "host SurfaceControl 解析失败"
        } else {
            SfBlurCall.fallbackSc() ?: return "host view 不可用且兜底窗口 SC 失败"
        }
        return applyRegionsToSc(sc, g.l, g.t, g.w, g.h, g.corner, g.originX, g.originY)
    }

    /** 把单条区域下发到指定 SC；换窗时先清旧窗防残留。 */
    private fun applyRegionsToSc(
        sc: Any,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        corner: Float,
        originX: Float,
        originY: Float,
    ): String? {
        if (sc !== lastHostSc) {
            // 换窗：先把旧窗口的模糊清掉，防残留
            lastHostSc?.let { old ->
                val clear = SfBlurCall.applyRegions(old, emptyArray(), 0)
                if (clear != null) throttled("lg-sf-clear-old", "旧窗口清除失败: $clear")
            }
            lastHostSc = sc
        }
        val r = blurRadiusPx()
        val region = SfBlurCall.regionOf(
            r.toFloat(), 1f,
            left - originX, top - originY, left - originX + width, top - originY + height,
            corner,
        )
        // 窗口级半径恒0：模糊只走申报 region（region 自带 radius），窗口级全屏模糊停用
        return SfBlurCall.applyRegions(sc, arrayOf(region), 0)
    }

    /** 撤层：清区域 + 窗口半径归零；无宿主 SC 时视为无事可做。 */
    private fun retract(): String? {
        val sc = lastHostSc ?: return null
        val err = SfBlurCall.applyRegions(sc, emptyArray(), 0)
        if (err == null) lastHostSc = null
        return err
    }

    private fun blurRadiusPx(): Int {
        val api = apiRef ?: return 13
        return try {
            Prefs.read(api)
                .getFloat(Prefs.KEY_BLUR_RADIUS, Prefs.DEFAULT_BLUR_RADIUS).toInt()
                .coerceAtLeast(1)
        } catch (_: Throwable) {
            13
        }
    }

    /**
     * on 写进 conf（SF 侧 TTL 500ms 惰性读）：读改写只换 on，材质/几何字段原样保留——
     * 玻璃几何走 generate 每帧 region 直取，不经配置。路径在 system_lib_file，
     * platform_app 无写权，读写都走 su。
     */
    private fun writeConf(on: Int) {
        try {
            val rd = runSu("cat $CONF_PATH 2>/dev/null")
            val cur = if (rd.ok) rd.out else null
            val base = cur?.lineSequence()?.firstOrNull { it.contains("on=") }
                ?: "on=0 bx=60 by=60 bw=500 bh=300 br=60 timeout_ms=100 overtrip=10 rf=28 ds=0 hg=0.5 ag=45 vb=1.5 ib=0"
            val merged = base.trim().split(Regex("\\s+")).joinToString(" ") { t ->
                val i = t.indexOf('=')
                if (i <= 0) t else when (t.substring(0, i)) {
                    "on" -> "on=$on"
                    else -> t
                }
            }
            val w = runSu("printf '%s\\n' '$merged' > $CONF_PATH")
            if (!w.ok) {
                throttled("lg-sf-conf", "conf 写失败 on=$on: ${w.err ?: w.summary}")
            }
        } catch (t: Throwable) {
            throttled("lg-sf-conf", "conf 写失败 on=$on: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ---------- su 执行底座 ----------

    /**
     * su 执行脚本：`su -c sh` 起单层 shell、脚本整体经 stdin 喂入 —— `;`/`&&`/`>`/`$()`
     * 全部由该层 sh 原生解析，无多层命令行引号剥离坑；超时强杀防挂。
     */
    internal fun runSu(script: String, timeoutMs: Long = SU_TIMEOUT_MS): SuResult {
        return try {
            val p = ProcessBuilder("su", "-c", "sh")
                .redirectErrorStream(true)
                .start()
            // 读线程先于写 stdin 启动：脚本灌满管道缓冲时读侧不消费会互等
            val out = StringBuilder()
            val reader = Thread {
                try {
                    p.inputStream.bufferedReader().forEachLine { line ->
                        synchronized(out) { out.append(line).append('\n') }
                    }
                } catch (_: Throwable) {
                }
            }.apply { isDaemon = true; start() }
            try {
                p.outputStream.bufferedWriter().use { w ->
                    w.write(script)
                    if (!script.endsWith('\n')) w.write("\n")
                }
            } catch (t: Throwable) {
                // stdin 写失败多为 su 已拒/授权超时，继续走 wait 收尸拿 exit
            }
            val done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!done) {
                p.destroyForcibly()
                reader.join(500)
                val text = synchronized(out) { out.toString() }
                return SuResult(false, text, "su 超时 ${timeoutMs}ms（root 授权未通过或进程挂起）")
            }
            reader.join(500)
            val text = synchronized(out) { out.toString() }
            if (p.exitValue() == 0) SuResult(true, text, null)
            else SuResult(false, text, "exit=${p.exitValue()} ${text.trim().take(200).ifBlank { "(无输出)" }}")
        } catch (t: Throwable) {
            SuResult(false, "", "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ---------- 节流日志 ----------

    private val throttleAt = ConcurrentHashMap<String, Long>()

    private fun throttled(key: String, msg: String, intervalMs: Long = 2000L) {
        val now = SystemClock.uptimeMillis()
        val last = throttleAt[key]
        if (last != null && now - last < intervalMs) return
        throttleAt[key] = now
        if (throttleAt.size > 32) throttleAt.clear()
        // key 回显进文本，按 key grep 可命中；tag 并入主标签，模块标签过滤抓取也能收到
        Lg.i(LOG_TAG, "$TAG [$key] $msg")
    }
}
