package com.coloros16.liquidglass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * E2 脚本前置态契约：脚本假设错了，拿到的是「看起来合理」的假结论。
 *
 * 两条实证：短按时钟中心打开的是样式面板而不是选中；照片是持久化配置，
 * 选过之后基线判据（不得出现 photo）不再成立。
 */
class AodE2ScriptPreconditionContractTest {

    private fun script(name: String): String = File("../scripts/$name").readText()

    /** 短按走 onClockTapped（开面板），不调 select()；脚本不得拿它当选中入口。 */
    @Test
    fun stretchScriptDoesNotTapAsSelection() {
        val src = script("aod_stretch_e2.py")
        // tap 只允许出现在 --reset 分支里（打开面板找「恢复默认布局」）
        val resetBlock = src.substringAfter("if '--reset' in sys.argv:", "")
        val beforeReset = src.substringBefore("if '--reset' in sys.argv:")
        assertFalse(
            "复位分支之外不得 tap 画布当选中：$beforeReset",
            beforeReset.contains("'tap', str(cx)"),
        )
        assertTrue("复位分支内部才允许 tap 开面板", resetBlock.contains("'tap', str(cx)"))
        assertTrue("必须提供长压选中入口", src.contains("def select_by_hold("))
    }

    /** 长压才是选中入口：零位移按住超长按阈值，抬手不触发 tapped。 */
    @Test
    fun selectByHoldUsesZeroDisplacementLongPress() {
        val src = script("aod_stretch_e2.py")
        val body = src.substringAfter("def select_by_hold(").substringBefore("\ndef ")
        assertTrue("必须按住", body.contains("touch_down("))
        assertTrue("必须抬手", body.contains("touch_up("))
        assertFalse("不得产生位移，否则会被判为拖动", body.contains("touch_move("))
        val hold = Regex("time\\.sleep\\((\\d+\\.\\d+)\\)").findAll(body).map { it.groupValues[1].toDouble() }.first()
        assertTrue("按住时长 $hold 必须超过长按阈值 0.5s", hold >= 0.5)
    }

    /**
     * ★钩心必须用 clock-hit 反推，不许拿截图框 ±8 当钩心。
     *
     * 截图量到的是字形虚线框（绝对像素），而 clockBounds 是手机框局部坐标、
     * 钩还画在它 right/bottom 之外；两者差 offset（实测 227,381）+ 3.2dp。
     */
    @Test
    fun stretchScriptCalibratesHookFromLog() {
        val src = script("aod_stretch_e2.py")
        assertTrue("必须有校准函数", src.contains("def hook_screen_point("))
        assertTrue("必须读 clock-hit", src.contains("clock-hit x="))
        assertTrue("必须报钩心屏幕坐标", src.contains("hook screen point"))
        assertFalse("不得再用截图框 ±8 当钩心", src.contains("hx, hy = a[1] + 8, a[3] + 8"))
        assertTrue(
            "钩心落屏外必须判 INCONCLUSIVE",
            src.contains("钩心 x=") && src.contains("落到屏幕外"),
        )
    }

    /** 阳性对照：截图框右 897 vs 真实钩心 1223 ⇒ 差 326px，远超 72px 命中半径。 */
    @Test
    fun positiveControlScreenshotBoxIsFarFromHook() {
        val shotRight = 897
        val realRight = 996.2
        val offset = 227.1
        val hookX = offset + realRight + 10
        assertTrue("钩心 $hookX 已超出屏幕宽 1080", hookX > 1080)
        val gap = kotlin.math.abs(hookX - shotRight)
        assertTrue("差 $gap 远超命中半径 72px", gap > 72 * 3)
    }

    /** 主判据必须是画在哪报哪的日志，不是量像素比例。 */
    @Test
    fun stretchJudgesByLogNotByPixelRatio() {
        val src = script("aod_stretch_e2.py")
        assertTrue("必须读 stretch 日志", src.contains("stretch=("))
        assertTrue("宽增长按 stretchX 差判", src.contains("dsx >= 0.10"))
        assertTrue("纵向必须冻结才算非等比", src.contains("abs(sy1 - sy0) <= 0.02"))
    }

    /** 零条 stretch 日志 = 前置不成立，判 INCONCLUSIVE；不许当成功能红。 */
    @Test
    fun zeroLogIsInconclusiveNotFail() {
        val src = script("aod_stretch_e2.py")
        val branch = src.substringAfter("if not seq:").substringBefore("return 2")
        assertTrue("必须打印 INCONCLUSIVE：$branch", branch.contains("INCONCLUSIVE"))
        assertTrue("退出码 2", src.substringAfter("if not seq:").contains("return 2"))
    }

    /**
     * ★凡读 logcat 的 E2 必须先开日志闸。
     *
     * `Prefs.DEFAULT_ENABLE_LOGS=false` ⇒ 全新安装后 i 级全被门控，
     * 零条日志会被读成「功能没生效」。开闸要导航设置页，所以必须在脚本自己导航之前。
     */
    @Test
    fun everyLogReadingScriptOpensGate() {
        val dir = File("../scripts")
        val offenders = dir.listFiles { f -> f.name.startsWith("aod_") && f.name.endsWith(".py") }!!
            .filter { it.name != "aod_enable_logs.py" }
            .filter { it.readText().contains("logcat") }
            .filterNot { it.readText().contains("ensure_log_gate") }
            .map { it.name }
        assertTrue("这些脚本读日志但没开闸：$offenders", offenders.isEmpty())
        // 闸必须在 main 里、且早于任何导航（goto_preview / am start）
        val audit = File("../scripts/aod_midframe_audit.py").readText()
        assertTrue("共享件提供闸", audit.contains("def ensure_log_gate("))
        assertTrue("闸失败要能判否", audit.contains("\"TOGGLED\" in out or \"ALREADY ON\" in out"))
    }

    /** 阳性对照：闸关时零条日志与「功能没跑」完全同形，判据无法区分。 */
    @Test
    fun positiveControlClosedGateLooksLikeFeatureMissing() {
        val gateOpen = false
        val featureRan = true
        val linesWhenClosed = 0
        val linesWhenOpenAndRan = 7
        val observed = if (gateOpen) linesWhenOpenAndRan else linesWhenClosed
        assertEquals("闸关时即使功能跑了也是零条", 0, observed)
        assertTrue("只看条数无法分辨（$observed）", observed == 0 && featureRan)
    }

    /** 照片是持久化配置：选过之后再跑基线判据必然假红，必须能显式声明。 */
    @Test
    fun backdropScriptDeclaresPersistedPhotoState() {
        val src = script("aod_backdrop_source_e2.py")
        assertTrue("必须有 --has-photo", src.contains("--has-photo"))
        val branch = src.substringAfter("if photo_already:").substringBefore("else:")
        assertTrue("已选照片走 photo 判据而不是基线判据", branch.contains("judge_photo("))
    }

    /**
     * ★自我更正：1.25x 判据**物理上可达**（上限 1.427 > 1.25），不是过严。
     *
     * 实测只涨到 +10.7% 是「拖动只送达约 55px」而不是「上限到头」，
     * 而像素判据无法区分「拖不动」与「压根没选中」——两者都报 FAIL，定位不到真因。
     */
    @Test
    fun pixelRatioCriterionIsReachableSoItCannotDiagnose() {
        val clockLeft = 282f
        val phoneRight = 1017f
        val resetWidth = 515f
        val maxRatio = (phoneRight - clockLeft) / resetWidth
        assertTrue("物理上限 $maxRatio 已超过 1.25x ⇒ 不能说判据过严", maxRatio > 1.25f)
        // 旧脚本的实际达成值远低于上限，说明缺的不是上限而是前置态取证
        val observedRatio = 570f / 515f
        assertTrue("实测 $observedRatio 远低于上限 $maxRatio", observedRatio < maxRatio - 0.3f)
        // 日志口径能区分：零条 = 没进 resize；有序列但 Δ 小 = 进了但拖动距离短
        val src = script("aod_stretch_e2.py")
        assertTrue("靠日志条数分辨前置态", src.contains("if not seq:"))
        assertTrue("靠 Δstretch 分辨拖动幅度", src.contains("dsx = sx1 - sx0"))
    }
    /**
     * ★凡打 VERDICT 的脚本，必须有「零样本 ≠ 通过」的闸。
     *
     * 反面实锤（同日连中三次）：①日志闸关着时零条日志与功能没跑完全同形；
     * ②判据③写在文档串里、代码从未实现；③`if fade and ...` 在样本为空时静默跳过。
     * 三种都会让脚本在「什么都没测到」时打 PASS。
     */
    @Test
    fun everyVerdictScriptGatesZeroSample() {
        val dir = File("../scripts")
        val scripts = dir.listFiles { f -> f.name.startsWith("aod_") && f.name.endsWith(".py") }!!
            .filter { it.readText().contains("VERDICT") }
        assertTrue("至少要扫到脚本", scripts.size >= 15)
        val ungated = scripts.filterNot { f -> zeroSampleGate().containsMatchIn(f.readText()) }
            .map { it.name }
        assertTrue("这些脚本零样本时会静默 PASS：$ungated", ungated.isEmpty())
    }

    /**
     * 零样本闸的形态：拒绝性语句必须与 `bad.append` / `print` 同行（只在文档串里出现不算）。
     *
     * 形式不必统一 —— 有 `if not xs:`、有 `if all(r is None ...)`、有直接 `print(INCONCLUSIVE)`。
     * 要紧的是「什么都没测到时不许打 PASS」这句意图真的落在拒绝分支里，而不是只在注释里。
     */
    private fun zeroSampleGate(): Regex = Regex(
        "(?:bad\\.append|print)\\([^\\n]*(?:不是通过|未取到证据|无法判定|零样本|零有效|零条|零 play|INCONCLUSIVE)",
    )

    /** 阳性对照：没有闸的样例必须被上面的规则判出来。 */
    @Test
    fun positiveControlUngatedScriptIsCaught() {
        val fake = "bad = []\nif xs and not ok:\n    bad.append('x')\nprint('VERDICT', 'FAIL' if bad else 'PASS')"
        assertFalse("无闸脚本必须被判出（否则闸形同虚设）", zeroSampleGate().containsMatchIn(fake))
    }

    /**
     * ★序列号不得默认成模拟器：默认值会让脚本静默操作错设备。
     *
     * 反面实锤（2026-11-20 真机踩到）：临时脚本 import 审计模块时没传 argv，
     * SERIAL 落回 `emulator-5554` ⇒ UI 改的是模拟器、验收读的是真机 ⇒
     * 「界面已改但落盘没变」的假矛盾，查了很久才发现在改错设备。
     */
    @Test
    fun noScriptDefaultsToEmulatorSerial() {
        val dir = File("../scripts")
        val offenders = dir.listFiles { f -> f.name.startsWith("aod_") && f.name.endsWith(".py") }!!
            .filter { it.readText().contains("emulator-5554") }
            .map { it.name }
        assertTrue("这些脚本把模拟器当默认序列号：$offenders", offenders.isEmpty())
    }

    /** 阳性对照：没给序列号时必须直接退出（exit 2），不能默默跑模拟器。 */
    @Test
    fun positiveControlMissingSerialExits() {
        val script = File("../scripts/aod_seam_e2.py")
        val proc = ProcessBuilder("python", "-I", script.path)
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        assertEquals("缺序列号必须退出码 2", 2, code)
        assertTrue("必须明说要序列号：$out", out.contains("必须显式给序列号"))
    }

    /**
     * ★`bar_of` 的高度下限必须挡住状态栏。
     *
     * 反面实锤（同日连踩三次）：状态栏也满足「宽 > 300 + `android.view.View` + 高 60..200」，
     * 真机实测状态栏高 93、三态条高 144 ⇒ 旧下限 60 会把状态栏当三态条，
     * 按它算的点击全落在顶部状态栏，脚本随后报「找不到三态条 / 零条日志」，
     * 而真正原因是**认错了元素**。
     */
    @Test
    fun barOfRejectsStatusBarByHeightFloor() {
        val src = File("../scripts/aod_midframe_audit.py").readText()
        val floor = Regex("BAR_MIN_H = (\\d+)").find(src)?.groupValues?.get(1)?.toInt()
        assertTrue("必须有显式高度下限常量", floor != null)
        assertTrue("下限 $floor 必须高于状态栏实测 93", floor!! > 93)
        assertTrue("下限 $floor 必须低于三态条实测 144", floor < 144)
        assertTrue("判据必须用该常量", src.contains("BAR_MIN_H <= (y1 - y0)"))
        // 阳性对照：状态栏 (0,0,1080,93) 在旧口径下会被选中
        val statusBarH = 93
        val oldFloor = 60
        assertTrue("旧下限 $oldFloor 放行了状态栏 $statusBarH", statusBarH >= oldFloor && statusBarH < 200)
        assertFalse("新下限必须挡住它", statusBarH >= floor)
    }

    /**
     * ★`bar_of` 必须有「本页是预览页」的前置锚点，不许盲取第一个宽扁 View。
     *
     * 反面实锤（第118轮）：密码盘页面里仍有高 100..200 的 View 满足宽高条件，
     * 旧逻辑 `if hint: ... else: return cands[0]` 会随手挑一个，点击落空后
     * 脚本报「零条日志」，真因是**认错页**。
     *
     * ★配套：编辑态提示行必须也在 HINTS 里 —— 否则加了这个前置会把编辑态直接判死。
     */
    @Test
    fun barOfRequiresPreviewPageAnchor() {
        val src = File("../scripts/aod_midframe_audit.py").readText()
        assertTrue("没有提示行时必须返回 None", src.contains("if not hint:"))
        assertTrue("该分支必须返回 None", src.contains("if not hint:\n        return None"))
        assertFalse("不得盲取第一个候选", src.contains("return cands[0]"))
        // 三种态各一句提示，缺一句就会把那个态判死
        val hints = src.substringAfter("HINTS = (").substringBefore(")")
        assertTrue("普通态提示", hints.contains("点三态条播放动画"))
        assertTrue("拖动态提示", hints.contains("拖动逐帧"))
        assertTrue("编辑态提示", hints.contains("拖动调整位置"))
        // 阳性对照：只有两句时编辑态 hint_of 返回 None ⇒ bar_of 直接判死
        val onlyTwo = listOf("点三态条播放动画 · 长按拖动看中间帧", "拖动逐帧 · 松开停在最近一端")
        val editHint = "拖动调整位置 · 拖右下圆点自由拉伸 · 点小组件改尺寸"
        assertFalse("旧两句覆盖不到编辑态", onlyTwo.any { it == editHint })
        assertTrue("补第三句后覆盖到", (onlyTwo + editHint).any { it == editHint })
    }

    /**
     * 从脚本里取出 `NAME = re.compile(r"…")` 的模式串。
     *
     * 不用正则解析源码：Kotlin 的 `"\."` 是非法转义，写起来全是坑。
     * ★必须容忍 `re.compile(` 后换行（FRAME 就是这么写的，单行写法提取不到）。
     */
    private fun pattern(src: String, name: String): String {
        val start = src.indexOf("$name = re.compile(")
        assertTrue("找不到 $name 定义", start >= 0)
        // 模式可能由多段相邻 r"…" 拼成（FRAME 就是两段）。截到下一个「顶格新语句」
        // （`\n` 后紧跟非空白）为止：单行定义后面是注释/下一个定义，多行定义后面是顶格 `)`。
        // 只取第一段会漏掉后续分组，让契约在「新增字段」上变成假绿。
        val end = Regex("\\n(?=\\S)").find(src, start + 1)?.range?.first ?: src.length
        val body = src.substring(start, end)
        val parts = Regex("r\"([^\"]+)\"").findAll(body).map { it.groupValues[1] }.toList()
        assertTrue("$name 里没有 r\"…\" 字面量", parts.isNotEmpty())
        return parts.joinToString("")
    }

    /**
     * ★`world-blurclear` 零条必须按 `-off` 的 edge 分类，不能直接判缺陷。
     *
     * 反面实锤（第132轮）：拿「4 个捕获只有 `-off`」推「LOCK↔HOME 边没进世界层」——
     * 那几条 `-off` 的 edge 全是 AOD 边，对 AOD 边 `blurClearAlpha` 按设计返回 0，
     * 只能说明那几次没做该转场。且 `-off` 本身是节流日志，短捕获「零条」是弱证据。
     *
     * ★第二处假红（第133轮）：`-off` 出现「本边 + a=0」本身不能定罪 ——
     * `LOCK_TO_HOME` 在 t>=0.55、`HOME_TO_LOCK` 在 t<=0.45 时 `blurClearAlpha` 恒 0 是**正确行为**，
     * 捕获从边中途开始时尤其如此。判缺陷必须配「时间轴已推进到该出 a>0 的区间」。
     */
    @Test
    fun blurClearZeroSampleIsClassifiedByOffEdge() {
        val src = script("aod_lockhome_stage_e2.py")
        val branch = src.substringAfter("if not lb and not hb:")
        assertTrue("时间轴有中段 tick 时判真缺陷", branch.contains("该出模糊却没出"))
        assertTrue("tick 全端点时判 AOD-142 同源", branch.contains("t 恒 1.000"))
        assertTrue("有中段渲染帧但零日志时另查口径", branch.contains("查日志口径/节流"))
        assertTrue("edge 全是 AOD 边时不得判缺陷", branch.contains("零条不等于缺陷"))
        assertTrue("连 -off 都没有时按弱证据处理", branch.contains("弱证据"))
        // ★前置条件必须写在代码里，不能只在注释里：天然 a=0 窗口
        assertTrue("必须显式认「本边天然 a=0 窗口」", branch.contains("天然 a=0 窗口"))
        // 阳性对照：本边 -off + a=0 在窗口内是合法行为，单凭它不能定罪
        val t = 0.70f
        val lockToHomeInWindow = t >= 0.55f
        assertTrue("t=$t 落在 LOCK_TO_HOME 天然 a=0 窗口", lockToHomeInWindow)
        val t2 = 0.30f
        assertTrue("t=$t2 落在 HOME_TO_LOCK 天然 a=0 窗口", t2 <= 0.45f)
        // 而 AOD 边任何 t 都是 0 ⇒ 样本里那三条 edge 说明不了任何缺陷
        val offEdges = listOf("LOCK_TO_AOD", "HOME_TO_AOD", "LOCK_TO_AOD")
        assertTrue("样本里没有本边", offEdges.none { it == "LOCK_TO_HOME" || it == "HOME_TO_LOCK" })
    }

    /**
     * ★正则必须能匹配**真实日志格式**，不能只断言源码里有那串字。
     *
     * 反面实锤（第134轮）：`logThrottled` 实现是 `Lg.i(TAG, "$key: $msg")`（**带冒号**），
     * 而 `BLUR` 写成 `world-blurclear edge=`（无冒号）⇒ 永远匹配不上；
     * 上一版契约还把这个写法原样断言下来 ⇒ 把 bug 钉死、还阻止修正。
     *
     * ⇒ 现在的断言对象是「能匹配真实日志的正则」，不是「我写的那串字」。
     */
    @Test
    fun blurClearRegexMatchesRealLogFormat() {
        val src = script("aod_lockhome_stage_e2.py")
        val blur = pattern(src, "BLUR")
        val off = pattern(src, "BLUROFF")
        val realLine = "09:00:00.100  1  1 I AodWorld : world-blurclear: edge=LOCK_TO_HOME t=0.312 a=0.845 zoom=1.000"
        val offLine = "09:00:00.200  1  1 I AodWorld : world-blurclear-off: 轻模糊不参与 edge=LOCK_TO_AOD a=0.000"
        val m = Regex(blur).find(realLine)
        assertTrue("BLUR 必须匹配真实日志行（模式=$blur）", m != null)
        assertEquals("边名要取到", "LOCK_TO_HOME", m!!.groupValues[1])
        assertEquals("t 要取到", 0.312f, m.groupValues[2].toFloat(), 1e-3f)
        assertEquals("a 要取到", 0.845f, m.groupValues[3].toFloat(), 1e-3f)
        assertTrue("BLUR 不得吃 -off 行", Regex(blur).find(offLine) == null)
        assertTrue("BLUROFF 必须匹配 -off 行（模式=$off）", Regex(off).find(offLine) != null)
        assertTrue("BLUROFF 不得吃真实行", Regex(off).find(realLine) == null)
        // 阳性对照：无冒号写法对真实行确实匹配不上，证明这条契约抓得住原 bug
        assertTrue("无冒号写法必须匹配不上", Regex("world-blurclear edge=(\\w+)").find(realLine) == null)
    }

    /**
     * ★判分器必须先证明自己是对的：冷启授权窗口只有一次。
     *
     * 反面实锤（peer 第134轮）：`aod_142_verify.py` 有两条路径会在真触发时崩
     * （`BLUR.findall(text)` 里 `text` 未定义 / 元组格式化 `TypeError`），
     * 都是「happy path 跑通一次就当它能用」。加了 `--self-test` 才当场抓到。
     */
    @Test
    fun lockhomeScriptHasOfflineSelfTest() {
        val src = script("aod_lockhome_stage_e2.py")
        assertTrue("必须有 --self-test 入口", src.contains("\"--self-test\" in sys.argv"))
        assertTrue("main 必须先分支到自检", src.contains("if SELF_TEST:"))
        assertTrue("必须有 self_test 函数", src.contains("def self_test():"))
        // 自检不得依赖设备：无序列号时不许走 _die_no_serial
        assertTrue("自检时序列号可为空", src.contains("_serial_arg or \"\""))
        assertTrue("必须有汇总输出", src.contains("SELFTEST %d/%d"))
        // 自检必须覆盖 blur 四分类的关键分支
        for (needle in listOf("该出模糊却没出", "t 恒 1.000", "零条不等于缺陷", "弱证据", "天然 a=0 窗口")) {
            assertTrue("自检须覆盖：$needle", src.contains(needle))
        }
    }

    /**
     * ★「没采集到」不许判成「实现错了」：零 SEG 与短边稀疏都是 INCONCLUSIVE。
     *
     * 反面实锤（peer 第134轮指出）：`segments()` 只把行归到上一个 SEG 段，
     * 零标记时所有行被丢弃 ⇒ `seg == {}` ⇒ 两段都 noframe ⇒ 判 FAIL。
     * 但零标记可能是「标记没落进缓冲 / -c 冲掉 / 外部没打」，属采集问题。
     */
    @Test
    fun lockhomeScriptTreatsNoSampleAsInconclusive() {
        val src = script("aod_lockhome_stage_e2.py")
        assertTrue("零 SEG 必须判 INCONCLUSIVE", src.contains("零个 SEG 分段标记"))
        assertTrue("零 SEG 分支必须 return 2", src.contains("没有可归属的样本（不是缺陷）"))
        // 短边稀疏：只返回 sparse，不 append bad
        assertTrue("judge 必须能返回 sparse", src.contains("return \"sparse\""))
        assertTrue("只有长边才判红", src.contains("long_edge = any(p[\"duration\"] >= 3000"))
        assertTrue("上层把 sparse 判 INCONCLUSIVE", src.contains("中段帧不足且属短边"))
        // 阳性对照：600ms 边 + 120ms 节流理论 ~5 帧，采到 1 帧属采样不足
        val edgeMs = 600
        val throttleMs = 120
        val theoretical = edgeMs / throttleMs
        assertTrue("短边理论帧数 $theoretical 很少，采到 1 帧不算缺陷", theoretical <= 5)
    }

    /**
     * ★`--no-drive` 必须 dump 已有缓冲（`-d`），绝不能先 `logcat -c`。
     *
     * 反面实锤（peer 第142轮）：原实现无条件 `-c` 清空再开流、`finally` 立刻 terminate
     * ⇒ 外部驱动刚留下的数据被清掉、只抓到几毫秒空流 ⇒ 必然「零个 SEG」INCONCLUSIVE。
     * **冷启窗口只有一次，这一跑就白费。**
     */
    @Test
    fun noDriveDumpsExistingBufferInsteadOfClearing() {
        val src = script("aod_lockhome_stage_e2.py")
        val branch = src.substringAfter("if NO_DRIVE:").substringBefore("else:")
        assertTrue("--no-drive 必须用 -d dump", branch.contains("\"logcat\", \"-d\""))
        assertFalse("--no-drive 分支里不得出现 -c 清空", branch.contains("\"-c\""))
        assertTrue("必须把 dump 内容写进 log_path", branch.contains("f.write(dumped)"))
        // 阳性对照：清空后再开流，终止时只剩空流 —— 与「读已有日志」语义相反
        val clearedThenStreamed = listOf("-c", "stream")
        val dumped = listOf("-d")
        assertTrue("清空路径 $-c 与 dump 路径 -d 语义相反", clearedThenStreamed[0] != dumped[0])
    }

    /**
     * ★设临时 PIN 前必须确认「本来就没有凭据」。
     *
     * 反面实锤（peer 第142轮）：直接 `set-pin` 会覆盖已有 PIN，收尾 `clear` 再清成「无密码」
     * ⇒ 原 PIN 永久丢失。脚本自己宣称「保证还原」，就必须在动手前确认前提成立。
     */
    @Test
    fun acceptScriptRefusesToOverwriteExistingPin() {
        val src = script("aod_accept_140_142.py")
        val before = src.substringBefore("set-pin")
        assertTrue("设 PIN 之前必须查 get-disabled", before.contains("locksettings\", \"get-disabled"))
        assertTrue("已有凭据必须拒绝", before.contains("拒绝覆盖"))
        assertTrue("拒绝时返回 INCONCLUSIVE", before.contains("VERDICT INCONCLUSIVE 设备已有锁屏凭据"))
        assertTrue("收尾必须清 PIN 并复核", src.contains("locksettings\", \"clear\", \"--old\"") ||
            src.contains("\"clear\", \"--old\", PIN"))
        assertTrue("还原失败要最高优先级", src.contains("RESTORE-FAIL") && src.contains("return 3"))
        // 阳性对照：不查前提就 set-pin，等于把「本来有 PIN」也当成「本来没有」
        assertTrue("检查点必须早于设 PIN", before.contains("get-disabled"))
    }

    /**
     * ★帧行追加字段后，`FRAME` 正则必须仍能匹配**真实新格式**。
     *
     * 反面实锤（第134轮）：正则只断言源码里那串字、不对真实日志行，结果把 bug 钉死。
     * 这次（第145轮）peer 在 `par=` 之后追加了 `lk=`：如果 `lk` 前没有空格，
     * `par=(\S+)` 会把 `AodRootLayout lk=0.500` 吞成一个值，后续 `par` 判据全错。
     */
    @Test
    fun frameRegexMatchesLogLineWithTrailingLkField() {
        val src = script("aod_lockhome_stage_e2.py")
        val frame = pattern(src, "FRAME")
        val realWithLk = "stage-frame-fast: edge=LOCK_TO_HOME t=0.500 dim=0.300 wp=1.000 aod=0.200 " +
            "cx=0.5000 cy=0.5000 s=0.2200 a=1.000 maskR=435 att=true par=AodRootLayout lk=0.500"
        val m = Regex(frame).find(realWithLk)
        assertTrue("FRAME 必须匹配带 lk 的真实行（模式=$frame）", m != null)
        assertEquals("边名", "LOCK_TO_HOME", m!!.groupValues[1])
        assertEquals("t", 0.500f, m.groupValues[2].toFloat(), 1e-3f)
        assertEquals("att", "true", m.groupValues[4])
        assertEquals("par 必须只取类名，不能把 lk 吞进来", "AodRootLayout", m.groupValues[5])
        // 阳性对照：lk 前少了空格时 par 会被污染 —— 证明这个空格是必需的
        val noSpace = realWithLk.replace(" par=AodRootLayout lk=", " par=AodRootLayoutlk=")
        val bad = Regex(frame).find(noSpace)
        assertTrue("无空格时 par 被吞成一体", bad != null && bad.groupValues[5] == "AodRootLayoutlk=0.500")
    }
}
