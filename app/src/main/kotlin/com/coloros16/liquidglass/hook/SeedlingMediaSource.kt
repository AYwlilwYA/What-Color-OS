package com.coloros16.liquidglass.hook

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.media.MediaMetadata
import com.coloros16.liquidglass.engine.SeedlingAction
import com.coloros16.liquidglass.engine.SeedlingActivity
import com.coloros16.liquidglass.engine.SeedlingCompact
import com.coloros16.liquidglass.engine.SeedlingElement
import com.coloros16.liquidglass.engine.SeedlingExpanded
import com.coloros16.liquidglass.engine.SeedlingKind
import com.coloros16.liquidglass.engine.SeedlingTemplate
import com.coloros16.liquidglass.log.Lg

/**
 * 媒体内容源：MediaSessionManager 直读（SystemUI 进程有权限），事件驱动零轮询。
 * activeSessions 变化监听 + 每 controller Callback；播放判定带宽限（暂停 3s 宽限防抖）。
 */
object SeedlingMediaSource {

    private const val TAG = "LiquidGlass"
    private const val PAUSE_GRACE_MS = 3000L
    private const val PUBLISH_MERGE_MS = 250L

    @Volatile
    private var installed = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val callbacks = LinkedHashMap<MediaController, MediaController.Callback>()
    private val lastPlaying = LinkedHashMap<MediaController, Boolean>()
    private val pauseGraceAt = LinkedHashMap<MediaController, Long>()

    /** 封面 LRU4：key=contentId（曲目身份），value=解码位图（≤512 采样）。 */
    private val coverCache = object : LinkedHashMap<String, android.graphics.Bitmap>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, android.graphics.Bitmap>): Boolean = size > 4
    }
    private var appContext: Context? = null

    /** 切歌防抖状态：同会话换曲时刻 + 待发布曲目身份。 */
    private var trackChangeAtMs = 0L
    private var lastPublishedContentId: String? = null
    private var debouncePending = false

    @Volatile
    var onActivity: ((SeedlingActivity?, String) -> Unit)? = null

    /** 当前曲目封面（渲染端消费；null=无封面画符号）。 */
    @Volatile
    var currentCover: android.graphics.Bitmap? = null
        private set

    private var sessionManager: MediaSessionManager? = null
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null

    fun install(ctx: Context) {
        if (installed) return
        appContext = ctx.applicationContext ?: ctx
        try {
            val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            if (msm == null) {
                Lg.w(TAG, "seedling-media: MediaSessionManager unresolved")
                return
            }
            sessionManager = msm
            val listener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
                mainHandler.post { syncControllers(controllers ?: emptyList()) }
            }
            sessionsListener = listener
            val notificationListener = ComponentName(ctx, "com.coloros16.liquidglass.hook.SeedlingMediaSource")
            try {
                msm.addOnActiveSessionsChangedListener(listener, null, mainHandler)
            } catch (t: Throwable) {
                Lg.w(TAG, "seedling-media: sessions listener with null component failed: ${t.message}")
                return
            }
            installed = true
            Lg.i(TAG, "seedling-media: installed (MediaSession direct read)")
            syncControllers(msm.getActiveSessions(null) ?: emptyList())
        } catch (t: Throwable) {
            Lg.e(TAG, "seedling-media: install failed", t)
        }
    }

    fun shutdown() {
        try {
            sessionsListener?.let { sessionManager?.removeOnActiveSessionsChangedListener(it) }
        } catch (_: Throwable) {
        }
        callbacks.forEach { (c, cb) -> runCatching { c.unregisterCallback(cb) } }
        callbacks.clear()
        lastPlaying.clear()
        pauseGraceAt.clear()
        coverCache.clear()
        currentCover = null
        lastPublishedContentId = null
        trackChangeAtMs = 0L
        debouncePending = false
        sessionsListener = null
        sessionManager = null
        appContext = null
        installed = false
    }

    private fun syncControllers(controllers: List<MediaController>) {
        val incoming = controllers.toSet()
        val removed = callbacks.keys.filter { it !in incoming }
        removed.forEach { c ->
            runCatching { c.unregisterCallback(callbacks[c]!!) }
            callbacks.remove(c)
            lastPlaying.remove(c)
            pauseGraceAt.remove(c)
        }
        controllers.forEach { c ->
            if (callbacks.containsKey(c)) return@forEach
            val cb = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    schedulePublish()
                }

                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    schedulePublish()
                }

                override fun onSessionDestroyed() {
                    mainHandler.post {
                        callbacks.remove(c)?.let { runCatching { c.unregisterCallback(it) } }
                        lastPlaying.remove(c)
                        pauseGraceAt.remove(c)
                        schedulePublish()
                    }
                }
            }
            runCatching {
                c.registerCallback(cb, mainHandler)
                callbacks[c] = cb
            }
        }
        Lg.i(TAG, "seedling-media: sessions=${controllers.size}")
        schedulePublish()
    }

    private var publishPending = false

    private fun schedulePublish() {
        if (publishPending) return
        publishPending = true
        mainHandler.postDelayed({
            publishPending = false
            publish()
        }, PUBLISH_MERGE_MS)
    }

    private fun isPlaying(c: MediaController): Boolean {
        val state = c.playbackState?.state ?: PlaybackState.STATE_NONE
        val now = SystemClock.elapsedRealtime()
        val prev = lastPlaying[c] ?: false
        val playing = when (state) {
            PlaybackState.STATE_PLAYING, PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING -> true
            PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED -> {
                val graceStart = pauseGraceAt.getOrPut(c) { now }
                if (now - graceStart < PAUSE_GRACE_MS) prev else false
            }
            else -> prev
        }
        if (state != PlaybackState.STATE_PAUSED && state != PlaybackState.STATE_STOPPED) {
            pauseGraceAt.remove(c)
        }
        lastPlaying[c] = playing
        return playing
    }

    private fun publish() {
        try {
            val active = callbacks.keys.firstOrNull { isPlaying(it) }
                ?: callbacks.keys.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PAUSED }
            if (active == null) {
                currentCover = null
                onActivity?.invoke(null, "media")
                return
            }
            val md = active.metadata
            val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
            val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
            val duration = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            val pb = active.playbackState
            val playing = isPlaying(active)
            val pkg = active.packageName
            val contentId = "media:$pkg:$title"
            // 切歌防抖：同会话换曲且新封面未到 → 最多等 420ms 再发布（一次性延迟）
            val prevId = lastPublishedContentId
            val trackChanged = prevId != null && prevId != contentId && prevId.startsWith("media:$pkg:")
            val cover = resolveCover(md, contentId)
            if (!debouncePending) {
                val wait = com.coloros16.liquidglass.engine.SeedlingCoverPolicy.coverDebounceMs(
                    sameSession = true,
                    trackChanged = trackChanged,
                    newCoverPresent = cover != null,
                    oldCoverPresent = currentCover != null,
                    elapsedSinceChangeMs = if (trackChanged) {
                        if (trackChangeAtMs == 0L) trackChangeAtMs = SystemClock.elapsedRealtime()
                        SystemClock.elapsedRealtime() - trackChangeAtMs
                    } else 0L,
                )
                if (wait > 0L) {
                    debouncePending = true
                    Lg.i(TAG, "seedling-media: cover debounce ${wait}ms track=$title")
                    mainHandler.postDelayed({
                        debouncePending = false
                        publish()
                    }, wait)
                    return
                }
            }
            trackChangeAtMs = 0L
            lastPublishedContentId = contentId
            currentCover = cover
            val label = if (artist.isNotEmpty()) "$title - $artist" else title
            val actions = mutableListOf<SeedlingAction>()
            val mask = pb?.actions ?: 0L
            if (mask and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L) {
                actions += SeedlingAction("prev", "上一首", "SKIP_PREV")
            }
            actions += SeedlingAction("play_pause", if (playing) "暂停" else "播放", if (playing) "PAUSE" else "PLAY")
            if (mask and PlaybackState.ACTION_SKIP_TO_NEXT != 0L) {
                actions += SeedlingAction("next", "下一首", "SKIP_NEXT")
            }
            val likeName = pickLikeAction(pb)
            if (likeName != null) {
                likeActionName = likeName
                actions += SeedlingAction("like", "喜欢", "HEART")
            } else {
                likeActionName = null
            }
            val activity = SeedlingActivity(
                id = "media:$pkg",
                source = pkg,
                kind = SeedlingKind.MEDIA,
                startedAtElapsedMs = SystemClock.elapsedRealtime(),
                updatedAtElapsedMs = SystemClock.elapsedRealtime(),
                compact = SeedlingCompact(
                    leading = SeedlingElement.Symbol("MUSIC"),
                    label = label,
                    trailing = SeedlingElement.Waveform(playing),
                    contentId = contentId,
                ),
                expanded = SeedlingExpanded(
                    template = SeedlingTemplate.MEDIA,
                    title = title,
                    subtitle = artist,
                    durationMs = duration,
                    positionMs = pb?.position ?: 0L,
                    positionAtElapsedMs = SystemClock.elapsedRealtime(),
                    playbackSpeed = pb?.playbackSpeed ?: 1f,
                    playing = playing,
                    actions = actions,
                ),
            )
            Lg.i(TAG, "seedling-media: publish pkg=$pkg playing=$playing title=$title")
            onActivity?.invoke(activity, "media")
            currentController = active
        } catch (t: Throwable) {
            Lg.e(TAG, "seedling-media: publish failed", t)
        }
    }

    /** 封面解码：metadata bitmap 直取 → URI 白名单解码（采样翻倍 ≤512）→ LRU4 缓存。 */
    private fun resolveCover(md: MediaMetadata?, contentId: String): android.graphics.Bitmap? {
        coverCache[contentId]?.let { return it }
        val bmp = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (bmp != null && !bmp.isRecycled) {
            val scaled = scaleDown(bmp)
            coverCache[contentId] = scaled
            return scaled
        }
        val uriStr = md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
        val ctx = appContext ?: return null
        if (uriStr.isNullOrEmpty()) return null
        return try {
            val uri = android.net.Uri.parse(uriStr)
            if (!com.coloros16.liquidglass.engine.SeedlingCoverPolicy.schemeAllowed(uri.scheme)) {
                Lg.i(TAG, "seedling-media: cover scheme rejected ${uri.scheme}")
                return null
            }
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val sample = com.coloros16.liquidglass.engine.SeedlingCoverPolicy.sampleSizeFor(bounds.outWidth, bounds.outHeight)
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = ctx.contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, opts)
            } ?: return null
            coverCache[contentId] = decoded
            Lg.i(TAG, "seedling-media: cover decoded ${bounds.outWidth}x${bounds.outHeight} sample=$sample")
            decoded
        } catch (t: Throwable) {
            Lg.w(TAG, "seedling-media: cover decode failed: ${t.message}")
            null
        }
    }

    private fun scaleDown(bmp: android.graphics.Bitmap): android.graphics.Bitmap {
        val maxEdge = maxOf(bmp.width, bmp.height)
        if (maxEdge <= 512) return bmp
        val ratio = 512f / maxEdge
        return android.graphics.Bitmap.createScaledBitmap(
            bmp, (bmp.width * ratio).toInt().coerceAtLeast(1), (bmp.height * ratio).toInt().coerceAtLeast(1), true,
        )
    }

    @Volatile
    var currentController: MediaController? = null
        private set

    /** 大卡按钮回传：play_pause/prev/next/like/seek → MediaController.TransportControls。 */
    fun dispatchAction(actionId: String) {
        val c = currentController ?: return
        try {
            when (actionId) {
                "play_pause" -> {
                    val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
                    if (playing) c.transportControls.pause() else c.transportControls.play()
                }
                "prev" -> c.transportControls.skipToPrevious()
                "next" -> c.transportControls.skipToNext()
                "like" -> {
                    val name = likeActionName
                    if (name != null) c.transportControls.sendCustomAction(name, null)
                    else Lg.i(TAG, "seedling-media: like action absent, skipped")
                }
            }
            Lg.i(TAG, "seedling-media: action=$actionId dispatched")
        } catch (t: Throwable) {
            Lg.e(TAG, "seedling-media: action dispatch failed", t)
        }
    }

    /** 进度条拖动回传：fraction∈[0,1] × duration → seekTo。 */
    fun dispatchSeek(fraction: Float) {
        val c = currentController ?: return
        try {
            val duration = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            if (duration <= 0L) {
                Lg.i(TAG, "seedling-media: seek skipped (no duration)")
                return
            }
            val pos = (duration * fraction.coerceIn(0f, 1f)).toLong()
            c.transportControls.seekTo(pos)
            Lg.i(TAG, "seedling-media: seekTo $pos (fraction=$fraction)")
        } catch (t: Throwable) {
            Lg.e(TAG, "seedling-media: seek failed", t)
        }
    }

    @Volatile
    private var likeActionName: String? = null

    /** PlaybackState.CustomAction 名称词表识别喜欢动作（负向词排除）。 */
    private fun pickLikeAction(pb: PlaybackState?): String? {
        val customs = pb?.customActions ?: return null
        for (ca in customs) {
            val name = ca.action
            val label = ca.name?.toString()
            if (com.coloros16.liquidglass.engine.SeedlingLikeAction.isLikeName(name) ||
                com.coloros16.liquidglass.engine.SeedlingLikeAction.isLikeName(label)
            ) {
                return name
            }
        }
        return null
    }
}