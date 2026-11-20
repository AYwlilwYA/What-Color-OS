package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SeedlingKind
import com.coloros16.liquidglass.engine.SeedlingNotifPolicy
import com.coloros16.liquidglass.engine.SeedlingNotifPolicy.ActionSemantic
import com.coloros16.liquidglass.engine.SeedlingNotifPolicy.MsgCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 通知上岛分类/动作词表/消息选择契约，含阳性对照。 */
class SeedlingNotifPolicyContractTest {

    // ---------- kind 判定优先级 ----------

    @Test
    fun callWinsOverEverything() {
        assertEquals(
            SeedlingKind.CALL,
            SeedlingNotifPolicy.kindOf("com.android.mms", "msg", callType = 1, hasMessages = true, isMediaStyle = false, codeExtracted = true),
        )
    }

    @Test
    fun codeWinsOverTimer() {
        assertEquals(
            SeedlingKind.CODE,
            SeedlingNotifPolicy.kindOf("com.android.mms", null, 0, false, false, codeExtracted = true),
        )
    }

    @Test
    fun alarmPkgAndCategory() {
        assertEquals(SeedlingKind.TIMER, SeedlingNotifPolicy.kindOf("com.coloros.alarmclock", null, 0, false, false, false))
        assertEquals(SeedlingKind.TIMER, SeedlingNotifPolicy.kindOf("com.google.android.deskclock", null, 0, false, false, false))
        assertEquals(SeedlingKind.TIMER, SeedlingNotifPolicy.kindOf("x.y", "alarm", 0, false, false, false))
    }

    @Test
    fun recorderPkgs() {
        assertEquals(SeedlingKind.RECORDING, SeedlingNotifPolicy.kindOf("com.coloros.soundrecorder", null, 0, false, false, false))
        assertEquals(SeedlingKind.RECORDING, SeedlingNotifPolicy.kindOf("com.oplus.screenrecorder", null, 0, false, false, false))
    }

    @Test
    fun messageByStyleOrCategory() {
        assertEquals(SeedlingKind.MESSAGE, SeedlingNotifPolicy.kindOf("com.tencent.mm", null, 0, hasMessages = true, false, false))
        assertEquals(SeedlingKind.MESSAGE, SeedlingNotifPolicy.kindOf("x", "msg", 0, false, false, false))
    }

    @Test
    fun mediaStyleDetected() {
        assertEquals(SeedlingKind.MEDIA, SeedlingNotifPolicy.kindOf("com.music", "transport", 0, false, true, false))
    }

    @Test
    fun liveUpdateByCategory() {
        assertEquals(SeedlingKind.LIVE_UPDATE, SeedlingNotifPolicy.kindOf("x.y", "progress", 0, false, false, false))
        assertEquals(SeedlingKind.LIVE_UPDATE, SeedlingNotifPolicy.kindOf("x.y", "status", 0, false, false, false))
        assertEquals(SeedlingKind.LIVE_UPDATE, SeedlingNotifPolicy.kindOf("x.y", "recommendation", 0, false, false, false))
    }

    @Test
    fun liveUpdateByDeliveryPkg() {
        assertEquals(SeedlingKind.LIVE_UPDATE, SeedlingNotifPolicy.kindOf("com.sankuai.meituan", null, 0, false, false, false))
        assertEquals(SeedlingKind.LIVE_UPDATE, SeedlingNotifPolicy.kindOf("me.ele", null, 0, false, false, false))
        assertEquals(SeedlingKind.LIVE_UPDATE, SeedlingNotifPolicy.kindOf("com.sdu.didi.psnger", null, 0, false, false, false))
        assertEquals(SeedlingKind.LIVE_UPDATE, SeedlingNotifPolicy.kindOf("com.cainiao.wireless", null, 0, false, false, false))
    }

    @Test
    fun liveUpdateBelowMessage_positiveControl() {
        assertEquals("阳性对照：外卖应用的消息通知仍归消息", SeedlingKind.MESSAGE, SeedlingNotifPolicy.kindOf("com.sankuai.meituan", "msg", 0, true, false, false))
        assertEquals("阳性对照：外卖应用的验证码仍归验证码", SeedlingKind.CODE, SeedlingNotifPolicy.kindOf("me.ele", null, 0, false, false, codeExtracted = true))
    }

    @Test
    fun unknownFallback_positiveControl() {
        assertEquals("阳性对照：普通通知不上岛", SeedlingKind.UNKNOWN, SeedlingNotifPolicy.kindOf("com.android.settings", null, 0, false, false, false))
    }

    // ---------- 动作词表 ----------

    @Test
    fun callActionWords() {
        assertEquals(ActionSemantic.ANSWER, SeedlingNotifPolicy.actionSemantic("接听"))
        assertEquals(ActionSemantic.ANSWER, SeedlingNotifPolicy.actionSemantic("Accept"))
        assertEquals(ActionSemantic.DECLINE, SeedlingNotifPolicy.actionSemantic("拒接"))
        assertEquals(ActionSemantic.HANGUP, SeedlingNotifPolicy.actionSemantic("挂断"))
        assertEquals(ActionSemantic.HANGUP, SeedlingNotifPolicy.actionSemantic("Hang up"))
        assertEquals(ActionSemantic.SPEAKER, SeedlingNotifPolicy.actionSemantic("免提"))
        assertEquals(ActionSemantic.SPEAKER, SeedlingNotifPolicy.actionSemantic("speakerphone"))
    }

    @Test
    fun timerActionWords() {
        assertEquals(ActionSemantic.PAUSE, SeedlingNotifPolicy.actionSemantic("暂停"))
        assertEquals(ActionSemantic.STOP, SeedlingNotifPolicy.actionSemantic("停止"))
        assertEquals(ActionSemantic.STOP, SeedlingNotifPolicy.actionSemantic("Cancel"))
        assertEquals(ActionSemantic.RESUME, SeedlingNotifPolicy.actionSemantic("继续"))
        assertEquals(ActionSemantic.SNOOZE, SeedlingNotifPolicy.actionSemantic("稍后提醒"))
        assertEquals(ActionSemantic.SNOOZE, SeedlingNotifPolicy.actionSemantic("snooze"))
    }

    @Test
    fun replyAndOther() {
        assertEquals(ActionSemantic.REPLY, SeedlingNotifPolicy.actionSemantic("回复"))
        assertEquals(ActionSemantic.OTHER, SeedlingNotifPolicy.actionSemantic("查看详情"))
        assertEquals(ActionSemantic.OTHER, SeedlingNotifPolicy.actionSemantic(null))
        assertEquals(ActionSemantic.OTHER, SeedlingNotifPolicy.actionSemantic(""))
    }

    @Test
    fun declineBeforeHangup_positiveControl() {
        // 「拒绝」不能落进 HANGUP（词表顺序敏感）
        assertEquals(ActionSemantic.DECLINE, SeedlingNotifPolicy.actionSemantic("拒绝"))
    }

    // ---------- MessagingStyle 最新非本人 ----------

    private fun msg(name: String, text: String, ts: Long, key: String? = null) =
        MsgCandidate(key, name, text, ts)

    @Test
    fun latestOtherByTimestamp() {
        val list = listOf(
            msg("我", "在吗", 100L, key = "self"),
            msg("张三", "早", 200L),
            msg("张三", "开会了", 300L),
        )
        val r = SeedlingNotifPolicy.latestFromOther(list, selfKey = "self", selfName = "我")
        assertEquals("开会了", r?.text)
    }

    @Test
    fun selfExcludedByKeyAndName() {
        val list = listOf(msg("我", "自己发的", 900L, key = "self"), msg("张三", "对方", 100L))
        assertEquals("对方", SeedlingNotifPolicy.latestFromOther(list, "self", "我")?.text)
        val nameOnly = listOf(msg("我", "自己发的", 900L), msg("张三", "对方", 100L))
        assertEquals("name 兜底也排除本人", "对方", SeedlingNotifPolicy.latestFromOther(nameOnly, null, "我")?.text)
    }

    @Test
    fun allSelfReturnsNull_positiveControl() {
        val list = listOf(msg("我", "a", 1L, key = "self"), msg("我", "b", 2L, key = "self"))
        assertNull("阳性对照：全是本人消息不提取", SeedlingNotifPolicy.latestFromOther(list, "self", "我"))
    }

    @Test
    fun blankTextSkipped() {
        val list = listOf(msg("张三", "  ", 900L), msg("张三", "有内容", 100L))
        assertEquals("有内容", SeedlingNotifPolicy.latestFromOther(list, null, null)?.text)
    }

    // ---------- ongoing 过滤 ----------

    @Test
    fun ongoingBlockedByDefault() {
        assertFalse(SeedlingNotifPolicy.isOngoingAllowed(0x2, ongoingWhitelisted = false))
        assertTrue(SeedlingNotifPolicy.isOngoingAllowed(0x2, ongoingWhitelisted = true))
        assertTrue("非 ongoing 照常", SeedlingNotifPolicy.isOngoingAllowed(0x0, false))
    }

    // ---------- TIMER 响铃判定 ----------

    @Test
    fun timerRingingByFullScreenIntent() {
        assertTrue("响铃全屏意图=响铃", SeedlingNotifPolicy.isTimerRinging(hasFullScreenIntent = true, flags = 0))
    }

    @Test
    fun timerRingingByInsistentFlag() {
        assertTrue("FLAG_INSISTENT 持续响铃=响铃", SeedlingNotifPolicy.isTimerRinging(false, flags = 0x4))
    }

    @Test
    fun timerNotRinging_positiveControl() {
        assertFalse("阳性对照：普通计时通知非响铃", SeedlingNotifPolicy.isTimerRinging(false, flags = 0))
        assertFalse("阳性对照：ongoing 非 insistent 不算响铃", SeedlingNotifPolicy.isTimerRinging(false, flags = 0x2))
    }

    @Test
    fun timerPriorityRinging85() {
        assertEquals("响铃 priority 85 拒划", 85, SeedlingNotifPolicy.timerPriority(ringing = true))
        assertEquals("非响铃默认 60", 60, SeedlingNotifPolicy.timerPriority(ringing = false))
    }

    // ---------- 通话文案 ----------

    @Test
    fun callLabelByType() {
        assertEquals("来电 张三", SeedlingNotifPolicy.callLabel(1, "张三", "标题", "正文"))
        assertEquals("通话中 张三", SeedlingNotifPolicy.callLabel(2, "张三", "标题", "正文"))
        assertEquals("来电筛选 张三", SeedlingNotifPolicy.callLabel(3, "张三", "标题", "正文"))
    }

    @Test
    fun callLabelFallback_positiveControl() {
        assertEquals("阳性对照：人名空回落标题", "来电 标题", SeedlingNotifPolicy.callLabel(1, null, "标题", "正文"))
        assertEquals("阳性对照：人名空白回落标题", "通话中 标题", SeedlingNotifPolicy.callLabel(2, "  ", "标题", "正文"))
        assertEquals("阳性对照：标题空回落正文", "来电 正文", SeedlingNotifPolicy.callLabel(1, null, "", "正文"))
        assertEquals("未知类型直接用名", "李四", SeedlingNotifPolicy.callLabel(0, "李四", "标题", "正文"))
    }
}
