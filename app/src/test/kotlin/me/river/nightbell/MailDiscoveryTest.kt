package me.river.nightbell

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import me.river.nightbell.data.check.MailDiscoverer
import me.river.nightbell.domain.MailDiscovery
import me.river.nightbell.domain.MailDiscovery.Candidate
import me.river.nightbell.domain.MailDiscovery.Probe
import me.river.nightbell.domain.MailDiscovery.Source
import me.river.nightbell.domain.MailSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MailDiscoveryTest {

    // ---- DNS ---------------------------------------------------------------------

    /** A response the way a resolver sends one: the question echoed, answers using compression. */
    private fun response(name: String, type: Int, vararg rdata: (Int) -> ByteArray, rcode: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0x81.toByte(), (0x80 or rcode).toByte(), 0, 1, 0, rdata.size.toByte(), 0, 0, 0, 0))
        val questionAt = out.size()
        name.split('.').forEach { out.write(it.length); out.write(it.toByteArray()) }
        out.write(0)
        out.write(byteArrayOf(0, type.toByte(), 0, 1))
        rdata.forEach { make ->
            out.write(byteArrayOf(0xC0.toByte(), questionAt.toByte()))
            out.write(byteArrayOf(0, type.toByte(), 0, 1, 0, 0, 0x0E, 0x10))
            val data = make(questionAt)
            out.write(byteArrayOf((data.size shr 8).toByte(), data.size.toByte()))
            out.write(data)
        }
        return out.toByteArray()
    }

    private fun name(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        text.split('.').forEach { out.write(it.length); out.write(it.toByteArray()) }
        out.write(0)
        return out.toByteArray()
    }

    @Test
    fun `a query is a standard recursive question for the name and type`() {
        val q = MailDiscovery.dnsQuery("flirtycall.me", MailDiscovery.TYPE_MX)
        assertEquals(1, q[2].toInt())
        assertEquals(1, q[5].toInt())
        assertEquals(10, q[12].toInt())
        assertEquals("flirtycall", String(q, 13, 10))
        assertEquals(15, q[q.size - 3].toInt())
    }

    @Test
    fun `MX answers parse, lowest preference first, compressed names followed`() {
        val msg = response(
            "flirtycall.me", 15,
            { _ -> byteArrayOf(0, 20) + name("mx2.zionbit.eu") },
            { _ -> byteArrayOf(0, 10) + name("mx1.zionbit.eu") },
        )
        val mx = MailDiscovery.parseMx(msg)
        assertEquals(listOf("mx1.zionbit.eu", "mx2.zionbit.eu"), mx.map { it.host })
    }

    @Test
    fun `a name ending in a pointer into the question reads whole`() {
        val msg = response("example.com", 15, { qAt -> byteArrayOf(0, 5, 4) + "mail".toByteArray() + byteArrayOf(0xC0.toByte(), qAt.toByte()) })
        assertEquals("mail.example.com", MailDiscovery.parseMx(msg).single().host)
    }

    @Test
    fun `SRV answers parse and a dot target means not offered`() {
        val msg = response(
            "_imaps._tcp.example.com", 33,
            { _ -> byteArrayOf(0, 0, 0, 1, 0x03, 0xE1.toByte()) + name("imap.example.com") },
            { _ -> byteArrayOf(0, 0, 0, 0, 0, 0) + byteArrayOf(0) },
        )
        val srv = MailDiscovery.parseSrv(msg).single()
        assertEquals("imap.example.com", srv.target)
        assertEquals(993, srv.port)
    }

    @Test
    fun `an error rcode or a truncated message gives nothing rather than throwing`() {
        val nx = response("nope.example", 15, rcode = 3)
        assertTrue(MailDiscovery.parseMx(nx).isEmpty())
        val good = response("example.com", 15, { _ -> byteArrayOf(0, 10) + name("mx.example.com") })
        assertTrue(MailDiscovery.parseMx(good.copyOf(good.size - 5)).isEmpty())
        assertTrue(MailDiscovery.parseMx(byteArrayOf(1, 2, 3)).isEmpty())
    }

    // ---- autoconfig ------------------------------------------------------------------

    private val mailcow = """
        <?xml version="1.0"?><clientConfig version="1.1">
            <emailProvider id="mx1.zionbit.eu">
              <domain>%EMAILDOMAIN%</domain>
              <incomingServer type="imap">
                 <hostname>mx1.zionbit.eu</hostname><port>993</port><socketType>SSL</socketType>
                 <username>%EMAILADDRESS%</username><authentication>password-cleartext</authentication>
              </incomingServer>
              <incomingServer type="imap">
                 <hostname>mx1.zionbit.eu</hostname><port>143</port><socketType>STARTTLS</socketType>
                 <username>%EMAILADDRESS%</username>
              </incomingServer>
              <incomingServer type="imap">
                 <hostname>mx1.zionbit.eu</hostname><port>143</port><socketType>plain</socketType>
              </incomingServer>
              <incomingServer type="pop3">
                 <hostname>mx1.zionbit.eu</hostname><port>995</port><socketType>SSL</socketType>
              </incomingServer>
            </emailProvider>
        </clientConfig>
    """.trimIndent()

    @Test
    fun `a mailcow autoconfig file gives its two IMAP servers and nothing plain or POP`() {
        val found = MailDiscovery.parseAutoconfig(mailcow)
        assertEquals(2, found.size)
        assertEquals(Candidate("mx1.zionbit.eu", 993, MailSecurity.TLS, Source.AUTOCONFIG, "%EMAILADDRESS%"), found[0])
        assertEquals(MailSecurity.STARTTLS, found[1].security)
    }

    @Test
    fun `a site that answers every path with its home page is no file`() {
        assertTrue(MailDiscovery.parseAutoconfig("<!DOCTYPE html><html lang=\"cs\"><head>").isEmpty())
    }

    @Test
    fun `a document with a DTD is not parsed at all`() {
        val evil = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>" +
            "<clientConfig><emailProvider><incomingServer type=\"imap\"><hostname>&e;</hostname><port>993</port>" +
            "<socketType>SSL</socketType></incomingServer></emailProvider></clientConfig>"
        assertTrue(MailDiscovery.parseAutoconfig(evil).isEmpty())
    }

    @Test
    fun `user name templates fill from the address`() {
        assertEquals("info@flirtycall.me", MailDiscovery.username("%EMAILADDRESS%", "info@flirtycall.me"))
        assertEquals("info", MailDiscovery.username("%EMAILLOCALPART%", "info@flirtycall.me"))
        assertEquals("info@flirtycall.me", MailDiscovery.username("", "info@flirtycall.me"))
    }

    // ---- what to try and what to pick --------------------------------------------------

    @Test
    fun `guesses start from the webmail host, then imap and mail on both domains`() {
        val hosts = MailDiscovery.guesses("info@flirtycall.me", "https://rc.zionbit.eu/?_task=mail")
            .map { it.host }.distinct()
        assertEquals(
            listOf("rc.zionbit.eu", "imap.flirtycall.me", "mail.flirtycall.me", "imap.zionbit.eu", "mail.zionbit.eu"),
            hosts,
        )
        assertEquals("zionbit.eu", MailDiscovery.parentDomain("rc.zionbit.eu"))
    }

    @Test
    fun `autoconfig urls are https only and carry the address`() {
        val urls = MailDiscovery.autoconfigUrls("info@flirtycall.me")
        assertTrue(urls.all { it.startsWith("https://") })
        assertTrue(urls[0].startsWith("https://autoconfig.flirtycall.me/mail/config-v1.1.xml?emailaddress=info%40flirtycall.me"))
    }

    private fun probe(host: String, port: Int = 993, trusted: Boolean = true, source: Source = Source.GUESS, imap: Boolean = true, names: List<String> = emptyList()) =
        Probe(
            Candidate(host, port, if (port == 143) MailSecurity.STARTTLS else MailSecurity.TLS, source),
            imap = imap,
            trusted = trusted,
            certificateNames = names,
        )

    @Test
    fun `a trusted certificate beats a better source, TLS beats STARTTLS`() {
        val pick = MailDiscovery.choose(
            listOf(
                probe("selfsigned.example", source = Source.AUTOCONFIG, trusted = false),
                probe("mx1.example", port = 143, source = Source.MX),
                probe("mx1.example", source = Source.MX),
                probe("down.example", imap = false, source = Source.AUTOCONFIG),
            ),
        )!!
        assertEquals("mx1.example", pick.candidate.host)
        assertEquals(993, pick.candidate.port)
        assertNull(MailDiscovery.choose(listOf(probe("x", imap = false))))
    }

    @Test
    fun `a host answering under another name offers the names its certificate is for`() {
        val rc = probe(
            "rc.zionbit.eu", trusted = false,
            names = listOf("autoconfig.zionbit.cz", "autodiscover.zionbit.cz", "mail.zionbit.eu", "mx1.zionbit.eu", "*.zionbit.eu"),
        )
        assertEquals(listOf("mail.zionbit.eu", "mx1.zionbit.eu"), MailDiscovery.renamed(rc))
        assertTrue(MailDiscovery.renamed(probe("ok", trusted = true, names = listOf("a.b"))).isEmpty())
    }

    // ---- the search, with fakes for the network ----------------------------------------

    private class FakeProber(val answers: Map<String, Probe>) : MailDiscoverer.Prober {
        val asked = mutableListOf<String>()
        override suspend fun probe(candidate: Candidate, timeoutMs: Int): Probe {
            synchronized(asked) { asked += candidate.key }
            return answers[candidate.key]?.copy(candidate = candidate) ?: Probe(candidate, imap = false)
        }
    }

    private val noDns = MailDiscoverer.Dns { _, _ -> null }
    private val noFetch = MailDiscoverer.Fetch { null }

    @Test
    fun `the setup in the issue, MX leads to the server and the webmail host is renamed to it`() {
        val mx = response("flirtycall.me", 15, { _ -> byteArrayOf(0, 10) + name("mx1.zionbit.eu") })
        val dns = MailDiscoverer.Dns { n, t -> if (n == "flirtycall.me" && t == 15) mx else null }
        val prober = FakeProber(
            mapOf(
                "mx1.zionbit.eu:993" to probe("mx1.zionbit.eu"),
                "mx1.zionbit.eu:143" to probe("mx1.zionbit.eu", port = 143),
                "rc.zionbit.eu:993" to probe("rc.zionbit.eu", trusted = false, names = listOf("mx1.zionbit.eu", "mail.zionbit.eu")),
            ),
        )
        val found = runBlocking {
            MailDiscoverer(dns, noFetch, prober).discover("info@flirtycall.me", "https://rc.zionbit.eu")
        }
        assertEquals("mx1.zionbit.eu", found.probe!!.candidate.host)
        assertEquals(993, found.probe!!.candidate.port)
        assertEquals(Source.MX, found.probe!!.candidate.source)
    }

    @Test
    fun `with no DNS at all, a webmail host on the same box is renamed to the certificate's name`() {
        val prober = FakeProber(
            mapOf(
                "rc.zionbit.eu:993" to probe("rc.zionbit.eu", trusted = false, names = listOf("mx1.zionbit.eu")),
                "mx1.zionbit.eu:993" to probe("mx1.zionbit.eu"),
            ),
        )
        val found = runBlocking { MailDiscoverer(noDns, noFetch, prober).discover("info@flirtycall.me", "https://rc.zionbit.eu") }
        assertEquals("mx1.zionbit.eu", found.probe!!.candidate.host)
        assertEquals("rc.zionbit.eu", found.renamedFrom)
    }

    @Test
    fun `autoconfig wins and carries its user name template`() {
        val fetch = MailDiscoverer.Fetch { url ->
            if (url.startsWith("https://autoconfig.example.org/")) {
                "<?xml version=\"1.0\"?><clientConfig><emailProvider><incomingServer type=\"imap\"><hostname>imap.host.net</hostname>" +
                    "<port>993</port><socketType>SSL</socketType><username>%EMAILLOCALPART%</username></incomingServer></emailProvider></clientConfig>"
            } else {
                null
            }
        }
        val prober = FakeProber(mapOf("imap.host.net:993" to probe("imap.host.net"), "mail.example.org:993" to probe("mail.example.org")))
        val found = runBlocking { MailDiscoverer(noDns, fetch, prober).discover("me@example.org", "") }
        assertEquals("imap.host.net", found.probe!!.candidate.host)
        assertEquals("%EMAILLOCALPART%", found.probe!!.candidate.username)
    }

    @Test
    fun `nothing answering is nothing found`() {
        val found = runBlocking { MailDiscoverer(noDns, noFetch, FakeProber(emptyMap())).discover("me@nowhere.example", "") }
        assertNull(found.probe)
        assertTrue(found.tried > 0)
    }

    @Test
    fun `a search that runs past its budget ends with nothing rather than hanging setup`() {
        val slow = MailDiscoverer.Prober { c, _ -> kotlinx.coroutines.delay(10_000); Probe(c, imap = true, trusted = true) }
        val started = System.currentTimeMillis()
        val found = runBlocking { MailDiscoverer(noDns, noFetch, slow, budgetMs = 300).discover("me@example.org", "") }
        assertNull(found.probe)
        assertTrue(System.currentTimeMillis() - started < 5_000)
    }

    @Test
    fun `the real prober greets a server without signing in and reads its certificate`() {
        TinyImap(implicitTls = TinyTls.selfSigned()).use { imap ->
            val probe = runBlocking {
                MailDiscoverer.Prober.Imap().probe(Candidate("127.0.0.1", imap.port, MailSecurity.TLS, Source.GUESS), 3_000)
            }
            assertTrue(probe.imap)
            assertFalse("a self-signed certificate is not trusted", probe.trusted)
            assertEquals(listOf("localhost"), probe.certificateNames)
            assertTrue("credentials crossed the wire", imap.received.none { it.contains("LOGIN") || it.contains("AUTHENTICATE") })
        }
    }

    @Test
    fun `the real prober upgrades STARTTLS and refuses a server without it`() {
        TinyImap(startTls = TinyTls.selfSigned()).use { imap ->
            val probe = runBlocking {
                MailDiscoverer.Prober.Imap().probe(Candidate("127.0.0.1", imap.port, MailSecurity.STARTTLS, Source.GUESS), 3_000)
            }
            assertTrue(probe.note, probe.imap)
        }
        TinyImap().use { imap ->
            val probe = runBlocking {
                MailDiscoverer.Prober.Imap().probe(Candidate("127.0.0.1", imap.port, MailSecurity.STARTTLS, Source.GUESS), 3_000)
            }
            assertFalse(probe.imap)
        }
    }
}
