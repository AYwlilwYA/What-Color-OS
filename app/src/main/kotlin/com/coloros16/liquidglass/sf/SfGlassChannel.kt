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
 * 互斥语义：模式 sf = SF 独占——绘制点认领后本帧交 SF、四腿全关；认领/下发失败 =
 * 无模块玻璃（系统原厂绘制）+ 节流日志，绝不回退四腿。模式 app = 现役四腿，本通道零参与。
 */
object SfGlassChannel {

    private const val TAG = "SfGlassChannel"
    private const val MODE_TTL_MS = 500L
    private const val OFFER_MIN_INTERVAL_MS = 250L
    private const val SESSION_IDLE_OFF_MS = 5000L
    private const val WATCHDOG_TICK_MS = 2000L
    private const val SU_TIMEOUT_MS = 8000L

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
        if (now - modeCacheAtMs < MODE_TTL_MS) return modeCache
        modeCacheAtMs = now
        val v = try {
            Prefs.read(api).getString(Prefs.KEY_RENDER_MODE, Prefs.DEFAULT_RENDER_MODE)
                ?: Prefs.DEFAULT_RENDER_MODE
        } catch (t: Throwable) {
            Lg.w(TAG, "render_mode read failed, default ${Prefs.DEFAULT_RENDER_MODE}", t)
            Prefs.DEFAULT_RENDER_MODE
        }
        if (v != modeCache) {
            val prev = modeCache
            modeCache = v
            Lg.i(TAG, "lg-sf-mode mode=$v prev=$prev")
            if (v == Prefs.RENDER_MODE_APP && prev == Prefs.RENDER_MODE_SF) {
                sessionGlassOff("mode-switch-app")
            }
        }
        return v
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
                Lg.i(TAG, "lg-sf-session-off reason=$reason")
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
                Lg.i(
                    TAG,
                    "lg-sf-idle-off 无认领满 ${SESSION_IDLE_OFF_MS}ms 自动撤层" +
                        (if (err == null) "" else "（失败:$err）"),
                )
            }
        } catch (t: Throwable) {
            Lg.w(TAG, "idle off tick failed", t)
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
        return SfBlurCall.applyRegions(sc, arrayOf(region), r)
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
        // key 回显进文本，按 key grep 可命中
        Lg.i(TAG, "[$key] $msg")
    }
}
