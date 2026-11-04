package me.river.nightbell

import java.io.ByteArrayInputStream
import me.river.nightbell.data.check.Imap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImapParserTest {

    private fun reader(vararg lines: String) =
        Imap.Reader(ByteArrayInputStream(lines.joinToString("").toByteArray(Charsets.UTF_8)))

    @Test
    fun `a response with a literal is read whole, literal bytes and the rest of the line`() {
        val subject = "Faktura č. 12"
        val bytes = subject.toByteArray(Charsets.UTF_8).size
        val r = reader("* 1 FETCH (UID 9 ENVELOPE (NIL {$bytes}\r\n$subject NIL))\r\n", "n1 OK done\r\n")
        val first = r.readResponse()
        assertTrue(first.startsWith("* 1 FETCH"))
        assertTrue(first.endsWith("NIL))"))
        assertEquals("n1 OK done", r.readResponse())
    }

    @Test
    fun `values parse, NIL, nested lists, quoted escapes`() {
        val values = Imap.parse("(\"a \\\"b\\\"\" NIL (x y) {3}\r\nabc)")
        val items = (values.single() as Imap.Value.Items).items
        assertEquals(Imap.Value.Str("a \"b\""), items[0])
        assertEquals(Imap.Value.Nil, items[1])
        assertEquals(Imap.Value.Items(listOf(Imap.Value.Atom("x"), Imap.Value.Atom("y"))), items[2])
        assertEquals(Imap.Value.Str("abc"), items[3])
    }

    @Test
    fun `raw 8-bit text in a literal is read as UTF-8`() {
        val text = "Žádost"
        val latin = String(text.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
        val values = Imap.parse("{${latin.length}}\r\n$latin")
        assertEquals(Imap.Value.Str(text), values.single())
    }

    @Test
    fun `status items read whatever order the server sends them in`() {
        val items = Imap.statusItems("* STATUS \"INBOX\" (UIDNEXT 4392 MESSAGES 231 UIDVALIDITY 1700000000 UNSEEN 3)")
        assertEquals(4392L, items["UIDNEXT"])
        assertEquals(231L, items["MESSAGES"])
        assertEquals(3L, items["UNSEEN"])
        assertEquals(1700000000L, items["UIDVALIDITY"])
    }

    @Test
    fun `a status line for a folder sent as a literal still reads`() {
        val items = Imap.statusItems("* STATUS {5}\r\nINBOX (MESSAGES 2 UIDNEXT 3)")
        assertEquals(3L, items["UIDNEXT"])
    }

    @Test
    fun `status responses split into kind, code and text`() {
        val s = Imap.Status.parse("NO [AUTHENTICATIONFAILED] Authentication failed.")
        assertFalse(s.ok)
        assertEquals("AUTHENTICATIONFAILED", s.codeName)
        assertEquals("Authentication failed.", s.text)
        val ok = Imap.Status.parse("OK [UIDNEXT 77] Predicted next UID")
        assertEquals("77", ok.codeArgs)
    }

    @Test
    fun `response codes are collected from untagged lines`() {
        val reply = Imap.Reply(
            Imap.Status.parse("OK [READ-ONLY] done"),
            listOf("* 3 EXISTS", "* OK [UIDVALIDITY 5] valid", "* OK [UIDNEXT 9] next"),
        )
        assertEquals("9", reply.codes()["UIDNEXT"])
        assertEquals("5", reply.codes()["UIDVALIDITY"])
    }

    @Test
    fun `list lines give the folder name, and none for a parent that cannot be opened`() {
        assertEquals("INBOX", Imap.listedFolder("* LIST (\\HasNoChildren) \"/\" \"INBOX\""))
        assertEquals("Sent", Imap.listedFolder("* LIST (\\HasNoChildren \\Sent) \".\" Sent"))
        assertEquals("Old Stuff", Imap.listedFolder("* LIST () \"/\" {9}\r\nOld Stuff"))
        assertEquals("Work/Alerts", Imap.listedFolder("* LIST () NIL \"Work/Alerts\""))
        assertNull(Imap.listedFolder("* LIST (\\Noselect \\HasChildren) \"/\" \"Projects\""))
        assertNull(Imap.listedFolder("* LSUB () \"/\" \"INBOX\""))
    }

    @Test
    fun `search results are numbers`() {
        assertEquals(listOf(4L, 5L, 9L), Imap.searchUids(listOf("* SEARCH 4 5 9")))
        assertEquals(emptyList<Long>(), Imap.searchUids(listOf("* SEARCH")))
    }

    @Test
    fun `a fetch reads uid, flags, date and the envelope sender and subject`() {
        val line = "* 12 FETCH (UID 345 FLAGS (\\Seen \\Answered) INTERNALDATE \"04-Nov-2026 09:30:00 +0100\" " +
            "ENVELOPE (\"Wed, 4 Nov 2026 09:30:00 +0100\" \"=?UTF-8?Q?Server_=C4=8Dek=C3=A1?=\" " +
            "((\"=?UTF-8?B?SmFuIE5vdsOhaw==?=\" NIL \"jan\" \"example.cz\")) NIL NIL NIL NIL NIL NIL \"<x@y>\"))"
        val message = Imap.fetchMessage(line)!!
        assertEquals(345L, message.uid)
        assertTrue(message.seen)
        assertEquals("Server čeká", message.subject)
        assertEquals("Jan Novák", message.fromName)
        assertEquals("jan@example.cz", message.fromAddress)
        assertEquals(1793781000000L, message.receivedAt)
    }

    @Test
    fun `a sender with no name and no subject still reads`() {
        val line = "* 1 FETCH (UID 2 FLAGS () ENVELOPE (NIL NIL ((NIL NIL \"cron\" \"box.lan\")) NIL NIL NIL NIL NIL NIL NIL))"
        val message = Imap.fetchMessage(line)!!
        assertEquals("cron@box.lan", message.from)
        assertEquals("(no subject)", message.subjectOrPlaceholder)
        assertFalse(message.seen)
    }

    @Test
    fun `a fetch without a uid is an unsolicited flag update and is ignored`() {
        assertNull(Imap.fetchMessage("* 3 FETCH (FLAGS (\\Seen))"))
    }

    @Test
    fun `internal dates with a space padded day parse`() {
        assertEquals(Imap.internalDate("04-Nov-2026 09:30:00 +0000"), Imap.internalDate(" 4-Nov-2026 09:30:00 +0000"))
        assertEquals(0L, Imap.internalDate("not a date"))
    }
}
