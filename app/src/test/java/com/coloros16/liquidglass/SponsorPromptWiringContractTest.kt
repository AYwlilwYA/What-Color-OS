package com.coloros16.liquidglass

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 赞助提示接线契约：冷启动计数必须真的有人消费。
 *
 * 治过的缺陷：`trackColdStart()` 只写计数、全仓无人读取，档位永远不触发。
 */
class SponsorPromptWiringContractTest {

    private fun activity(): String = File(
        "src/main/kotlin/com/coloros16/liquidglass/ui/SettingsActivity.kt",
    ).readText()

    private fun strings(): String = File(
        "src/main/res/values/strings.xml",
    ).readText()

    @Test
    fun coldStartCountIsConsumed() {
        val src = activity()
        assertTrue("冷启动计数必须被读取", src.contains("xmlPrefs.getInt(XML_KEY_OPEN_COUNT"))
        assertTrue("档位判定必须走共用引擎", src.contains("SponsorMilestone.forCount("))
        assertTrue("必须真的调用弹窗入口", src.contains("maybeShowSponsorPrompt()"))
    }

    @Test
    fun eachMilestoneShowsOnlyOnce() {
        val src = activity()
        assertTrue("必须记录已弹档位", src.contains("XML_KEY_SPONSOR_MILESTONE"))
        assertTrue("已弹过的档位必须跳过", src.contains("XML_KEY_SPONSOR_MILESTONE, 0) >= milestone"))
        assertTrue("弹前必须写回档位", src.contains("putInt(XML_KEY_SPONSOR_MILESTONE, milestone)"))
    }

    @Test
    fun declineTriggersStarPrompt() {
        val src = activity()
        assertTrue("拒绝后必须追弹点星提示", src.contains("showStarPrompt()"))
        assertTrue("点星按钮必须跳仓库", src.contains("openExternal(REPO_URL)"))
        assertTrue("赞助按钮必须跳仓库", src.contains("setPositiveButton(R.string.sponsor_prompt_ok)"))
    }

    @Test
    fun userFacingCopyExists() {
        val res = strings()
        for (name in listOf(
            "sponsor_prompt_title",
            "sponsor_prompt_message",
            "sponsor_prompt_ok",
            "sponsor_prompt_later",
            "sponsor_star_title",
            "sponsor_star_message",
            "sponsor_star_ok",
            "sponsor_star_later",
        )) {
            assertTrue("缺少文案 $name", res.contains("name=\"$name\""))
        }
        assertTrue("文案必须带打开次数占位", res.contains("%1\$d 次"))
    }
}
