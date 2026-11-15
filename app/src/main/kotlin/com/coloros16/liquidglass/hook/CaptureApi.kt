package com.coloros16.liquidglass.hook

import android.os.SystemClock
import com.coloros16.liquidglass.engine.SafetyFuse
import com.coloros16.liquidglass.log.Lg
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ScreenCapture 类族统一解析入口。
 *
 * Android 17 把抓屏类族迁到 `android.window.ScreenCaptureInternal`；参数/监听器必须整族同源，
 * 新旧类型混用会静默炸抓屏，故整个家族只经 [load] 单入口解析。
 */
object CaptureApi {
    private const val TAG = "lg-capture"
    private const val LEGACY = "android.window.ScreenCapture"
    private const val INTERNAL = "android.window.ScreenCaptureInternal"
    private const val THROTTLE_MS = 2000L

    private const val FUSE_DOMAIN = "capture"
    private const val FUSE_THRESHOLD = 6
    private const val FUSE_WINDOW_MS = 60_000L
    private const val SKIP_LOG_MS = 10_000L

    @Volatile
    private var resolved: String? = null
    private val throttleAt = ConcurrentHashMap<String, Long>()

    private val fuse = SafetyFuse()
    private val fuseArmed = AtomicBoolean(false)
    @Volatile
    private var fuseTripped = false
    @Volatile
    private var pendingFailures = false
    @Volatile
    private var lastSkipLogAt = 0L

    /**
     * 抓屏入口统一门：已熔断则返回 false（调用方直接回退系统模糊），并节流打一条跳过日志。
     * 熔断只统计异常/反射失败，不统计「抓出来是 null」这类正常空结果。
     */
    fun beginCapture(): Boolean {
        if (!fuseTripped) return true
        val now = SystemClock.uptimeMillis()
        if (now - lastSkipLogAt >= SKIP_LOG_MS) {
            lastSkipLogAt = now
            Lg.w(TAG, "lg-capture-skip: fuse tripped, glass source falls back to system blur")
        }
        return false
    }

    /** 抓屏异常/类族缺失上报；窗口内超阈值熔断。 */
    fun recordFailure(reason: String) {
        armFuse()
        pendingFailures = true
        fuse.recordFailure(FUSE_DOMAIN, reason)
    }

    /** 一次成功抓屏清空失败窗口（无失败时不进同步块，热路径零开销）。 */
    fun recordSuccess() {
        if (!pendingFailures) return
        pendingFailures = false
        fuse.recordSuccess(FUSE_DOMAIN)
    }

    fun isTripped(): Boolean = fuseTripped

    private fun armFuse() {
        if (!fuseArmed.compareAndSet(false, true)) return
        fuse.register(
            FUSE_DOMAIN,
            SafetyFuse.Policy(threshold = FUSE_THRESHOLD, windowMs = FUSE_WINDOW_MS),
        ) { report ->
            fuseTripped = true
            Lg.e(
                TAG,
                "lg-fuse: capture tripped failures=${report.failures} reason=${report.reason}; " +
                    "glass source falls back to system blur",
            )
        }
    }

    /**
     * 运行时抓屏类族探针：返回该类族属于哪一代（LEGACY / INTERNAL / UNSUPPORTED）。
     * 探针是生效真值，版本池只提供「该版本号本来该是哪一代」的期望。
     */
    fun probedRev(loader: ClassLoader?): CaptureRev = try {
        when (resolve(loader)) {
            INTERNAL -> CaptureRev.INTERNAL
            else -> CaptureRev.LEGACY
        }
    } catch (_: Throwable) {
        CaptureRev.UNSUPPORTED
    }

    /** 抓屏族探针与版本池期望对账，装载期各宿主打一行 `lg-capture-audit`。 */
    fun logAudit(pkg: String, ver: Long, loader: ClassLoader?) {
        try {
            val probed = probedRev(loader)
            val expected = VersionPool.query(pkg, ver).profile.captureRev
            if (expected != CaptureRev.UNKNOWN && expected != probed) {
                Lg.w(TAG, "lg-capture-audit MISMATCH pkg=$pkg ver=$ver expected=$expected probed=$probed")
            } else {
                Lg.i(TAG, "lg-capture-audit pkg=$pkg ver=$ver expected=$expected probed=$probed")
            }
        } catch (t: Throwable) {
            Lg.w(TAG, "lg-capture-audit failed ${t.javaClass.simpleName}")
        }
    }

    /** 抓屏类族取类：族内名（`ScreenCapture` / `ScreenCapture$*`）统一解析，族外名原样透传。 */
    fun load(name: String, loader: ClassLoader?): Class<*> {
        if (name != LEGACY && !name.startsWith(LEGACY + "$")) {
            return if (loader != null) Class.forName(name, false, loader) else Class.forName(name)
        }
        val root = resolved ?: synchronized(this) {
            resolved ?: resolve(loader).also { resolved = it }
        }
        return Class.forName(root + name.removePrefix(LEGACY), false, loader)
    }

    /** 先试 ScreenCaptureInternal（三件齐全才算成立），不成立回退 ScreenCapture。 */
    private fun resolve(loader: ClassLoader?): String {
        for (root in listOf(INTERNAL, LEGACY)) {
            try {
                val type = Class.forName(root, false, loader)
                Class.forName(root + "\$CaptureArgs\$Builder", false, loader).getMethod("build")
                Class.forName(root + "\$DisplayCaptureArgs\$Builder", false, loader)
                type.getMethod("createSyncCaptureListener")
                logThrottled("capture-family", "抓屏类族解析成功 root=$root")
                return root
            } catch (_: ClassNotFoundException) {
            } catch (_: NoSuchMethodException) {
            }
        }
        val error = ClassNotFoundException("抓屏类族缺失：ScreenCaptureInternal/ScreenCapture 均不完整")
        logThrottled("capture-family-miss", "抓屏类族解析失败：两代族都不完整", error)
        recordFailure("capture-family-miss")
        throw error
    }

    /** 节流诊断日志：同 key 2s 内只打一条，key 回显进打印文本。 */
    private fun logThrottled(key: String, msg: String, tr: Throwable? = null) {
        val now = SystemClock.uptimeMillis()
        val last = throttleAt[key]
        if (last != null && now - last < THROTTLE_MS) return
        throttleAt[key] = now
        if (throttleAt.size > 64) throttleAt.clear()
        if (tr != null) Lg.w(TAG, "[$key] $msg", tr) else Lg.i(TAG, "[$key] $msg")
    }
}
