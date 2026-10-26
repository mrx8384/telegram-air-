package com.tcam.core

import com.tcam.core.input.ChannelInputParser
import com.tcam.core.input.TitleNormalizer
import com.tcam.core.model.ChannelInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelInputParserTest {

    @Test fun parsesUsernamesAndLinks() {
        val parsed = ChannelInputParser.parse(
            """
            @channel_one
            https://t.me/channel_two
            t.me/s/channel_three
            telegram.me/ChannelFour
            tg://resolve?domain=channel_five
            sixth_chan.t.me
            """.trimIndent(),
        )
        assertEquals(
            listOf("channel_one", "channel_two", "channel_three", "ChannelFour", "channel_five", "sixth_chan"),
            parsed.map { (it as ChannelInput.Username).username },
        )
    }

    @Test fun parsesInviteLinks() {
        val parsed = ChannelInputParser.parse("https://t.me/+AbCdEf123\nt.me/joinchat/XyZ987\ntg://join?invite=QQQ")
        assertEquals(
            listOf("https://t.me/+AbCdEf123", "https://t.me/+XyZ987", "https://t.me/+QQQ"),
            parsed.map { (it as ChannelInput.InviteLink).link },
        )
    }

    @Test fun treatsOtherTextAsTitle() {
        val parsed = ChannelInputParser.parse("My  Cool Channel\nاخبار فوری")
        assertEquals(listOf("My Cool Channel", "اخبار فوری"), parsed.map { (it as ChannelInput.Title).title })
    }

    @Test fun commasCommentsAndDuplicates() {
        val parsed = ChannelInputParser.parse("@abcde, @ABCDE ,# comment\n\n@fghij")
        assertEquals(2, parsed.size)
    }

    @Test fun invalidUsernameBecomesTitle() {
        val parsed = ChannelInputParser.parseOne("@ab")
        assertTrue(parsed is ChannelInput.Title)
        assertTrue(ChannelInputParser.parseOne("https://t.me/c/12345/6") == null)
    }

    @Test fun normalizesPersianVariants() {
        assertEquals(TitleNormalizer.normalize("كانال علي ۱۲"), TitleNormalizer.normalize("کانال علی 12"))
        assertEquals("abc def", TitleNormalizer.normalize("  ABC‌  Def "))
    }
}
