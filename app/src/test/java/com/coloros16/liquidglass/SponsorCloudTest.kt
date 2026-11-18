package com.coloros16.liquidglass

import com.coloros16.liquidglass.ui.sponsor.SponsorCloud
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SponsorCloudTest {

    private val listUrl = "https://gitlab.com/g/p/-/raw/main/sponsors.txt"

    @Test
    fun parsesTwoAndThreeFieldRows() {
        val rows = SponsorCloud.parse(
            """
            # 注释行
            K|K***
            口|口口|sponsor_kokoro.png

            A|A***|https://gitlab.com/g/p/-/raw/main/a.png
            """.trimIndent(),
        )
        assertEquals(3, rows.size)
        assertEquals(SponsorCloud.Row("K", "K***", ""), rows[0])
        assertEquals(SponsorCloud.Row("口", "口口", "sponsor_kokoro.png"), rows[1])
        assertEquals("https://gitlab.com/g/p/-/raw/main/a.png", rows[2].avatar)
    }

    @Test
    fun dropsMalformedRows() {
        val rows = SponsorCloud.parse("|noInitial\nonlyOne\n#x\n\n  \nB|Bob")
        assertEquals(1, rows.size)
        assertEquals("Bob", rows[0].name)
    }

    @Test
    fun trimsWhitespaceAroundFields() {
        val rows = SponsorCloud.parse("  口 | 口口 | sponsor_kokoro.png  ")
        assertEquals(SponsorCloud.Row("口", "口口", "sponsor_kokoro.png"), rows[0])
    }

    @Test
    fun avatarWhitelistRejectsLocalPathsAndOtherSchemes() {
        assertEquals("", SponsorCloud.safeAvatar("../../secret"))
        assertEquals("", SponsorCloud.safeAvatar("file:///data/local/tmp/x.png"))
        assertEquals("", SponsorCloud.safeAvatar("http://insecure/x.png"))
        assertEquals("", SponsorCloud.safeAvatar("https://h/../x.png"))
        assertEquals("", SponsorCloud.safeAvatar("/sdcard/x.png"))
        assertEquals("", SponsorCloud.safeAvatar(""))
    }

    @Test
    fun avatarWhitelistAcceptsResourceNameAndHttps() {
        assertEquals("sponsor_kokoro", SponsorCloud.safeAvatar("sponsor_kokoro"))
        assertEquals("sponsor_kokoro.png", SponsorCloud.safeAvatar("sponsor_kokoro.png"))
        assertEquals("a-b_2.webp", SponsorCloud.safeAvatar("a-b_2.webp"))
        assertEquals(
            "https://gitlab.com/g/p/-/raw/main/a.png",
            SponsorCloud.safeAvatar("https://gitlab.com/g/p/-/raw/main/a.png"),
        )
    }

    @Test
    fun avatarWhitelistRejectsTraversalWithDots() {
        assertEquals("", SponsorCloud.safeAvatar("a..b.png"))
        assertEquals("", SponsorCloud.safeAvatar("sub/a.png"))
        assertEquals("", SponsorCloud.safeAvatar("sub\\a.png"))
    }

    @Test
    fun relativeAvatarResolvesAgainstListDirectory() {
        assertEquals(
            "https://gitlab.com/g/p/-/raw/main/sponsor_kokoro.png",
            SponsorCloud.resolveAvatarUrl(listUrl, "sponsor_kokoro.png"),
        )
    }

    @Test
    fun absoluteAvatarUrlKeptAsIs() {
        val abs = "https://elsewhere.example/x.png"
        assertEquals(abs, SponsorCloud.resolveAvatarUrl(listUrl, abs))
    }

    @Test
    fun resourceNameResolvesToSiblingTooAndCallerSkipsNetwork() {
        assertEquals(
            "https://gitlab.com/g/p/-/raw/main/sponsor_kokoro",
            SponsorCloud.resolveAvatarUrl(listUrl, "sponsor_kokoro"),
        )
        assertTrue(!SponsorCloud.safeAvatar("sponsor_kokoro").contains('.'))
    }
}
