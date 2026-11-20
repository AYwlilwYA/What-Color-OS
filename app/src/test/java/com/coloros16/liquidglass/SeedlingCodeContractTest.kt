package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SeedlingCodeExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 验证码三段式提取契约：关键词/紧邻/兜底/负向词表/单位货币守卫，含阳性对照。 */
class SeedlingCodeContractTest {

    @Test
    fun adjacentChineseColon() {
        assertEquals("123456", SeedlingCodeExtractor.extract("【某应用】验证码：123456，5分钟内有效"))
    }

    @Test
    fun adjacentWithShi() {
        assertEquals("884213", SeedlingCodeExtractor.extract("您的验证码是 884213"))
    }

    @Test
    fun dashedJoin() {
        assertEquals("123456", SeedlingCodeExtractor.extract("验证码 123-456 请勿泄露"))
    }

    @Test
    fun englishKeyword() {
        assertEquals("482913", SeedlingCodeExtractor.extract("Your verification code is 482913. Do not share."))
    }

    @Test
    fun otpKeyword() {
        assertEquals("998877", SeedlingCodeExtractor.extract("OTP: 998877"))
    }

    @Test
    fun alphanumericCode() {
        assertEquals("A1B2C3", SeedlingCodeExtractor.extract("Your code: A1B2C3"))
    }

    @Test
    fun fallbackScanAfterKeyword() {
        assertEquals("654321", SeedlingCodeExtractor.extract("验证码已发送，请使用 654321 完成登录"))
    }

    @Test
    fun fallbackScanBeforeKeyword() {
        assertEquals("246810", SeedlingCodeExtractor.extract("246810 是你的验证码"))
    }

    @Test
    fun multiTextFirstWins() {
        assertEquals("111222", SeedlingCodeExtractor.extract("标题无码", "验证码：111222", "验证码：333444"))
        assertNull(SeedlingCodeExtractor.extract(null, "", "  "))
    }

    // ---------- 负向：不误提 ----------

    @Test
    fun noKeywordNoExtract_positiveControl() {
        assertNull("阳性对照：无关键词的纯数字文本不提取", SeedlingCodeExtractor.extract("订单 123456 已发货"))
    }

    @Test
    fun tailNumberRejected() {
        assertNull(SeedlingCodeExtractor.extract("验证码已发送，尾号1234的卡"))
    }

    @Test
    fun cardNumberRejected() {
        assertNull(SeedlingCodeExtractor.extract("验证码提示：卡号 6222021234567890"))
    }

    @Test
    fun orderNumberRejected() {
        assertNull(SeedlingCodeExtractor.extract("验证码无关文本 订单号：20261119"))
    }

    @Test
    fun amountRejected() {
        assertNull(SeedlingCodeExtractor.extract("验证码活动：满1000元减50"))
    }

    @Test
    fun currencyRejected() {
        assertNull(SeedlingCodeExtractor.extract("验证码充值 ¥123456"))
    }

    @Test
    fun dateSuffixRejected() {
        assertNull(SeedlingCodeExtractor.extract("验证码有效期 2026年"))
    }

    @Test
    fun tooShortRejected() {
        assertNull(SeedlingCodeExtractor.extract("验证码：123"))
    }

    @Test
    fun embeddedInLongNumberRejected() {
        assertNull(SeedlingCodeExtractor.extract("验证码核对 123456789012"))
    }

    @Test
    fun decimalRejected() {
        assertNull(SeedlingCodeExtractor.extract("验证码版本 1234.56"))
    }

    @Test
    fun windowBeyond40Rejected() {
        val far = "验证码" + "啊".repeat(45) + "654321"
        assertNull("超 40 字符窗口不兜底", SeedlingCodeExtractor.extract(far))
    }
}
