package com.coloros16.liquidglass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 转场诊断通道契约：按帧日志必须「按键分别节流」且「只在边内放开」。
 *
 * 治过的缺陷：`logFast` 曾用单个全局时间戳，两个 key 互相顶掉
 * （`stage-frame-fast` 只剩端点帧、`stage-clock-anchors` 独占配额）。
 */
class AodDiagnosticChannelContractTest {

    private fun stage(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/hook/aod/AodStageHook.kt",
    ).readText()

    private fun world(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/hook/aod/AodWorldLayer.kt",
    ).readText()

    private fun body(text: String, name: String): String {
        val at = text.indexOf("fun $name(")
        if (at < 0) return ""
        var depth = 0
        var started = false
        val sb = StringBuilder()
        var i = at
        while (i < text.length) {
            val c = text[i]
            if (c == '{') {
                depth++
                started = true
            } else if (c == '}') {
                depth--
                if (started && depth == 0) break
            }
            if (started) sb.append(c)
            i++
        }
        return sb.toString()
    }

    @Test
    fun fastThrottleIsKeyedNotShared() {
        val src = stage()
        assertTrue(
            "logFast 的节流必须按键分别计时（共用单个时间戳会让两个 key 互相顶掉）",
            src.contains("HashMap<String, Long>()") && src.contains("fastAt[key]"),
        )
        assertFalse(
            "禁止退回全局单值节流",
            src.contains("private var fastAt = 0L"),
        )
    }

    @Test
    fun stageFastLogOnlyInsidePlayback() {
        val b = body(stage(), "logFast")
        assertTrue("logFast 必须是节流函数而不是空壳", b.contains("Lg.i(TAG"))
        val play = body(stage(), "pump")
        assertTrue("按帧日志只允许出现在播放路径里", play.contains("logFast("))
    }

    @Test
    fun reattachFailureBranchesAreLogged() {
        val b = body(stage(), "reattachIfDetached")
        assertTrue("无舞台记录必须留痕", b.contains("stage-reattach-nostage"))
        assertTrue("舞台已下屏必须留痕", b.contains("stage-reattach-stagegone"))
    }

    @Test
    fun lockRootLookupIsLogged() {
        val b = body(stage(), "findLockRoot")
        assertTrue("id 解析失败必须留痕", b.contains("stage-lockroot-noid"))
        assertTrue("取不到容器必须留痕", b.contains("stage-lockroot-miss"))
        assertTrue("取到了必须打出类名与子节点数", b.contains("stage-lockroot\""))
    }

    /**
     * 脚本正则必须拿**真实日志格式**跑一遍，不能只断言源码里有这串。
     *
     * `logThrottled` 的拼接是 `"$key: $msg"`，真实行是 `world-blurclear: edge=…`；
     * 断言「源码里有个不带冒号的正则」会让契约把缺陷钉死。
     */
    @Test
    fun blurClearSummaryRegexMatchesRealLogFormat() {
        val src = File("../scripts/aod_142_verify.py").readText()
        val hook = File("src/main/kotlin/com/coloros16/liquidglass/hook/aod/AodStageHook.kt").readText()
        assertTrue("只报数不下判", src.contains("BLURCLEAR real="))
        assertTrue(
            "必须给第二判据打分段标记（否则段切不出来）",
            src.contains("SEG LOCK_TO_HOME") && src.contains("SEG END"),
        )
        assertTrue("必须能离线自检", src.contains("--self-test") && src.contains("SELFTEST %d/%d"))
        assertTrue("判分逻辑必须可复用（自检与真跑同一份）", src.contains("def verdict("))
        assertTrue(
            "混合态那行必须按单参数格式化（MIXED 是元组，裸 % 会 TypeError）",
            src.contains("(MIXED,)"),
        )
        assertTrue("必须报锁屏内容层归零时刻（AOD-146 原料）", src.contains("LOCKALPHA n=%d"))
        assertTrue("必须报分段标记条数（零 SEG 自证）", src.contains("seg=%d(期望2)"))
        assertTrue("帧行必须有 lk= 字段", hook.contains("lk=\${"))
        val tail = hook.substringAfter("private fun noteClockTail(").substringBefore("private fun clockTailPending(")
        assertTrue("补尾账本必须用与求值器同一边长", tail.contains("stageEdgeMs(edge)"))
        assertFalse("不得另算一套边长", tail.contains("edgeDurationMs(edge)"))

        val blur = pattern(src, "BLUR")
        val real = "11-20 06:40:18.214 I/LgAodWorld(13088): " +
            "world-blurclear: edge=LOCK_TO_HOME t=0.312 a=0.845 zoom=1.000"
        val hit = Regex(blur).find(real)
        assertTrue("BLUR 必须匹配真实行（$blur）", hit != null)
        assertEquals("LOCK_TO_HOME", hit!!.groupValues[1])
        assertEquals("0.312", hit.groupValues[2])
        assertTrue(
            "阳性对照：无冒号写法必须匹配不上真实行（证明本条契约抓得住原 bug）",
            Regex("world-blurclear edge=([A-Za-z_]+)").find(real) == null,
        )

        val off = pattern(src, "BLUROFF")
        val offLine = "11-20 06:40:18.214 I/LgAodWorld(13088): " +
            "world-blurclear-off: 轻模糊不参与 edge=LOCK_TO_AOD a=0.000"
        val offHit = Regex(off).find(offLine)
        assertTrue("BLUROFF 必须匹配真实行（$off）", offHit != null)
        assertEquals("LOCK_TO_AOD", offHit!!.groupValues[1])

        assertTrue("BLUR 不得吃掉 -off 行", Regex(blur).find(offLine) == null)
        assertTrue("BLUROFF 不得吃掉真实行", Regex(off).find(real) == null)
    }

    /** 取脚本里 `NAME = re.compile(r"…")` 的模式串。 */
    private fun pattern(src: String, name: String): String {
        val head = name + " = re.compile(r\""
        val at = src.indexOf(head)
        assertTrue("取不到 " + name + " 的正则", at >= 0)
        val start = at + head.length
        val end = src.indexOf('"', start)
        assertTrue("正则未闭合", end > start)
        return src.substring(start, end)
    }

    @Test
    fun handoffSupersedeIsLogged() {
        val src = File(
            "src/main/kotlin/com/coloros16/liquidglass/hook/AodHandoffHook.kt",
        ).readText()
        val b = body(src, "noteSupersede")
        assertTrue("必须有顶边诊断函数", b.isNotEmpty())
        assertTrue("必须判旧边仍在播放", b.contains("st.playing"))
        assertTrue("同边不得重复打点", b.contains("old == edge"))
        assertTrue("必须打出旧边剩余进度与来源", b.contains("tOld=") && b.contains("src="))
        val feed = body(src, "feedBridge")
        assertTrue("取到 feedBridge", feed.isNotEmpty())
        val noteAt = feed.indexOf("noteSupersede(")
        val writeAt = feed.indexOf("bridge.onSystemProgress(")
        assertTrue("必须先记账再写进度", noteAt >= 0 && writeAt > noteAt)
    }

    @Test
    fun worldLensFrameIsEdgeGated() {
        val b = body(world(), "logLensFrame")
        assertTrue("静止态必须回落到 2s 节流", b.contains("logThrottled(\"world-lens\""))
        assertTrue("边内才走低节流分支", b.contains("if (t < 1f)"))
        assertTrue("落位帧不受节流，否则端点会被吞", b.contains("fastDoneEdge"))
        val draw = body(world(), "drawLens")
        assertTrue("drawLens 必须经由按帧通道输出", draw.contains("logLensFrame("))
        assertFalse("drawLens 不得绕过通道直接节流打点", draw.contains("logThrottled(\n            \"world-lens\""))
    }

    /**
     * ★`playbackEdgeMs` 是「本次播放」的账本，拖动开始必须清零。
     *
     * 反面实锤（第149轮）：它由 `clock.play` 同处写入后从不复位。播放 3840ms 的
     * `LOCK_TO_HOME` 之后去拖 `LOCK_TO_AOD`（实际 650ms），求值仍按 3840 归一化
     * ⇒ 曲线被当成慢动作拉伸 5.9 倍。拖动时边由 `scrubP` 决定，本来就不该沿用上一轮的边长。
     */
    @Test
    fun scrubStartClearsPlaybackEdgeMs() {
        val src = java.io.File("../app/src/main/kotlin/com/coloros16/liquidglass/ui/aod/AodDisplayController.kt")
            .readText()
        val body = src.substringAfter("override fun onScrubStart()").substringBefore("override fun onScrubMove")
        assertTrue("拖动开始必须复位播放边长", body.contains("preview.playbackEdgeMs = 0L"))
        assertTrue("复位必须在 scrub = true 之后（玩家已进入拖动）", body.indexOf("scrub = true") < body.indexOf("playbackEdgeMs = 0L"))
        // 阳性对照：不清零时，3840 的账本会把 650 的边拉成 5.9 倍慢动作
        val stale = 3840L
        val real = 650L
        assertTrue("沿用旧账本偏差 ${stale.toFloat() / real} 倍", stale / real.toFloat() > 5f)
    }

    @Test
    fun playbackLedgersAreResetPerEdgeAndOnHotReload() {
        val hook = stage()
        val inEdge = hook.substringAfter("if (params.t < 1f) {").substringBefore("logFast(")
        assertTrue("边内必须作废落位账本（同一条边重放时末帧仍要打）", inEdge.contains("fastDoneEdge = null"))
        // ★同族第二个账本：时钟位姿落位日志（AOD-130/131 的判据来源），同样会吞末帧
        val clockInEdge = hook.substringAfter("if (t < 1f) {").substringBefore("logFast(\"stage-clock-anchors\"")
        assertTrue("时钟落位账本也必须边内作废", clockInEdge.contains("clockDoneEdge = null"))
        val shut = hook.substringAfter("fun shutdownForHotReload(").substringBefore("private fun resolveStage(")
        for (field in listOf(
            "clockTails.clear()", "fastDoneEdge = null", "clockDoneEdge = null",
            "playEdge = null", "playEdgeMs = 0L",
        )) {
            assertTrue("热重载必须清 $field", shut.contains(field))
        }
        // 按结构模式枚举同类账本：`*DoneEdge` 新增项必须同时具备「声明」「边内作废」「热重载清零」
        val doneEdges = hook.lines().mapNotNull { line ->
            val t = line.trim()
            if (!t.startsWith("private var ") || !t.contains("DoneEdge")) null
            else t.removePrefix("private var ").substringBefore(":")
        }.distinct()
        assertTrue("必须扫到落位账本字段（结构模式）", doneEdges.size >= 2)
        for (name in doneEdges) {
            val hits = Regex(Regex.escape(name) + " = null").findAll(hook).count()
            assertTrue("$name 必须边内作废 + 热重载清零两处，实际 $hits", hits >= 2)
            assertTrue("$name 必须出现在热重载清理里", shut.contains("$name = null"))
            // hits>=2 + shut 命中，仍可能是「两处都写在热重载里」⇒ 必须证明至少一处紧邻「边内」分支。
            // 没有这一条，把复位从边内挪走、在别处再补一行，契约照样绿。
            val at = Regex(Regex.escape(name) + " = null").findAll(hook).map { it.range.first }.toList()
            assertTrue(
                "$name 至少要有一处复位紧跟在「边内」分支（< 1f）之后，实际位置 $at",
                at.any { p -> hook.substring(maxOf(0, p - 400), p).contains("< 1f") },
            )
        }
    }
}
