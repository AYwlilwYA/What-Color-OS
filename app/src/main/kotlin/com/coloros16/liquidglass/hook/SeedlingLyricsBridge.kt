package com.coloros16.liquidglass.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SharedMemory
import android.os.SystemClock
import com.coloros16.liquidglass.engine.LyricLine
import com.coloros16.liquidglass.engine.LyricTimeline
import com.coloros16.liquidglass.log.Lg
import java.nio.ByteBuffer
import org.json.JSONObject

/**
 * 歌词桥（开放歌词桥协议（io.github.proify.lyricon））：
 * 收 REGISTER_PROVIDER 广播 → onRegistrationCallback 回传我们的 IRemoteService binder →
 * 提供者推 setSong(JSON bytes) + getPositionMemory(SharedMemory getLong(0)=positionMs)。
 * 行选择走 engine/LyricTimeline（纯 JVM）；位置读取按需（渲染帧才读，零轮询）。
 */
object SeedlingLyricsBridge {

    private const val TAG = "LiquidGlass"
    private const val ACTION_REGISTER = "io.github.proify.lyricon.lyric.bridge.REGISTER_PROVIDER"
    private const val ACTION_BOOT = "io.github.proify.lyricon.lyric.bridge.CENTRAL_BOOT_COMPLETED"
    private const val DESC_PROVIDER_BINDER = "io.github.proify.lyricon.provider.IProviderBinder"
    private const val DESC_REMOTE_SERVICE = "io.github.proify.lyricon.provider.IRemoteService"
    private const val DESC_REMOTE_PLAYER = "io.github.proify.lyricon.provider.IRemotePlayer"

    @Volatile
    private var installed = false

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var timeline: LyricTimeline? = null
        private set

    @Volatile
    var songTitle: String = ""
        private set

    @Volatile
    var songArtist: String = ""
        private set

    private var positionMemory: SharedMemory? = null
    private var positionBuffer: ByteBuffer? = null

    @Volatile
    var onLyricsUpdated: (() -> Unit)? = null

    /** 当前播放位：SharedMemory 优先，无内存用 PlaybackState 基准外推；都无回 0。 */
    fun positionMs(): Long {
        val buf = positionBuffer
        if (buf != null) {
            try {
                return buf.getLong(0).coerceIn(0L, 604800000L)
            } catch (_: Throwable) {
            }
        }
        return baseline?.positionAt(SystemClock.elapsedRealtime()) ?: 0L
    }

    fun currentLine(): LyricLine? = timeline?.currentLine(positionMs())

    /** 是否有歌词时间轴（提供者已注册且解析出行）。 */
    fun hasLyrics(): Boolean = timeline?.lines?.isNotEmpty() == true

    fun install(ctx: Context) {
        if (installed) return
        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action != ACTION_REGISTER) return
                    try {
                        val bundle = intent.getBundleExtra("bundle")
                        val binder = bundle?.getBinder("binder")
                        if (binder == null) {
                            Lg.w(TAG, "seedling-lyrics: register missing binder")
                            return
                        }
                        registerProvider(binder)
                    } catch (t: Throwable) {
                        Lg.e(TAG, "seedling-lyrics: register failed", t)
                    }
                }
            }
            val filter = IntentFilter(ACTION_REGISTER)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                ctx.registerReceiver(receiver, filter)
            }
            installed = true
            ctx.sendBroadcast(Intent(ACTION_BOOT))
            Lg.i(TAG, "seedling-lyrics: bridge installed, boot broadcast sent")
        } catch (t: Throwable) {
            Lg.e(TAG, "seedling-lyrics: install failed", t)
        }
    }

    /** 调提供者 onRegistrationCallback(transact 1)，回传我们的 IRemoteService stub。 */
    private fun registerProvider(providerBinder: IBinder) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESC_PROVIDER_BINDER)
            data.writeStrongBinder(remoteServiceStub)
            providerBinder.transact(1, data, reply, 0)
            reply.readException()
            Lg.i(TAG, "seedling-lyrics: provider registered")
        } catch (t: Throwable) {
            Lg.w(TAG, "seedling-lyrics: registration callback failed: ${t.message}")
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** 我们的 IRemoteService：getPlayer 返回 player stub；disconnect 清状态。 */
    private val remoteServiceStub = object : Binder() {
        init {
            attachInterface(null, DESC_REMOTE_SERVICE)
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code in 1..16777215) data.enforceInterface(DESC_REMOTE_SERVICE)
            return when (code) {
                1 -> {
                    reply?.writeNoException()
                    reply?.writeStrongBinder(remotePlayerStub)
                    true
                }
                2 -> {
                    mainHandler.post { clearSong() }
                    reply?.writeNoException()
                    true
                }
                else -> super.onTransact(code, data, reply, flags)
            }
        }
    }

    /** 我们的 IRemotePlayer：setSong(6)/getPositionMemory(1)/setPlaybackState 等按 AIDL 序号。 */
    private val remotePlayerStub = object : Binder() {
        init {
            attachInterface(null, DESC_REMOTE_PLAYER)
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code in 1..16777215) data.enforceInterface(DESC_REMOTE_PLAYER)
            when (code) {
                1 -> {
                    val bytes = data.createByteArray()
                    mainHandler.post { onSongBytes(bytes) }
                    reply?.writeNoException()
                    return true
                }
                2 -> {
                    data.readInt()
                    reply?.writeNoException()
                    return true
                }
                7 -> {
                    val mem = try {
                        if (data.readInt() != 0) SharedMemory.CREATOR.createFromParcel(data) else null
                    } catch (_: Throwable) {
                        null
                    }
                    if (reply != null) {
                        reply.writeNoException()
                        reply.writeInt(if (mem != null) 1 else 0)
                        mem?.writeToParcel(reply, 0)
                    }
                    return true
                }
                9 -> {
                    val pb = android.media.session.PlaybackState.CREATOR.createFromParcel(data)
                    mainHandler.post { onPlaybackState(pb) }
                    reply?.writeNoException()
                    return true
                }
                else -> {
                    reply?.writeNoException()
                    return true
                }
            }
        }
    }

    private fun attachPositionMemory(mem: SharedMemory?) {
        detachPositionMemory()
        if (mem == null) return
        try {
            positionMemory = mem
            positionBuffer = mem.mapReadOnly()
            Lg.i(TAG, "seedling-lyrics: position memory attached size=${mem.size}")
        } catch (t: Throwable) {
            Lg.w(TAG, "seedling-lyrics: map failed: ${t.message}")
        }
    }

    /** setPlaybackState2：位置基准三元组（position/lastUpdateTime/speed），无 SharedMemory 时的外推源。 */
    private var baseline: com.coloros16.liquidglass.engine.PositionBaseline? = null

    private fun onPlaybackState(pb: android.media.session.PlaybackState?) {
        if (pb == null) return
        val speed = pb.playbackSpeed.takeIf { it in 0.01f..16f } ?: 1f
        val base = pb.lastPositionUpdateTime.takeIf { it > 0 } ?: SystemClock.elapsedRealtime()
        baseline = com.coloros16.liquidglass.engine.PositionBaseline(pb.position, base, speed)
    }

    private fun detachPositionMemory() {
        positionBuffer?.let { runCatching { SharedMemory.unmap(it) } }
        positionMemory?.let { runCatching { it.close() } }
        positionBuffer = null
        positionMemory = null
    }

    private fun onSongBytes(bytes: ByteArray?) {
        if (bytes == null || bytes.isEmpty()) {
            clearSong()
            return
        }
        try {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            songTitle = json.optString("name", "")
            songArtist = json.optString("artist", "")
            val arr = json.optJSONArray("lyrics")
            val lines = mutableListOf<LyricLine>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val start = o.optLong("start", o.optLong("time", 0L))
                    val text = o.optString("text", "")
                    if (text.isEmpty()) continue
                    lines += LyricLine(start, text, o.optString("translation", ""))
                }
            }
            timeline = LyricTimeline(lines.sortedBy { it.atMs })
            Lg.i(TAG, "seedling-lyrics: song=$songTitle artist=$songArtist lines=${lines.size}")
            onLyricsUpdated?.invoke()
        } catch (t: Throwable) {
            Lg.w(TAG, "seedling-lyrics: song parse failed: ${t.message}")
        }
    }

    private fun clearSong() {
        timeline = null
        songTitle = ""
        songArtist = ""
        detachPositionMemory()
        onLyricsUpdated?.invoke()
    }

    fun shutdown() {
        clearSong()
        installed = false
    }
}