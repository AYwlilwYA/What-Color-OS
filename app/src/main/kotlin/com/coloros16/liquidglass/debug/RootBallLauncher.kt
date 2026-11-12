package com.coloros16.liquidglass.debug

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Handler
import android.os.Looper
import com.coloros16.liquidglass.log.Lg
import com.coloros16.liquidglass.sf.SfGlassChannel
import java.io.File
import java.util.concurrent.Executors

/**
 * root imgui 球控制端：起球/停止/状态经 abstract socket（lgballd）交 root daemon
 * 代执行；连不上 daemon（或握不上版本）时走 su 通道（SfGlassChannel.runSu）重建部署
 * 并拉起 daemon。二进制与日志只在会话期存在：球停止后同轮删除。
 */
object RootBallLauncher {

    private const val TAG = "RootBallLauncher"
    private const val SOCK_NAME = "lgballd"
    private const val PROTO_VER = "OK v2"
    private const val CLEANUP_GRACE_MS = 1000L

    /** 与 lgball.cpp kBinPath/kLogPath 同源，改一处必须同步另一处。 */
    private const val BIN_NAME = "lgball"
    private const val LOG_NAME = "lgball.log"

    // 起 daemon：SELinux 放行（socket 互连 + app 私有目录执行/读）→ 撤旧 tmp 产物 → 从 app 私有目录拉起
    private val START_SCRIPT = """
        if [ -x /data/adb/ap/bin/magiskpolicy ]; then
            /data/adb/ap/bin/magiskpolicy --live "allow untrusted_app magisk unix_stream_socket { connectto }"
            /data/adb/ap/bin/magiskpolicy --live "allow magisk untrusted_app unix_stream_socket { accept }"
            /data/adb/ap/bin/magiskpolicy --live "allow magisk app_data_file file { getattr open read map execute execute_no_trans }"
            /data/adb/ap/bin/magiskpolicy --live "allow magisk app_data_file dir { getattr search }"
        fi
        mv -f /data/local/tmp/lgball /data/local/tmp/lgball.retired 2>/dev/null
        mv -f /data/local/tmp/lgball.log /data/local/tmp/lgball.log.retired 2>/dev/null
        setsid /data/data/com.coloros16.liquidglass/files/bin/lgball daemon </dev/null >/dev/null 2>&1 &
    """.trimIndent()

    // 停 daemon 与球（pidof lgball 同时匹配 daemon 的 exe 名，重复 kill 无害）
    private val KILL_SCRIPT = """
        for p in $(pidof lgballd) $(pidof lgball); do kill ${'$'}p 2>/dev/null || true; done
    """.trimIndent()

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var sessionGen = 0
    private var cleanupGen = 0
    private var cleanupReason = ""
    private var cleanupKillDaemon = false

    fun start(onDone: (String) -> Unit) = submit("START", onDone)

    fun stop(onDone: (String) -> Unit) = submit("STOP", onDone)

    fun status(onDone: (String) -> Unit) = submit("STATUS", onDone)

    /** 单行协议收发（按钮频率低不节流，成功/失败全链留日志）；START 进新代际，作废在途清理。 */
    private fun submit(cmd: String, onDone: (String) -> Unit) {
        if (cmd == "START") {
            sessionGen++
            main.removeCallbacks(cleanupTask)
            Lg.i(TAG, "start requested gen=$sessionGen")
        }
        io.execute {
            val msg = when (cmd) {
                "START" -> runStart()
                "STOP" -> runStop()
                else -> runStatus()
            }
            main.post { onDone(msg) }
        }
    }

    /** 起球：VER 握手，连不上/旧 daemon/拉起失败各走一轮部署重建，重建后只再试一次。 */
    private fun runStart(): String {
        val ctx = resolveAppContext()
        if (ctx == null) {
            Lg.w(TAG, "start skipped: no application context")
            return "启动失败：拿不到应用上下文"
        }
        val ver = command("VER")
        var reply: String? = if (ver == PROTO_VER) command("START") else null
        if (reply == null || reply.startsWith("ERR spawn")) {
            Lg.i(TAG, "start rebuild path ver=${ver ?: "conn-fail"} reply=${reply ?: "conn-fail"}")
            if (!rebuildAndStartDaemon(ctx)) {
                return "启动失败：拉不起球服务，看 ${logPathOf(ctx)}"
            }
            reply = command("START")
            if (reply == null) return "启动失败：球服务无应答，看 ${logPathOf(ctx)}"
        }
        syncBridge("START", reply)
        return humanize(reply, ctx)
    }

    private fun runStop(): String {
        val ctx = resolveAppContext()
        val reply = command("STOP")
        if (reply == null) {
            Lg.i(TAG, "stop: daemon unreachable, treat as stopped")
            syncBridge("STOP", null)
            return "已停止（球服务未运行）"
        }
        syncBridge("STOP", reply)
        return humanize(reply, ctx)
    }

    private fun runStatus(): String {
        val ctx = resolveAppContext()
        val reply = command("STATUS")
        if (reply == null) {
            syncBridge("STATUS", null)
            return "未在运行（球服务未启动）"
        }
        syncBridge("STATUS", reply)
        return humanize(reply, ctx)
    }

    /** 杀旧进程 → 从 assets 复制二进制 → su 拉起新 daemon（脚本自带 SELinux 放行）。 */
    private fun rebuildAndStartDaemon(ctx: Context): Boolean {
        val kill = SfGlassChannel.runSu(KILL_SCRIPT)
        if (!kill.ok) Lg.i(TAG, "pre-kill su: ${kill.err ?: "ok"}")
        if (!deploy(ctx)) return false
        val start = SfGlassChannel.runSu(START_SCRIPT)
        if (!start.ok) {
            Lg.w(TAG, "daemon start su fail: ${start.err}")
            return false
        }
        Lg.i(TAG, "daemon started via su")
        return true
    }

    /** assets/lgball → files/bin/lgball（原子替换 + 可执行位），日志文件由 app 预建（root 只追加不新建）。 */
    private fun deploy(ctx: Context): Boolean {
        return try {
            val binDir = File(ctx.filesDir, "bin")
            if (!binDir.isDirectory && !binDir.mkdirs()) {
                Lg.w(TAG, "mkdir bin fail path=${binDir.absolutePath}")
                return false
            }
            val log = File(ctx.filesDir, LOG_NAME)
            if (!log.exists()) {
                log.createNewFile()
                Lg.i(TAG, "log precreated path=${log.absolutePath}")
            }
            val bin = File(binDir, BIN_NAME)
            val tmp = File(binDir, "$BIN_NAME.tmp")
            ctx.assets.open(BIN_NAME).use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            if (!tmp.renameTo(bin)) {
                bin.delete()
                if (!tmp.renameTo(bin)) {
                    tmp.delete()
                    Lg.w(TAG, "deploy rename fail path=${bin.absolutePath}")
                    return false
                }
            }
            bin.setExecutable(true, false)
            Lg.i(TAG, "deploy copied path=${bin.absolutePath} size=${bin.length()}")
            true
        } catch (t: Throwable) {
            Lg.w(TAG, "deploy fail", t)
            false
        }
    }

    /** 球探活：点选桥轮询期间周期调用；连不上/不活即收桥 + 清场（代际校验防串台）。 */
    internal fun probeBall() {
        io.execute {
            val gen = sessionGen
            val reply = command("STATUS", quiet = true)
            main.post {
                if (gen != sessionGen) {
                    Lg.i(TAG, "probe dropped, session advanced")
                    return@post
                }
                val alive = reply != null &&
                    (reply.startsWith("OK running pid=") || reply.startsWith("ERR running pid="))
                if (!alive) {
                    Lg.i(TAG, "probe: ball gone reply=${reply ?: "conn-fail"}")
                    PickBridge.shutdown("probe-ball-gone")
                    scheduleCleanup("probe-ball-gone", reply != null)
                }
            }
        }
    }

    /** 球活桥才活：正答拉起点选桥，停球/不活收桥并清场。 */
    private fun syncBridge(cmd: String, reply: String?) {
        val ballAlive = reply != null &&
            (reply.startsWith("OK pid=") || reply.startsWith("OK running pid=") ||
                reply.startsWith("ERR running pid="))
        val ballStopped = reply == null || reply.startsWith("OK stopped pid=") ||
            reply == "OK not-running"
        when {
            ballAlive -> PickBridge.ensureRunning()
            ballStopped && cmd == "STOP" -> {
                PickBridge.shutdown("stop-confirmed")
                scheduleCleanup("stop-confirmed", true)
            }
            ballStopped && cmd == "STATUS" -> {
                PickBridge.shutdown("status-not-running")
                scheduleCleanup("status-not-running", reply != null)
            }
            cmd == "STOP" -> Lg.i(TAG, "stop not confirmed, keep bridge pid-reply=$reply")
        }
    }

    /** 延迟清场（先给 relay 时间把 declare off 转出 app 目录）；新 START 进新代际即作废。 */
    private fun scheduleCleanup(reason: String, killDaemon: Boolean) {
        cleanupGen = sessionGen
        cleanupReason = reason
        cleanupKillDaemon = killDaemon
        main.removeCallbacks(cleanupTask)
        main.postDelayed(cleanupTask, CLEANUP_GRACE_MS)
        Lg.i(TAG, "cleanup scheduled reason=$reason killDaemon=$killDaemon in ${CLEANUP_GRACE_MS}ms")
    }

    private val cleanupTask = Runnable {
        val gen = cleanupGen
        val reason = cleanupReason
        val killDaemon = cleanupKillDaemon
        io.execute {
            if (gen != sessionGen) {
                Lg.i(TAG, "cleanup skipped, session advanced reason=$reason")
                return@execute
            }
            doCleanup(reason, killDaemon)
        }
    }

    /** 删本目录二进制与日志；进程侧只在 daemon 还活着或本地留有会话产物时才动 su。 */
    private fun doCleanup(reason: String, killDaemon: Boolean) {
        val ctx = resolveAppContext()
        var hadLocal = false
        if (ctx != null) {
            val bin = File(File(ctx.filesDir, "bin"), BIN_NAME)
            val log = File(ctx.filesDir, LOG_NAME)
            hadLocal = bin.exists() || log.exists()
            val binGone = !bin.exists() || bin.delete()
            val logGone = !log.exists() || log.delete()
            Lg.i(TAG, "cleanup local reason=$reason bin=${if (binGone) "gone" else "kept"}" +
                " log=${if (logGone) "gone" else "kept"} had=$hadLocal")
        } else {
            Lg.w(TAG, "cleanup: no context, local files kept")
        }
        if (killDaemon || hadLocal) {
            val r = SfGlassChannel.runSu(KILL_SCRIPT)
            if (r.ok) Lg.i(TAG, "cleanup kill su ok reason=$reason")
            else Lg.w(TAG, "cleanup kill su fail reason=$reason err=${r.err}")
        } else {
            Lg.i(TAG, "cleanup skip su reason=$reason")
        }
    }

    /** 单行协议收发；quiet 供周期探活用（成功不落日志，失败照落）。 */
    private fun command(cmd: String, quiet: Boolean = false): String? {
        return try {
            LocalSocket().use { s ->
                s.connect(LocalSocketAddress(SOCK_NAME, LocalSocketAddress.Namespace.ABSTRACT))
                s.outputStream.write("$cmd\n".toByteArray(Charsets.UTF_8))
                s.outputStream.flush()
                val reply = s.inputStream.bufferedReader().readLine() ?: ""
                if (!quiet) Lg.i(TAG, "$cmd -> ${reply.ifBlank { "(空)" }}")
                reply
            }
        } catch (t: Throwable) {
            Lg.w(TAG, "$cmd socket failed", t)
            null
        }
    }

    private fun logPathOf(ctx: Context): String = File(ctx.filesDir, LOG_NAME).absolutePath

    /** daemon 原始回复 → 调试页人话。 */
    private fun humanize(reply: String, ctx: Context?): String = when {
        reply.startsWith("OK pid=") -> "已启动 pid=${reply.removePrefix("OK pid=")}"
        reply.startsWith("OK stopped pid=") -> "已停止（原 pid=${reply.removePrefix("OK stopped pid=")}）"
        reply == "OK not-running" -> "未在运行"
        reply.startsWith("OK running pid=") -> "运行中 pid=${reply.removePrefix("OK running pid=")}"
        reply.startsWith("ERR running pid=") -> "已在运行 pid=${reply.removePrefix("ERR running pid=")}"
        reply == "ERR spawn" -> "启动失败：daemon 拉不起球，看 ${ctx?.let { logPathOf(it) } ?: LOG_NAME}"
        reply.startsWith("ERR alive pid=") -> "停止失败，仍有 pid=${reply.removePrefix("ERR alive pid=")}"
        reply.isEmpty() -> "daemon 空回复"
        else -> reply
    }
}
