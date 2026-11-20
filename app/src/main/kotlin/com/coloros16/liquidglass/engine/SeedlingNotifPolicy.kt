package com.coloros16.liquidglass.engine

/**
 * 通知上岛分类与动作词表（纯 JVM）：kind 判定优先级、通知动作语义识别、
 * MessagingStyle 最新非本人消息选择。包名/类别/动作标题为输入，不碰 Android。
 */
object SeedlingNotifPolicy {

    private val ALARM_PKGS = setOf(
        "com.coloros.alarmclock", "com.oplus.alarmclock",
        "com.android.deskclock", "com.google.android.deskclock",
    )
    private val RECORDER_PKGS = setOf("com.coloros.soundrecorder", "com.oplus.soundrecorder")
    private val SCREENREC_PKGS = setOf("com.coloros.screenrecorder", "com.oplus.screenrecorder")
    private val LIVE_UPDATE_PKGS = setOf(
        "com.sankuai.meituan", "me.ele", "com.sdu.didi.psnger", "com.taobao.taobao",
        "com.jingdong.app.mall", "com.cainiao.wireless", "com.autonavi.minimap",
        "com.baidu.BaiduMap", "com.tencent.map", "com.eg.android.AlipayGphone",
    )

    /** kind 判定：通话 > 验证码 > 计时 > 录音 > 消息 > 媒体 > 实时进度 > UNKNOWN（参考分类优先级）。 */
    fun kindOf(
        pkg: String,
        category: String?,
        callType: Int,
        hasMessages: Boolean,
        isMediaStyle: Boolean,
        codeExtracted: Boolean,
    ): SeedlingKind {
        val p = pkg.lowercase()
        val c = category?.lowercase() ?: ""
        return when {
            callType > 0 || c == "call" || "incallui" in p || "telecom" in p -> SeedlingKind.CALL
            codeExtracted -> SeedlingKind.CODE
            p in ALARM_PKGS || c == "alarm" || "timer" in c -> SeedlingKind.TIMER
            p in RECORDER_PKGS || p in SCREENREC_PKGS || "recording" in c -> SeedlingKind.RECORDING
            hasMessages || c == "msg" || "mms" in p || "sms" in p -> SeedlingKind.MESSAGE
            isMediaStyle || c == "transport" -> SeedlingKind.MEDIA
            p in LIVE_UPDATE_PKGS || c == "progress" || c == "status" || c == "recommendation" ||
                "delivery" in c || "taxi" in c || "navigation" in c -> SeedlingKind.LIVE_UPDATE
            else -> SeedlingKind.UNKNOWN
        }
    }

    /** 通知动作语义（词表照参考 q.java:41-55）。 */
    enum class ActionSemantic { ANSWER, DECLINE, HANGUP, SPEAKER, PAUSE, STOP, RESUME, SNOOZE, REPLY, OTHER }

    fun actionSemantic(title: String?): ActionSemantic {
        if (title.isNullOrBlank()) return ActionSemantic.OTHER
        val t = title.trim()
        val low = t.lowercase()
        return when {
            t.contains("接听") || t.contains("接通") || low == "answer" || low == "accept" -> ActionSemantic.ANSWER
            t.contains("拒绝") || t.contains("拒接") || low == "decline" || low == "reject" -> ActionSemantic.DECLINE
            t.contains("挂断") || t.contains("结束") || Regex("hang\\s*up").containsMatchIn(low) || low == "end" -> ActionSemantic.HANGUP
            t.contains("免提") || t.contains("扬声器") || Regex("speaker(phone)?").containsMatchIn(low) || Regex("hands-?free").containsMatchIn(low) -> ActionSemantic.SPEAKER
            t.contains("稍后提醒") || t.contains("再响") || t.contains("贪睡") || low == "snooze" -> ActionSemantic.SNOOZE
            t.contains("继续") || t.contains("恢复") || low == "resume" || low == "continue" -> ActionSemantic.RESUME
            t.contains("暂停") || low == "pause" -> ActionSemantic.PAUSE
            t.contains("停止") || t.contains("取消") || t.contains("关闭") || low == "stop" || low == "cancel" || low == "dismiss" -> ActionSemantic.STOP
            t.contains("回复") || low == "reply" -> ActionSemantic.REPLY
            else -> ActionSemantic.OTHER
        }
    }

    /** 动作 → 岛按钮符号。 */
    fun symbolFor(s: ActionSemantic): String = when (s) {
        ActionSemantic.ANSWER -> "PHONE"
        ActionSemantic.DECLINE, ActionSemantic.HANGUP -> "PHONE_END"
        ActionSemantic.SPEAKER -> "SPEAKER"
        ActionSemantic.PAUSE -> "PAUSE"
        ActionSemantic.STOP -> "CLOSE"
        ActionSemantic.RESUME -> "PLAY"
        ActionSemantic.SNOOZE -> "TIMER"
        ActionSemantic.REPLY -> "MESSAGE"
        ActionSemantic.OTHER -> "INFO"
    }

    /**
     * MessagingStyle 最新非本人消息：倒序找发送者非本人（key 优先，name 兜底）且文本非空，
     * 多条取时间戳最新。输入=(senderKey, senderName, text, timestamp, isSelf)。
     */
    data class MsgCandidate(
        val senderKey: String?,
        val senderName: String?,
        val text: String,
        val timestampMs: Long,
    )

    fun latestFromOther(
        messages: List<MsgCandidate>,
        selfKey: String?,
        selfName: String?,
    ): MsgCandidate? {
        val others = messages.filter { m ->
            if (m.text.isBlank()) return@filter false
            val name = m.senderName
            if (name.isNullOrBlank()) return@filter false
            val sameKey = !selfKey.isNullOrEmpty() && m.senderKey == selfKey
            val sameName = !selfName.isNullOrBlank() && name == selfName
            !sameKey && !sameName
        }
        return others.maxByOrNull { it.timestampMs }
    }

    /** 通知是否常驻类（ongoing 白名单外默认不上岛，防通知刷屏）。 */
    fun isOngoingAllowed(flags: Int, ongoingWhitelisted: Boolean): Boolean {
        val ongoing = flags and 0x2 != 0
        return !ongoing || ongoingWhitelisted
    }

    /**
     * TIMER 响铃判定（priority=85 不可划掉，参考 u.java:588）：
     * 闹钟/计时器通知带 fullScreenIntent（响铃全屏意图）或 FLAG_INSISTENT（持续响铃）= 响铃中。
     */
    fun isTimerRinging(hasFullScreenIntent: Boolean, flags: Int): Boolean =
        hasFullScreenIntent || (flags and 0x4 != 0)

    /** TIMER 活动优先级：响铃 85，否则默认 60。 */
    fun timerPriority(ringing: Boolean): Int = if (ringing) 85 else SeedlingKind.TIMER.defaultPriority

    /**
     * 通话胶囊文案（参考 §2.3 callType/callPersonName）：
     * 来电=「来电 人名」，通话中=「通话中 人名」，筛选中=「来电筛选 人名」；人名空回落标题/正文。
     * @param callType Notification.CallStyle.CALL_TYPE_*（1来电/2通话中/3筛选）
     */
    fun callLabel(callType: Int, personName: String?, title: String, text: String): String {
        val name = personName?.takeIf { it.isNotBlank() } ?: title.ifEmpty { text }
        return when (callType) {
            1 -> "来电 $name".trim()
            2 -> "通话中 $name".trim()
            3 -> "来电筛选 $name".trim()
            else -> name
        }
    }
}
