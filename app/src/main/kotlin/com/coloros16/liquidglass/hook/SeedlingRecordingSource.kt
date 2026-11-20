package com.coloros16.liquidglass.hook

import android.content.Context
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.coloros16.liquidglass.engine.SeedlingAction
import com.coloros16.liquidglass.engine.SeedlingActivity
import com.coloros16.liquidglass.engine.SeedlingCompact
import com.coloros16.liquidglass.engine.SeedlingElement
import com.coloros16.liquidglass.engine.SeedlingExpanded
import com.coloros16.liquidglass.engine.SeedlingKind
import com.coloros16.liquidglass.engine.SeedlingTemplate
import com.coloros16.liquidglass.log.Lg

/**
 * 录音内容源：AudioRecordingCallback 事件驱动（录音开始/结束系统回调，零轮询）。
 * 任一活跃录音客户端开始录音 → RECORDING 活动上岛（MIC 符号+波形），全部结束 → 撤活动。
 * uid/包名 getter 为系统隐藏 API：反射取，取不到回落占位；本进程自身音频按 uid 过滤防自激励。
 */
object SeedlingRecordingSource {

    private const val TAG = "LiquidGlass"
    private const val ACTIVITY_ID = "recording:active"

    @Volatile
    var onActivity: ((SeedlingActivity?, String) -> Unit)? = null

    @Volatile
    private var installed = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var audioManager: AudioManager? = null
    private var callback: AudioManager.AudioRecordingCallback? = null
    private var startedAtMs = 0L

    fun install(ctx: Context) {
        if (installed) return
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (am == null) {
                Lg.w(TAG, "seedling-recording: AudioManager unresolved")
                return
            }
            audioManager = am
            val cb = object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                    mainHandler.post { onConfigs(configs.toList()) }
                }
            }
            callback = cb
            am.registerAudioRecordingCallback(cb, mainHandler)
            installed = true
            Lg.i(TAG, "seedling-recording: installed (AudioRecordingCallback event-driven)")
            onConfigs(am.activeRecordingConfigurations ?: emptyList())
        } catch (t: Throwable) {
            Lg.e(TAG, "seedling-recording: install failed", t)
        }
    }

    fun shutdown() {
        try {
            callback?.let { audioManager?.unregisterAudioRecordingCallback(it) }
        } catch (_: Throwable) {
        }
        callback = null
        audioManager = null
        installed = false
    }

    private fun clientUid(c: AudioRecordingConfiguration): Int = try {
        c.javaClass.getMethod("getClientUid").invoke(c) as? Int ?: -1
    } catch (_: Throwable) {
        -1
    }

    private fun clientPkg(c: AudioRecordingConfiguration): String = try {
        c.javaClass.getMethod("getClientPackageName").invoke(c) as? String ?: "unknown"
    } catch (_: Throwable) {
        "unknown"
    }

    private fun onConfigs(configs: List<AudioRecordingConfiguration>) {
        val selfUid = android.os.Process.myUid()
        val active = configs.filter { !it.isClientSilenced && clientUid(it) != selfUid }
        if (active.isEmpty()) {
            if (startedAtMs > 0) {
                Lg.i(TAG, "seedling-recording: all recording ended")
                startedAtMs = 0L
                onActivity?.invoke(null, "recording")
            }
            return
        }
        val first = active.first()
        val pkg = clientPkg(first)
        val now = SystemClock.elapsedRealtime()
        if (startedAtMs == 0L) startedAtMs = now
        val elapsedSec = (now - startedAtMs) / 1000L
        val mm = elapsedSec / 60L
        val ss = elapsedSec % 60L
        val label = "录音中 %02d:%02d".format(mm, ss)
        val activity = SeedlingActivity(
            id = ACTIVITY_ID,
            source = pkg,
            kind = SeedlingKind.RECORDING,
            startedAtElapsedMs = startedAtMs,
            updatedAtElapsedMs = now,
            compact = SeedlingCompact(
                leading = SeedlingElement.Symbol("MIC"),
                label = label,
                trailing = SeedlingElement.Waveform(true),
                contentId = ACTIVITY_ID,
            ),
            expanded = SeedlingExpanded(
                template = SeedlingTemplate.STATUS,
                title = "录音中",
                subtitle = pkg,
                actions = listOf(SeedlingAction("open_source", "打开来源", "MIC")),
            ),
        )
        Lg.i(TAG, "seedling-recording: active n=${active.size} pkg=$pkg source=${first.getClientAudioSource()}")
        onActivity?.invoke(activity, "recording")
    }
}