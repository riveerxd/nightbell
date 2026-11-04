package me.river.nightbell

import me.river.nightbell.domain.MailText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MailTextTest {

    @Test
    fun `plain headers pass through untouched`() {
        assertEquals("Server down on web-3", MailText.decodeHeader("Server down on web-3"))
    }

    @Test
    fun `base64 encoded words decode`() {
        assertEquals("Été à Paris", MailText.decodeHeader("=?UTF-8?B?w4l0w6kgw6AgUGFyaXM=?="))
    }

    @Test
    fun `q encoded words decode, underscores are spaces`() {
        assertEquals("Faktura č. 12", MailText.decodeHeader("=?UTF-8?Q?Faktura_=C4=8D=2E_12?="))
    }

    @Test
    fun `whitespace between two encoded words is dropped, folding is not a space`() {
        val folded = "=?UTF-8?B?RG9ydcSN?= \r\n =?UTF-8?B?ZW7DoSBwb8WhdGE=?="
        assertEquals("Doručená pošta", MailText.decodeHeader(folded))
    }

    @Test
    fun `text around encoded words keeps its spaces`() {
        assertEquals("Re: Été ok", MailText.decodeHeader("Re: =?UTF-8?Q?=C3=89t=C3=A9?= ok"))
    }

    @Test
    fun `legacy charsets decode, ISO-8859-2 for a Czech subject`() {
        assertEquals("Žádost", MailText.decodeHeader("=?ISO-8859-2?Q?=AE=E1dost?="))
    }

    @Test
    fun `an unknown charset leaves the word rather than losing the subject`() {
        val raw = "=?x-nonsense?B?SGVsbG8=?= there"
        assertEquals(raw, MailText.decodeHeader(raw))
    }

    @Test
    fun `broken base64 leaves the word as it came`() {
        val raw = "=?UTF-8?B?***?="
        assertEquals(raw, MailText.decodeHeader(raw))
    }

    @Test
    fun `a stray equals sign in Q text is kept`() {
        assertEquals("a=b", MailText.decodeHeader("=?UTF-8?Q?a=3Db?="))
        assertEquals("100%=", MailText.decodeHeader("=?UTF-8?Q?100%=?="))
    }

    @Test
    fun `folder names encode to modified UTF-7`() {
        assertEquals("INBOX", MailText.encodeFolder("INBOX"))
        assertEquals("Doru&AQ0-en&AOE- po&AWE-ta", MailText.encodeFolder("Doručená pošta"))
        assertEquals("Tom &- Jerry", MailText.encodeFolder("Tom & Jerry"))
        assertEquals("&ZeVnLIqe-", MailText.encodeFolder("日本語"))
    }

    @Test
    fun `folder names round trip`() {
        listOf("INBOX", "Doručená pošta", "Tom & Jerry", "日本語", "Archiv/2026").forEach {
            assertEquals(it, MailText.decodeFolder(MailText.encodeFolder(it)))
        }
    }

    @Test
    fun `quoting escapes quotes and backslashes, and refuses what a quoted string cannot carry`() {
        assertEquals("\"pa\\\"ss\\\\word\"", MailText.quote("pa\"ss\\word"))
        assertTrue(MailText.quotable("plain ascii !@#"))
        assertFalse(MailText.quotable("heslo-žluť"))
        assertFalse(MailText.quotable("line\nbreak"))
    }
}
