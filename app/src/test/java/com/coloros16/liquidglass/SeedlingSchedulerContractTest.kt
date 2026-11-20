package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SeedlingActivity
import com.coloros16.liquidglass.engine.SeedlingCompact
import com.coloros16.liquidglass.engine.SeedlingKind
import com.coloros16.liquidglass.engine.SeedlingScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 接管岛调度器契约：优先级择优/独占抢占/展开锁定/过期降级/排队，含阳性对照。 */
class SeedlingSchedulerContractTest {

    private fun act(id: String, kind: SeedlingKind, updated: Long = 1000L) =
        SeedlingActivity(id = id, source = "test", kind = kind, updatedAtElapsedMs = updated, compact = SeedlingCompact(label = id))

    @Test
    fun higherPriorityWinsMain() {
        val s = SeedlingScheduler()
        s.upsert(act("media", SeedlingKind.MEDIA))
        s.upsert(act("timer", SeedlingKind.TIMER))
        val l = s.layout(2000L)
        assertEquals("timer60 > media50", "timer", l.main?.id)
        assertEquals("media", l.left?.id)
    }

    @Test
    fun exclusiveCallPreemptsEverything() {
        val s = SeedlingScheduler()
        s.upsert(act("code", SeedlingKind.CODE))
        s.upsert(act("call", SeedlingKind.CALL))
        assertEquals("call", s.layout(2000L).main?.id)
    }

    @Test
    fun expandedLocksMain_positiveControl() {
        val s = SeedlingScheduler()
        s.upsert(act("media", SeedlingKind.MEDIA))
        s.upsert(act("call", SeedlingKind.CALL))
        s.expandedId = "media"
        assertEquals("展开中的媒体压过独占通话（展开锁定）", "media", s.layout(2000L).main?.id)
        s.expandedId = null
        assertEquals("阳性对照：撤展开后通话夺回主岛", "call", s.layout(2000L).main?.id)
    }

    @Test
    fun staleActivityDemoted() {
        val s = SeedlingScheduler()
        s.upsert(act("old-media", SeedlingKind.MEDIA, updated = 1000L))
        s.upsert(act("msg", SeedlingKind.MESSAGE, updated = 9000L))
        val l = s.layout(10000L, staleMs = 5000L)
        assertEquals("过期媒体降 1000 分，消息顶上", "msg", l.main?.id)
    }

    @Test
    fun samePriorityNewerWins() {
        val s = SeedlingScheduler()
        s.upsert(act("m1", SeedlingKind.MEDIA, updated = 1000L))
        s.upsert(act("m2", SeedlingKind.MEDIA, updated = 2000L))
        assertEquals("m2", s.layout(3000L).main?.id)
    }

    @Test
    fun removeAndClear() {
        val s = SeedlingScheduler()
        s.upsert(act("a", SeedlingKind.MEDIA))
        s.remove("a")
        assertTrue(s.layout(1L).isEmpty)
        s.upsert(act("b", SeedlingKind.TIMER))
        s.expandedId = "b"
        s.remove("b")
        assertNull("remove 展开中的活动应清 expandedId", s.expandedId)
        s.upsert(act("c", SeedlingKind.CALL))
        s.clear()
        assertTrue(s.layout(1L).isEmpty)
    }

    @Test
    fun queueOverflow() {
        val s = SeedlingScheduler()
        listOf("a" to SeedlingKind.CALL, "b" to SeedlingKind.CODE, "c" to SeedlingKind.TIMER, "d" to SeedlingKind.MEDIA, "e" to SeedlingKind.MESSAGE)
            .forEach { (id, k) -> s.upsert(act(id, k)) }
        val l = s.layout(2000L)
        assertEquals("a", l.main?.id)
        assertEquals("b", l.left?.id)
        assertEquals("c", l.right?.id)
        assertEquals(listOf("d", "e"), l.queued.map { it.id })
    }

    @Test
    fun kindPriorityTruth() {
        assertEquals(100, SeedlingKind.FACE_UNLOCK.defaultPriority)
        assertEquals(90, SeedlingKind.CALL.defaultPriority)
        assertEquals(80, SeedlingKind.RECORDING.defaultPriority)
        assertEquals(66, SeedlingKind.CODE.defaultPriority)
        assertEquals(60, SeedlingKind.TIMER.defaultPriority)
        assertEquals(55, SeedlingKind.LIVE_UPDATE.defaultPriority)
        assertEquals(50, SeedlingKind.MEDIA.defaultPriority)
        assertEquals(40, SeedlingKind.MESSAGE.defaultPriority)
        assertTrue(SeedlingKind.CALL.exclusive)
        assertTrue(SeedlingKind.FACE_UNLOCK.exclusive)
        assertTrue(!SeedlingKind.MEDIA.exclusive)
        assertTrue(!SeedlingKind.RECORDING.exclusive)
    }

    @Test
    fun hideRemovesFromSlotsButKeepsActivity() {
        val s = SeedlingScheduler()
        s.upsert(act("media", SeedlingKind.MEDIA))
        s.upsert(act("timer", SeedlingKind.TIMER))
        s.hide("timer")
        val l = s.layout(2000L)
        assertEquals("划掉计时后媒体顶上", "media", l.main?.id)
        assertNull(l.left)
        assertTrue(s.isHidden("timer"))
        assertEquals(1, s.hiddenCount())
    }

    @Test
    fun recallRestoresHidden() {
        val s = SeedlingScheduler()
        s.upsert(act("timer", SeedlingKind.TIMER))
        s.upsert(act("media", SeedlingKind.MEDIA))
        s.hide("timer")
        assertEquals("media", s.layout(2000L).main?.id)
        assertEquals(1, s.recallAll())
        assertEquals("召回后计时夺回主岛（60>50）", "timer", s.layout(2000L).main?.id)
        assertEquals("media", s.layout(2000L).left?.id)
        assertTrue(!s.isHidden("timer"))
    }

    @Test
    fun hiddenExpandedCleared_positiveControl() {
        val s = SeedlingScheduler()
        s.upsert(act("call", SeedlingKind.CALL))
        s.expandedId = "call"
        assertEquals("call", s.layout(2000L).main?.id)
        s.hide("call")
        assertNull("划掉展开中的活动应清展开锁", s.expandedId)
        assertTrue("阳性对照：隐藏后独占通话也不上岛", s.layout(2000L).isEmpty)
        s.recallAll()
        assertEquals("call", s.layout(2000L).main?.id)
    }

    @Test
    fun removeClearsHiddenEntry() {
        val s = SeedlingScheduler()
        s.upsert(act("a", SeedlingKind.MEDIA))
        s.hide("a")
        s.remove("a")
        assertEquals("remove 后隐藏集不留尸", 0, s.hiddenCount())
        assertEquals(0, s.recallAll())
        s.upsert(act("a", SeedlingKind.MEDIA))
        assertTrue("重新 upsert 同 id 不再被旧隐藏压住", !s.layout(2000L).isEmpty)
    }

    @Test
    fun hideUnknownIdNoOp() {
        val s = SeedlingScheduler()
        s.hide("ghost")
        assertEquals(0, s.hiddenCount())
    }

    @Test
    fun kindFilterBlocksAtLayout() {
        val s = SeedlingScheduler()
        s.upsert(act("media", SeedlingKind.MEDIA))
        s.upsert(act("msg", SeedlingKind.MESSAGE))
        val l = s.layout(2000L, kindAllowed = { it != SeedlingKind.MEDIA })
        assertEquals("媒体开关关掉后消息顶上", "msg", l.main?.id)
        assertNull(l.left)
    }

    @Test
    fun kindFilterAllOffEmpty_positiveControl() {
        val s = SeedlingScheduler()
        s.upsert(act("call", SeedlingKind.CALL))
        assertTrue("阳性对照：全类型关=岛空（过滤真生效非空转）", s.layout(2000L, kindAllowed = { false }).isEmpty)
        assertEquals("call", s.layout(2000L).main?.id)
    }

    // ---------- 消息降级 / 去重 / 拒划 / 导航保底（盘点 §1.6 真值） ----------

    @Test
    fun messageDemotesAfter6s() {
        val s = SeedlingScheduler()
        s.upsert(act("msg", SeedlingKind.MESSAGE, updated = 1000L).copy(demoteAfterMs = 6000L))
        s.upsert(act("media", SeedlingKind.MEDIA, updated = 1000L))
        assertEquals("6s 内消息不降级（40<50 媒体主岛）", "media", s.layout(5000L).main?.id)
        // 降级后消息 40 分仍低于媒体 50——用更低优先级的 live 对照降级真发生
        s.upsert(act("status", SeedlingKind.STATUS, updated = 1000L))
        val before = s.layout(6999L)
        assertEquals("media", before.main?.id)
        val after = s.layout(7000L)
        assertEquals("降级后消息 40 > status20 顶上副岛", "msg", after.left?.id ?: after.right?.id)
    }

    @Test
    fun demoteBoundaryExact_positiveControl() {
        val s = SeedlingScheduler()
        s.upsert(act("msg", SeedlingKind.MESSAGE, updated = 1L).copy(demoteAfterMs = 6000L, demotedPriority = 10))
        s.upsert(act("status", SeedlingKind.STATUS, updated = 1L))
        assertEquals("5999ms 未降级：消息 40 > status20", "msg", s.layout(6000L).main?.id)
        assertEquals("6001ms 恰好满 6000 降级：10 < 20", "status", s.layout(6001L).main?.id)
    }

    @Test
    fun sameSourceMessageDedup() {
        val s = SeedlingScheduler()
        s.upsert(act("m1", SeedlingKind.MESSAGE, updated = 1000L).copy(source = "com.tencent.mm"))
        s.upsert(act("m2", SeedlingKind.MESSAGE, updated = 2000L).copy(source = "com.tencent.mm"))
        assertNull("同来源旧消息被去重", s.get("m1"))
        assertEquals("m2", s.layout(3000L).main?.id)
        s.upsert(act("m3", SeedlingKind.MESSAGE, updated = 3000L).copy(source = "com.other.app"))
        assertEquals("别来源消息保留", "m2", s.get("m2")?.id)
        assertEquals(2, listOfNotNull(s.layout(4000L).main, s.layout(4000L).left).size)
    }

    @Test
    fun ringingTimerCannotDismiss() {
        val s = SeedlingScheduler()
        s.upsert(act("alarm", SeedlingKind.TIMER).copy(priority = 85))
        assertFalse("响铃中 TIMER85 拒划", s.hide("alarm"))
        assertTrue(!s.isHidden("alarm"))
        assertEquals("alarm", s.layout(2000L).main?.id)
    }

    @Test
    fun normalTimerCanDismiss_positiveControl() {
        val s = SeedlingScheduler()
        s.upsert(act("timer", SeedlingKind.TIMER))
        assertTrue("阳性对照：普通计时（60）可划", s.hide("timer"))
        assertTrue(s.layout(2000L).isEmpty)
    }

    @Test
    fun staleRingingTimerCanDismiss() {
        val s = SeedlingScheduler()
        s.upsert(act("alarm", SeedlingKind.TIMER, updated = 1000L).copy(priority = 85))
        assertTrue("过期响铃可划", s.hide("alarm", nowElapsedMs = 20000L, staleMs = 5000L))
    }

    @Test
    fun navigationFloor62() {
        val s = SeedlingScheduler()
        s.upsert(act("nav", SeedlingKind.LIVE_UPDATE).copy(priority = 30, navigation = true))
        s.upsert(act("timer", SeedlingKind.TIMER))
        assertEquals("导航保底 62 > 计时 60", "nav", s.layout(2000L).main?.id)
    }

    @Test
    fun removeBySourceAndPrefix() {
        val s = SeedlingScheduler()
        // 两条不同来源消息（同来源会被去重只留一条，这里测 removeBySource 本身）
        s.upsert(act("notif:a", SeedlingKind.MESSAGE).copy(source = "pkgA"))
        s.upsert(act("notif:b", SeedlingKind.MESSAGE).copy(source = "pkgB"))
        s.upsert(act("media:x", SeedlingKind.MEDIA))
        assertEquals(1, s.removeBySource("pkgA"))
        assertEquals(1, s.removeBySource("pkgB"))
        assertEquals("media:x", s.layout(2000L).main?.id)
        assertEquals(1, s.removeByIdPrefix("media:"))
        assertTrue(s.layout(2000L).isEmpty)
    }

    // ---------- 进来源应用隐藏（盘点 hideWhenSourceForeground 默认 true） ----------

    @Test
    fun foregroundSourceHidden() {
        val s = SeedlingScheduler()
        s.upsert(act("media:x", SeedlingKind.MEDIA).copy(source = "com.music"))
        s.upsert(act("timer:y", SeedlingKind.TIMER).copy(source = "com.clock"))
        s.foregroundPackage = "com.music"
        val l = s.layout(2000L)
        assertEquals("进来源应用：媒体岛隐藏，计时顶上", "timer:y", l.main?.id)
        assertNull(l.left)
    }

    @Test
    fun leavingSourceRestores_positiveControl() {
        val s = SeedlingScheduler()
        s.upsert(act("media:x", SeedlingKind.MEDIA).copy(source = "com.music"))
        s.foregroundPackage = "com.music"
        assertTrue(s.layout(2000L).isEmpty)
        s.foregroundPackage = "com.launcher"
        assertEquals("阳性对照：离开来源岛恢复", "media:x", s.layout(2000L).main?.id)
        s.foregroundPackage = null
        assertEquals("前台未知不隐藏", "media:x", s.layout(2000L).main?.id)
    }

    @Test
    fun optOutStaysVisible() {
        val s = SeedlingScheduler()
        s.upsert(act("call:x", SeedlingKind.CALL).copy(source = "com.dialer", hideWhenSourceForeground = false))
        s.foregroundPackage = "com.dialer"
        assertEquals("豁免活动进来源不隐藏（通话保持）", "call:x", s.layout(2000L).main?.id)
    }

    @Test
    fun otherSourceUnaffected() {
        val s = SeedlingScheduler()
        s.upsert(act("media:x", SeedlingKind.MEDIA).copy(source = "com.music"))
        s.foregroundPackage = "com.other"
        assertEquals("别的应用前台不影响", "media:x", s.layout(2000L).main?.id)
    }
}