package com.coloros16.liquidglass.entry

/**
 * 循环崩守卫的纯判定逻辑（零 Android 依赖，可单测）。
 *
 * 计数口径：窗口内到达安装点的**不同 pid** 数达到阈值即判循环崩。
 * 两条豁免只针对「不是崩溃」的重启：热重载同进程换代码；刚装过新包后的宽限窗。
 * 宽限窗过后同一包连续崩仍会攒满阈值，防守能力不变。
 */
object CrashGuardPolicy {

    const val WINDOW_MS = 2 * 60_000L
    const val THRESHOLD = 4
    const val INSTALL_GRACE_MS = 90_000L

    /** 判定结果：是否拦安装、是否强制关总开关、新账本、新 APK 戳、日志原因。 */
    data class Decision(
        val block: Boolean,
        val forceMasterOff: Boolean,
        val ledger: String,
        val apkStamp: Long,
        val reason: String,
    )

    /**
     * @param now 当前时刻（毫秒）
     * @param pid 本次到达安装点的进程 pid
     * @param ledger 旧账本 `ts,pid;ts,pid`
     * @param apkStamp 模块 APK lastUpdateTime，0 = 取不到（不启用宽限）
     * @param lastStamp 账本里记的上次 APK 戳
     * @param hotReload 本次安装由热重载触发
     */
    fun decide(
        now: Long,
        pid: Int,
        ledger: String,
        apkStamp: Long,
        lastStamp: Long,
        hotReload: Boolean,
    ): Decision {
        if (hotReload) {
            return Decision(false, false, ledger, lastStamp, "hot-reload")
        }
        val freshInstall = apkStamp > 0L && apkStamp != lastStamp
        val age = now - apkStamp
        val inGrace = apkStamp > 0L && age in 0L until INSTALL_GRACE_MS
        if (inGrace) {
            return Decision(false, false, "", apkStamp, "grace apkAge=${age / 1000}s")
        }
        val raw = if (freshInstall) "" else ledger
        val byPid = parse(raw, now)
        byPid[pid] = now
        return if (byPid.size >= THRESHOLD) {
            Decision(true, true, "", apkStamp, "loop pids=${byPid.keys.sorted()}")
        } else {
            Decision(false, false, format(byPid), apkStamp, "count=${byPid.size}/$THRESHOLD")
        }
    }

    /** 账本解析：丢弃格式非法项与窗口外项，同 pid 取最新时间戳。 */
    fun parse(ledger: String, now: Long): HashMap<Int, Long> {
        val out = HashMap<Int, Long>()
        ledger.split(';').forEach { part ->
            val bits = part.split(',')
            val ts = bits.getOrNull(0)?.toLongOrNull() ?: return@forEach
            val p = bits.getOrNull(1)?.toIntOrNull() ?: return@forEach
            if (now - ts in 0L until WINDOW_MS) {
                out[p] = maxOf(out[p] ?: 0L, ts)
            }
        }
        return out
    }

    fun format(byPid: Map<Int, Long>): String =
        byPid.entries.joinToString(";") { "${it.value},${it.key}" }
}