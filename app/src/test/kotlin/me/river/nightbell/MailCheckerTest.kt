package me.river.nightbell

import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import me.river.nightbell.data.check.MailChecker
import me.river.nightbell.domain.FailureKind
import me.river.nightbell.domain.MailSecurity
import me.river.nightbell.domain.MailState
import me.river.nightbell.domain.MailText
import me.river.nightbell.domain.MailWatch
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.TlsTrust
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MailCheckerTest {

    private class Credentials(var password: String? = "secret") : MailChecker.Credentials {
        var refusedWith: String? = null

        override suspend fun password(monitorId: String): MailChecker.Stored {
            val value = password ?: return MailChecker.Stored.None
            return MailChecker.Stored.Password(value, refusedBefore = refusedWith == value)
        }

        override suspend fun refused(monitorId: String, password: String) {
            refusedWith = password
        }
    }

    private val servers = mutableListOf<TinyImap>()

    @After
    fun tearDown() = servers.forEach { it.close() }

    private fun server(implicit: TinyTls.Identity? = TinyTls.selfSigned(), starttls: TinyTls.Identity? = null) =
        TinyImap(implicitTls = implicit, startTls = starttls).also { servers += it }

    private fun monitor(
        port: Int,
        security: MailSecurity = MailSecurity.TLS,
        trust: TlsTrust = TlsTrust.ANY,
        user: String = "me@example.com",
        folder: String = "INBOX",
        timeout: Int = 5,
    ) = Monitor(
        id = "m1",
        kind = MonitorKind.MAILBOX,
        tlsTrust = trust,
        timeoutSeconds = timeout,
        mail = MailWatch(host = "127.0.0.1", port = port, security = security, username = user, folder = folder),
    )

    private fun check(
        monitor: Monitor,
        credentials: Credentials = Credentials(),
        previous: MailState = MailState(),
        deliberate: Boolean = false,
        checker: MailChecker = MailChecker(credentials),
    ) = runBlocking { checker.check(monitor, previous, "", deliberate) }

    @Test
    fun `signs in over TLS with AUTH PLAIN and reads the counters`() {
        val imap = server()
        imap.deliver("ann@example.com", "one", seen = true)
        imap.deliver("bob@example.com", "two")
        val outcome = check(monitor(imap.port))
        val result = outcome.result!!
        assertTrue(result.message, result.ok)
        assertEquals("Signed in. 1 unread of 2 in the inbox, in ${result.latencyMs}ms", result.message)
        assertEquals(3L, outcome.snapshot!!.uidNext)
        assertEquals(1700000000L, outcome.snapshot!!.uidValidity)
        assertTrue(result.certSpki.startsWith("sha256/"))
        assertTrue(imap.received.any { it.contains("AUTHENTICATE PLAIN ") })
        assertTrue(imap.received.any { it.endsWith("LOGOUT") })
        assertTrue("never EXAMINE on a first check", imap.received.none { it.contains("EXAMINE") })
    }

    @Test
    fun `Dovecot's waiting lines before the real greeting are read through`() {
        val imap = server().apply {
            preamble = listOf(
                "* OK Waiting for authentication process to respond..",
                "* OK Waiting for authentication process to respond..",
            )
        }
        val result = check(monitor(imap.port)).result!!
        assertTrue(result.message, result.ok)
    }

    @Test
    fun `without SASL-IR the PLAIN token waits for the continuation`() {
        val imap = server().apply { saslIr = false }
        assertTrue(check(monitor(imap.port)).result!!.ok)
        assertTrue(imap.received.any { it.endsWith("AUTHENTICATE PLAIN") })
    }

    @Test
    fun `LOGIN with a password a quoted string cannot carry goes as a literal and waits for the go ahead`() {
        val imap = server().apply {
            authPlain = false
            literalPlus = false
            users["me@example.com"] = "heslo-žluťoučký \"kůň\""
        }
        val result = check(monitor(imap.port), Credentials("heslo-žluťoučký \"kůň\"")).result!!
        assertTrue(result.message, result.ok)
    }

    @Test
    fun `LOGIN quotes an ASCII password with quotes and backslashes in it`() {
        val imap = server().apply {
            authPlain = false
            users["me@example.com"] = "a\"b\\c d"
        }
        assertTrue(check(monitor(imap.port), Credentials("a\"b\\c d")).result!!.ok)
    }

    @Test
    fun `a refused password is reported, recorded, and not tried again until it changes`() {
        val imap = server()
        val credentials = Credentials("wrong")
        val first = check(monitor(imap.port), credentials)
        assertFalse(first.result!!.ok)
        assertEquals(FailureKind.MAIL_LOGIN, first.result!!.failureKind)
        assertTrue(first.result!!.detail, first.result!!.detail.contains("AUTHENTICATIONFAILED"))
        assertTrue(first.refused)
        assertEquals("wrong", credentials.refusedWith)

        val before = imap.connections
        val second = check(monitor(imap.port), credentials)
        assertNull("no verdict without trying", second.result)
        assertEquals("no connection was made", before, imap.connections)

        val deliberate = check(monitor(imap.port), credentials, deliberate = true)
        assertNotNull(deliberate.result)
        assertEquals(before + 1, imap.connections)

        credentials.password = "secret"
        assertTrue(check(monitor(imap.port), credentials).result!!.ok)
    }

    @Test
    fun `a temporary refusal is not recorded as a wrong password`() {
        val imap = server().apply { refusal = "NO [UNAVAILABLE] Try again later" }
        val credentials = Credentials("not-the-password")
        val outcome = check(monitor(imap.port), credentials)
        assertEquals(FailureKind.UNKNOWN, outcome.result!!.failureKind)
        assertFalse(outcome.refused)
        assertNull(credentials.refusedWith)
    }

    @Test
    fun `the password is scrubbed out of anything the server echoed`() {
        val imap = server().apply { refusal = "NO [AUTHENTICATIONFAILED] bad password hunter2hunter2" }
        val outcome = check(monitor(imap.port), Credentials("hunter2hunter2"))
        assertFalse(outcome.result!!.detail.contains("hunter2hunter2"))
    }

    @Test
    fun `STARTTLS upgrades before signing in`() {
        val imap = server(implicit = null, starttls = TinyTls.selfSigned())
        val result = check(monitor(imap.port, security = MailSecurity.STARTTLS)).result!!
        assertTrue(result.message, result.ok)
        val startTlsAt = imap.received.indexOfFirst { it.endsWith("STARTTLS") }
        val authAt = imap.received.indexOfFirst { it.contains("AUTHENTICATE") || it.contains("LOGIN") }
        assertTrue(startTlsAt in 0 until authAt)
    }

    @Test
    fun `a server that does not offer STARTTLS never receives the password`() {
        val imap = server(implicit = null, starttls = null)
        val result = check(monitor(imap.port, security = MailSecurity.STARTTLS)).result!!
        assertEquals(FailureKind.TLS, result.failureKind)
        assertEquals("The server does not offer STARTTLS", result.message)
        assertTrue(imap.received.none { it.contains("LOGIN") || it.contains("AUTHENTICATE") })
    }

    @Test
    fun `system trust checks the host name, not only the chain`() {
        val held = HeldCertificate.Builder().commonName("mail").addSubjectAlternativeName("localhost").build()
        val imap = server(implicit = TinyTls.Identity(held))
        val trusting = HandshakeCertificates.Builder().addTrustedCertificate(held.certificate).build()
        val checker = MailChecker(Credentials(), systemTls = { trusting.sslSocketFactory() })
        val result = check(monitor(imap.port, trust = TlsTrust.SYSTEM), checker = checker).result!!
        assertEquals(FailureKind.TLS, result.failureKind)
        assertTrue(result.message, result.message.contains("not 127.0.0.1"))
    }

    @Test
    fun `system trust passes a certificate issued for the address`() {
        val held = HeldCertificate.Builder().commonName("mail").addSubjectAlternativeName("127.0.0.1").build()
        val imap = server(implicit = TinyTls.Identity(held))
        val trusting = HandshakeCertificates.Builder().addTrustedCertificate(held.certificate).build()
        val checker = MailChecker(Credentials(), systemTls = { trusting.sslSocketFactory() })
        val result = check(monitor(imap.port, trust = TlsTrust.SYSTEM), checker = checker).result!!
        assertTrue(result.message, result.ok)
    }

    @Test
    fun `a self-signed server under system trust is a TLS failure that says what to do`() {
        val imap = server()
        val result = check(monitor(imap.port, trust = TlsTrust.SYSTEM)).result!!
        assertEquals(FailureKind.TLS, result.failureKind)
        assertTrue(result.advice, result.advice.contains("Self-signed"))
    }

    @Test
    fun `TLS on the STARTTLS port says so`() {
        val imap = server(implicit = null, starttls = TinyTls.selfSigned())
        val result = check(monitor(imap.port).let { it.copy(mail = it.mail.copy(security = MailSecurity.TLS)) }).result!!
        assertEquals(FailureKind.TLS, result.failureKind)
    }

    @Test
    fun `when something arrived, the new envelopes are fetched read only`() {
        val imap = server()
        repeat(3) { imap.deliver("old@example.com", "old $it") }
        val seed = check(monitor(imap.port))
        val state = MailState(seeded = true, uidValidity = seed.snapshot!!.uidValidity, uidNext = seed.snapshot!!.uidNext)
        imap.deliver("alerts@monitor.example.com", "=?UTF-8?Q?Disk_=C4=8Dek=C3=A1?=", fromName = "Monitor")
        imap.deliver("ann@example.com", "Žádost o schůzku")
        val outcome = check(monitor(imap.port), previous = state)
        val fresh = outcome.snapshot!!.fresh
        assertEquals(listOf(4L, 5L), fresh.map { it.uid })
        assertEquals("Disk čeká", fresh[0].subject)
        assertEquals("Monitor", fresh[0].fromName)
        assertEquals("Žádost o schůzku", fresh[1].subject)
        assertEquals("ann@example.com", fresh[1].fromAddress)
        assertTrue(fresh[1].receivedAt > 0)
        assertTrue(imap.received.any { it.contains("EXAMINE") })
        assertTrue("never SELECT, which could mark mail read", imap.received.none { it.contains("SELECT") })
    }

    @Test
    fun `nothing new means no envelope is fetched`() {
        val imap = server()
        imap.deliver("old@example.com", "old")
        val state = MailState(seeded = true, uidValidity = 1700000000, uidNext = 2)
        val outcome = check(monitor(imap.port), previous = state)
        assertTrue(outcome.snapshot!!.fresh.isEmpty())
        assertTrue(imap.received.none { it.contains("FETCH") || it.contains("EXAMINE") })
    }

    @Test
    fun `the star quirk on a server, n colon star past the end, fetches nothing`() {
        val imap = server()
        imap.deliver("old@example.com", "old")
        // Stored uidNext claims 5 but the server only reached 2: a stale state
        // after a restore. UIDNEXT 2 is not past 5, so nothing is asked for.
        val state = MailState(seeded = true, uidValidity = 1700000000, uidNext = 5)
        assertTrue(check(monitor(imap.port), previous = state).snapshot!!.fresh.isEmpty())
    }

    @Test
    fun `a flood fetches the newest twenty and counts the rest`() {
        val imap = server()
        val state = MailState(seeded = true, uidValidity = 1700000000, uidNext = 1)
        repeat(45) { imap.deliver("bulk@example.com", "n$it") }
        val snapshot = check(monitor(imap.port), previous = state).snapshot!!
        assertEquals(20, snapshot.fresh.size)
        assertEquals(45, snapshot.freshCount)
        assertEquals(45L, snapshot.fresh.last().uid)
    }

    @Test
    fun `a server that leaves UIDNEXT out of STATUS is read through EXAMINE`() {
        val imap = server().apply { statusOmitsUidNext = true }
        imap.deliver("a@b.c", "x")
        val snapshot = check(monitor(imap.port)).snapshot!!
        assertEquals(2L, snapshot.uidNext)
    }

    @Test
    fun `a non ASCII folder goes out in modified UTF-7`() {
        val imap = server()
        imap.folders[MailText.encodeFolder("Doručená pošta")] = TinyImap.Folder(5)
        val result = check(monitor(imap.port, folder = "Doručená pošta")).result!!
        assertTrue(result.message, result.ok)
        assertTrue(result.message.contains("Doručená pošta"))
    }

    @Test
    fun `folders are listed for the picker, decoded, inbox first, unopenable parents left out`() {
        val imap = server()
        imap.folders["Archive"] = TinyImap.Folder(2)
        imap.folders[MailText.encodeFolder("Doručená pošta")] = TinyImap.Folder(3)
        imap.folders["Old Stuff"] = TinyImap.Folder(4)
        imap.noselect += "Projects"
        val outcome = runBlocking { MailChecker(Credentials()).folders(monitor(imap.port), "") }
        assertEquals(listOf("INBOX", "Archive", "Doručená pošta", "Old Stuff"), outcome.folders)
        assertTrue(imap.received.none { it.contains("STATUS") || it.contains("EXAMINE") })
    }

    @Test
    fun `listing with a wrong password says so and lists nothing`() {
        val imap = server()
        val outcome = runBlocking { MailChecker(Credentials("nope")).folders(monitor(imap.port), "") }
        assertNull(outcome.folders)
        assertEquals(FailureKind.MAIL_LOGIN, outcome.result!!.failureKind)
    }

    @Test
    fun `a folder that does not exist is a config problem with the server's words`() {
        val imap = server()
        val result = check(monitor(imap.port, folder = "Nope")).result!!
        assertEquals(FailureKind.BAD_CONFIG, result.failureKind)
        assertEquals("No folder named Nope", result.message)
        assertTrue(result.detail.contains("NONEXISTENT"))
    }

    @Test
    fun `a BYE greeting is the server turning us away`() {
        val imap = server().apply { greeting = "* BYE Too many connections from your IP" }
        val result = check(monitor(imap.port)).result!!
        assertEquals(FailureKind.CONNECT, result.failureKind)
        assertTrue(result.detail.contains("Too many connections"))
    }

    @Test
    fun `pointed at something that is not IMAP`() {
        val imap = server(implicit = null).apply { greeting = "HTTP/1.1 400 Bad Request" }
        val result = check(monitor(imap.port, security = MailSecurity.STARTTLS)).result!!
        assertEquals(FailureKind.BAD_CONFIG, result.failureKind)
        assertTrue(result.message.startsWith("This is not an IMAP server"))
    }

    @Test
    fun `a server that never answers times out`() {
        val imap = server(implicit = null).apply { silent = true }
        val result = check(monitor(imap.port, security = MailSecurity.STARTTLS, timeout = 1)).result!!
        assertEquals(FailureKind.TIMEOUT, result.failureKind)
    }

    @Test
    fun `nothing listening is a refused connection`() {
        val port = ServerSocket(0).use { it.localPort }
        val result = check(monitor(port)).result!!
        assertEquals(FailureKind.CONNECT, result.failureKind)
    }

    @Test
    fun `no saved password fails without touching the network`() {
        val imap = server()
        val result = check(monitor(imap.port), Credentials(null)).result!!
        assertEquals(FailureKind.BAD_CONFIG, result.failureKind)
        assertEquals(0, imap.connections)
    }
}
