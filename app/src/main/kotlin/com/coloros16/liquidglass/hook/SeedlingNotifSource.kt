package com.coloros16.liquidglass.hook

import android.app.Notification
import android.app.PendingIntent
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import com.coloros16.liquidglass.engine.SeedlingAction
import com.coloros16.liquidglass.engine.SeedlingActivity
import com.coloros16.liquidglass.engine.SeedlingCodeExtractor
import com.coloros16.liquidglass.engine.SeedlingCompact
import com.coloros16.liquidglass.engine.SeedlingElement
import com.coloros16.liquidglass.engine.SeedlingExpanded
import com.coloros16.liquidglass.engine.SeedlingKind
import com.coloros16.liquidglass.engine.SeedlingNotifPolicy
import com.coloros16.liquidglass.engine.SeedlingTemplate
import com.coloros16.liquidglass.log.Lg
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode

/**
 * 通知上岛内容源：hook SystemUI NotificationListener 的 posted/removed（C16 dex 实证
 * :148/:181/:223 三签名），提取通知事实 → 分类 → 喂内容模型；动作按钮 PendingIntent.send 回传。
 * 媒体类通知让位 MediaSession 直读（不双源）；验证码走三段式提取；常驻通知默认不上岛。
 */
object SeedlingNotifSource {

    private const val TAG = "LiquidGlass"
    private const val CLASS_NOTIFICATION_LISTENER = "com.android.systemui.statusbar.NotificationListener"
    private const val MESSAGE_DEMOTE_MS = 6000L

    @Volatile
    private var mounted = false

    @Volatile
    var onActivity: ((SeedlingActivity?, String) -> Unit)? = null

    /** 单条通知移除回调（精确撤该活动，不连带别条）。 */
    @Volatile
    var onRemove: ((String) -> Unit)? = null

    /** 已上岛通知 key → 活动 id（removed 时撤）。 */
    private val postedKeys = LinkedHashMap<String, String>()

    /** 通知动作回传表：活动 id → (按钮 id → PendingIntent)。 */
    private val actionIntents = LinkedHashMap<String, LinkedHashMap<String, PendingIntent>>()

    /** 通知内容点击意图表：活动 id → contentIntent（打开来源用）。 */
    private val contentIntents = LinkedHashMap<String, PendingIntent>()

    fun install(api: XposedInterface, classLoader: ClassLoader) {
        if (mounted) return
        try {
            val cls = Class.forName(CLASS_NOTIFICATION_LISTENER, false, classLoader)
            var hooked = 0
            for (m in cls.declaredMethods) {
                when (m.name) {
                    "onNotificationPosted" -> {
                        api.hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                            val result = chain.proceed()
                            try {
                                (chain.getArg(0) as? StatusBarNotification)?.let { onPosted(it) }
                            } catch (t: Throwable) {
                                Lg.e(TAG, "seedling-notif: posted extract failed", t)
                            }
                            result
                        }
                        hooked++
                    }
                    "onNotificationRemoved" -> {
                        api.hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                            val result = chain.proceed()
                            try {
                                (chain.getArg(0) as? StatusBarNotification)?.let { onRemoved(it) }
                            } catch (t: Throwable) {
                                Lg.e(TAG, "seedling-notif: removed extract failed", t)
                            }
                            result
                        }
                        hooked++
                    }
                }
            }
            if (hooked > 0) {
                mounted = true
                Lg.i(TAG, "seedling-notif: mounted $hooked methods on $CLASS_NOTIFICATION_LISTENER")
            } else {
                Lg.w(TAG, "seedling-notif: no posted/removed methods found")
            }
        } catch (t: Throwable) {
            Lg.w(TAG, "seedling-notif: mount skipped (${t.javaClass.simpleName}: ${t.message})")
        }
    }

    private fun onPosted(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        val pkg = sbn.packageName ?: return
        val key = sbn.key ?: return
        if (!SeedlingNotifPolicy.isOngoingAllowed(n.flags, ongoingWhitelisted = false)) return
        val extras = n.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        val category = n.category
        val callType = extras?.getInt(Notification.EXTRA_CALL_TYPE, 0) ?: 0
        val isMediaStyle = n.extras?.getString(Notification.EXTRA_TEMPLATE)?.let {
            it.contains("MediaStyle")
        } ?: false
        // 媒体通知让位 MediaSession 直读（防双源打架）
        if (isMediaStyle) return
        val code = SeedlingCodeExtractor.extract(title, text, bigText)
        val messages = extractMessages(n)
        val kind = SeedlingNotifPolicy.kindOf(pkg, category, callType, messages.isNotEmpty(), isMediaStyle, code != null)
        if (kind == SeedlingKind.UNKNOWN) return
        val latest = SeedlingNotifPolicy.latestFromOther(
            messages,
            selfKey = extras?.getParcelable(Notification.EXTRA_MESSAGING_PERSON, android.app.Person::class.java)?.key,
            selfName = extras?.getParcelable(Notification.EXTRA_MESSAGING_PERSON, android.app.Person::class.java)?.name?.toString(),
        )
        val callPersonName = extras?.getParcelable(Notification.EXTRA_CALL_PERSON, android.app.Person::class.java)?.name?.toString()
        val label = when {
            code != null -> "验证码 $code"
            kind == SeedlingKind.MESSAGE && latest != null -> "${latest.senderName}: ${latest.text}"
            kind == SeedlingKind.CALL -> com.coloros16.liquidglass.engine.SeedlingNotifPolicy.callLabel(callType, callPersonName, title, text)
            text.isNotEmpty() && title.isNotEmpty() -> "$title · $text"
            else -> title.ifEmpty { text }
        }
        if (label.isEmpty()) return
        val activityId = "notif:$key"
        val actions = mutableListOf<SeedlingAction>()
        val intents = LinkedHashMap<String, PendingIntent>()
        n.actions?.forEachIndexed { i, a ->
            val pi = a.actionIntent ?: return@forEachIndexed
            if (a.remoteInputs != null && a.remoteInputs.isNotEmpty()) return@forEachIndexed
            val sem = SeedlingNotifPolicy.actionSemantic(a.title?.toString())
            val id = "notif_action_$i"
            actions += SeedlingAction(id, a.title?.toString() ?: "操作", SeedlingNotifPolicy.symbolFor(sem))
            intents[id] = pi
        }
        val now = SystemClock.elapsedRealtime()
        // 计时通知走时：chronometer 基准（墙钟 when）→ elapsed 基准；倒计时 endAt / 正计时 base
        val showChronometer = extras?.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false) ?: false
        val chronometerCountDown = extras?.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false) ?: false
        val nowWall = System.currentTimeMillis()
        val countdownEnd = if (kind == SeedlingKind.TIMER && showChronometer && chronometerCountDown) {
            com.coloros16.liquidglass.engine.SeedlingNotifTimePolicy.countdownEndElapsed(n.`when`, nowWall, now)
        } else 0L
        val countUpBase = if (kind == SeedlingKind.TIMER && showChronometer && !chronometerCountDown) {
            com.coloros16.liquidglass.engine.SeedlingNotifTimePolicy.countUpBaseElapsed(n.`when`, nowWall, now)
        } else 0L
        // 通知进度条（外卖/打车等 LIVE_UPDATE 带 progress）
        val notifProgress = com.coloros16.liquidglass.engine.SeedlingNotifTimePolicy.progressFraction(
            extras?.getInt(Notification.EXTRA_PROGRESS, 0) ?: 0,
            extras?.getInt(Notification.EXTRA_PROGRESS_MAX, 0) ?: 0,
            extras?.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false) ?: false,
        )
        val timerRinging = kind == SeedlingKind.TIMER &&
            com.coloros16.liquidglass.engine.SeedlingNotifPolicy.isTimerRinging(n.fullScreenIntent != null, n.flags)
        val activity = SeedlingActivity(
            id = activityId,
            source = pkg,
            kind = kind,
            priority = if (kind == SeedlingKind.TIMER) {
                com.coloros16.liquidglass.engine.SeedlingNotifPolicy.timerPriority(timerRinging)
            } else kind.defaultPriority,
            startedAtElapsedMs = if (countUpBase > 0L) countUpBase else now,
            updatedAtElapsedMs = now,
            demoteAfterMs = if (kind == SeedlingKind.MESSAGE) MESSAGE_DEMOTE_MS else null,
            compact = SeedlingCompact(
                leading = SeedlingElement.Symbol(symbolOf(kind)),
                label = label,
                contentId = "$activityId:$label",
            ),
            expanded = SeedlingExpanded(
                template = templateOf(kind),
                title = if (code != null) "验证码" else title.ifEmpty { pkg },
                subtitle = if (code != null) code else if (kind == SeedlingKind.MESSAGE && latest != null) latest.text else text,
                body = bigText,
                countdownEndAtElapsedMs = countdownEnd,
                progressFraction = notifProgress,
                actions = actions,
            ),
        )
        postedKeys[key] = activityId
        if (intents.isNotEmpty()) actionIntents[activityId] = intents
        n.contentIntent?.let { contentIntents[activityId] = it }
        Lg.i(TAG, "seedling-notif: posted key=$key pkg=$pkg kind=$kind label=$label actions=${actions.size}")
        onActivity?.invoke(activity, "notif")
    }

    private fun onRemoved(sbn: StatusBarNotification) {
        val key = sbn.key ?: return
        val activityId = postedKeys.remove(key) ?: return
        actionIntents.remove(activityId)
        contentIntents.remove(activityId)
        Lg.i(TAG, "seedling-notif: removed key=$key")
        onRemove?.invoke(activityId)
    }

    /** 通知按钮回传：PendingIntent.send（无 RemoteInput 的普通动作）。 */
    fun dispatchAction(activityId: String, actionId: String) {
        val pi = actionIntents[activityId]?.get(actionId)
        if (pi == null) {
            Lg.i(TAG, "seedling-notif: action absent activity=$activityId action=$actionId")
            return
        }
        try {
            pi.send()
            Lg.i(TAG, "seedling-notif: action sent activity=$activityId action=$actionId")
        } catch (t: Throwable) {
            Lg.e(TAG, "seedling-notif: action send failed", t)
        }
    }

    fun ownsActivity(activityId: String): Boolean = activityId.startsWith("notif:")

    /** 打开通知来源：contentIntent.send（点通知本体同路径）；无意图返回 false 由调用方回落包名启动。 */
    fun openContentIntent(activityId: String): Boolean {
        val pi = contentIntents[activityId] ?: return false
        return try {
            pi.send()
            Lg.i(TAG, "seedling-notif: content intent sent activity=$activityId")
            true
        } catch (t: Throwable) {
            Lg.w(TAG, "seedling-notif: content intent send failed (${t.message})")
            false
        }
    }

    private fun extractMessages(n: Notification): List<SeedlingNotifPolicy.MsgCandidate> {
        return try {
            val arr = n.extras?.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return emptyList()
            val msgs = Notification.MessagingStyle.Message.getMessagesFromBundleArray(arr)
            msgs.mapNotNull { m ->
                val sender = m.senderPerson
                SeedlingNotifPolicy.MsgCandidate(
                    senderKey = sender?.key,
                    senderName = sender?.name?.toString(),
                    text = m.text?.toString() ?: "",
                    timestampMs = m.timestamp,
                )
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    private fun symbolOf(kind: SeedlingKind): String = when (kind) {
        SeedlingKind.CALL -> "PHONE"
        SeedlingKind.TIMER -> "TIMER"
        SeedlingKind.RECORDING -> "MIC"
        SeedlingKind.MESSAGE -> "MESSAGE"
        SeedlingKind.CODE -> "LOCK_OPEN"
        SeedlingKind.LIVE_UPDATE -> "NAVIGATION"
        else -> "INFO"
    }

    private fun templateOf(kind: SeedlingKind): SeedlingTemplate = when (kind) {
        SeedlingKind.CALL -> SeedlingTemplate.CALL
        SeedlingKind.TIMER -> SeedlingTemplate.TIMER
        SeedlingKind.MESSAGE -> SeedlingTemplate.MESSAGE
        SeedlingKind.LIVE_UPDATE -> SeedlingTemplate.PROGRESS
        else -> SeedlingTemplate.GENERIC
    }

    fun shutdown() {
        postedKeys.clear()
        actionIntents.clear()
        contentIntents.clear()
        mounted = false
    }
}
