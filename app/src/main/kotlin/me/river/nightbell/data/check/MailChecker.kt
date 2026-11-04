package me.river.nightbell.data.check

import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import me.river.nightbell.domain.CheckResult
import me.river.nightbell.domain.FailureKind
import me.river.nightbell.domain.MailEvents
import me.river.nightbell.domain.MailMessage
import me.river.nightbell.domain.MailSecurity
import me.river.nightbell.domain.MailSnapshot
import me.river.nightbell.domain.MailState
import me.river.nightbell.domain.MailText
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.Secrets
import okhttp3.internal.tls.OkHostnameVerifier

/**
 * Checks a [me.river.nightbell.domain.MonitorKind.MAILBOX] monitor: signs in over
 * IMAP, reads the folder's counters, and when something arrived since last time,
 * the envelopes of what did. Never a message body, never a write: the folder is
 * opened with EXAMINE, which is read only, so a check cannot mark anything read.
 *
 * Answers two questions at once, and they are kept apart. Whether the mail
 * server is up is the verdict, and goes through the same tracks as every other
 * outage. What arrived is [MailSnapshot], and is judged by [MailEvents].
 */
class MailChecker(
    private val credentials: Credentials,
    /** The platform's own trust store. A test swaps in one that trusts its CA. */
    private val systemTls: () -> SSLSocketFactory = { SSLSocketFactory.getDefault() as SSLSocketFactory },
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    /** Where the password is. The vault on a phone, a map in a test. */
    interface Credentials {
        suspend fun password(monitorId: String): Stored

        /** The server refused [password]. It is not tried again on a schedule until it changes. */
        suspend fun refused(monitorId: String, password: String)
    }

    sealed interface Stored {
        data object None : Stored

        /** Held something that would not open, a lost key after a restore. */
        data object Unreadable : Stored

        data class Password(val value: String, val refusedBefore: Boolean) : Stored
    }

    /**
     * @property result null when nothing was tried: the stored password was
     *   refused before and nobody has changed it. Not a verdict about the server.
     * @property snapshot what the folder held, when the check got that far.
     */
    data class Outcome(
        val result: CheckResult?,
        val snapshot: MailSnapshot? = null,
        val refused: Boolean = false,
        /** Selectable folders, as a person would read their names. Only from [folders]. */
        val folders: List<String>? = null,
    )

    /**
     * Signs in and lists the folders, for setup's picker. Deliberate, like Test:
     * somebody pressed a button to ask. Folders the server marks \Noselect are
     * left out, because a check could never open them.
     */
    suspend fun folders(monitor: Monitor, certPin: String): Outcome = check(monitor, MailState(), certPin, deliberate = true, listOnly = true)

    /**
     * @param deliberate a person asked for this check, so a password refused
     *   before is tried again. The once-per-change rule is about Nightbell
     *   retrying by itself, the same as for a health monitor's sign-in.
     */
    suspend fun check(
        monitor: Monitor,
        previous: MailState,
        certPin: String,
        deliberate: Boolean,
        listOnly: Boolean = false,
    ): Outcome {
        val password = when (val stored = credentials.password(monitor.id)) {
            Stored.None -> return Outcome(
                failed(
                    FailureKind.BAD_CONFIG,
                    "No password is saved",
                    hint = "Edit the monitor and enter the mailbox password.",
                ),
            )
            Stored.Unreadable -> return Outcome(
                failed(
                    FailureKind.BAD_CONFIG,
                    "The saved password could not be opened",
                    detail = "This happens after a backup is restored onto another phone, because the key it " +
                        "was sealed with stayed on the old one.",
                    hint = "Edit the monitor and enter the password again.",
                ),
            )
            is Stored.Password -> {
                if (stored.refusedBefore && !deliberate) return Outcome(null)
                stored.value
            }
        }
        val outcome = runInterruptible(Dispatchers.IO) { talk(monitor, previous, certPin, password, listOnly) }
        if (outcome.refused) credentials.refused(monitor.id, password)
        // The password is the one thing a server's own error text might echo.
        val result = outcome.result?.let {
            it.copy(
                message = Secrets.scrub(it.message, password),
                detail = Secrets.scrub(it.detail, password),
            )
        }
        return outcome.copy(result = result)
    }

    private fun talk(monitor: Monitor, previous: MailState, certPin: String, password: String, listOnly: Boolean): Outcome {
        val watch = monitor.mail
        val host = watch.cleanHost
        val port = watch.effectivePort
        val timeoutMs = monitor.timeoutSeconds.coerceAtLeast(1) * 1000
        val started = System.nanoTime()
        var leaf: X509Certificate? = null
        var socket: Socket? = null
        try {
            val (factory, session) = if (monitor.tlsTrust == me.river.nightbell.domain.TlsTrust.SYSTEM) {
                systemTls() to null
            } else {
                TlsTrustConfig.rawSocketFactory(monitor.tlsTrust, certPin)
            }
            val plain = Socket().apply {
                soTimeout = timeoutMs
                connect(InetSocketAddress(host, port), timeoutMs)
            }
            socket = plain
            var imap: Imap.Session
            if (watch.security == MailSecurity.TLS) {
                val tls = handshake(factory, plain, host, port, session == null)
                socket = tls
                leaf = session?.leaf ?: tls.session.peerCertificates.firstOrNull() as? X509Certificate
                imap = Imap.Session(tls.inputStream, tls.outputStream)
                imap.greeting().let { if (it.kind == "BYE") return Outcome(byeAtGreeting(it)) }
            } else {
                imap = Imap.Session(plain.inputStream, plain.outputStream)
                imap.greeting().let { if (it.kind == "BYE") return Outcome(byeAtGreeting(it)) }
                if (imap.capabilities.isEmpty()) imap.command("CAPABILITY")
                if (!imap.has("STARTTLS")) {
                    return Outcome(
                        failed(
                            FailureKind.TLS,
                            "The server does not offer STARTTLS",
                            detail = "$host:$port answered IMAP without the upgrade, so signing in would send " +
                                "the password unencrypted. Nightbell stopped before that.",
                            hint = "Use TLS on port 993 if the server has it.",
                        ),
                    )
                }
                val upgrade = imap.command("STARTTLS")
                if (!upgrade.status.ok) {
                    return Outcome(failed(FailureKind.TLS, "The server refused STARTTLS", detail = upgrade.status.text))
                }
                val tls = handshake(factory, plain, host, port, session == null)
                socket = tls
                leaf = session?.leaf ?: tls.session.peerCertificates.firstOrNull() as? X509Certificate
                imap = Imap.Session(tls.inputStream, tls.outputStream)
                // Capabilities learned before the upgrade must not be trusted after it.
                imap.command("CAPABILITY")
            }
            if (imap.capabilities.isEmpty()) imap.command("CAPABILITY")

            val login = signIn(imap, watch.username.trim(), password)
            if (!login.status.ok) {
                val temporary = login.status.codeName in setOf("UNAVAILABLE", "INUSE", "LIMIT", "SERVERBUG")
                return Outcome(
                    failed(
                        if (temporary) FailureKind.UNKNOWN else FailureKind.MAIL_LOGIN,
                        if (temporary) "The server could not sign in right now" else "The server refused the sign-in",
                        // The advice goes in the detail as well, because the detail is what
                        // the monitor's screen shows, and "not tried again" is the one
                        // thing somebody looking at a stopped monitor has to know.
                        detail = if (temporary) {
                            serverSaid(login.status)
                        } else {
                            serverSaid(login.status) + "\n\n" + FailureKind.MAIL_LOGIN.hint
                        },
                        hint = when {
                            temporary -> ""
                            // The kind's own advice covers a server nobody picked a chip for.
                            watch.preset == me.river.nightbell.domain.MailPreset.OTHER -> ""
                            else -> watch.preset.passwordHint + " Not tried again until the password is edited."
                        },
                        cert = leaf,
                    ),
                    refused = !temporary,
                )
            }
            imap.learnCapabilities(login.status)

            if (listOnly) {
                val list = imap.command("LIST", "\"\"", "\"*\"")
                runCatching { imap.command("LOGOUT") }
                if (!list.status.ok) {
                    return Outcome(failed(FailureKind.BAD_CONFIG, "The server would not list its folders", detail = serverSaid(list.status)))
                }
                val names = list.data("LIST").mapNotNull(Imap::listedFolder)
                    .map(MailText::decodeFolder)
                    .distinct()
                    .sortedWith(compareBy<String> { !it.equals("INBOX", ignoreCase = true) }.thenBy { it.lowercase() })
                return Outcome(
                    CheckResult(ok = true, latencyMs = 0, message = "${names.size} folders", at = nowMs()).withCert(leaf),
                    folders = names,
                )
            }

            val folder = Imap.astring(MailText.encodeFolder(watch.cleanFolder))
            val status = imap.command("STATUS", folder, "(MESSAGES UNSEEN UIDNEXT UIDVALIDITY)")
            if (!status.status.ok) return Outcome(noFolder(watch.cleanFolder, status.status, leaf))
            val items = status.data("STATUS").lastOrNull()?.let(Imap::statusItems).orEmpty()
            var uidNext = items["UIDNEXT"]
            var uidValidity = items["UIDVALIDITY"]
            val messages = items["MESSAGES"]?.toInt() ?: -1
            val unseen = items["UNSEEN"]?.toInt() ?: -1

            val wantFresh = previous.seeded && (uidValidity == null || uidValidity == previous.uidValidity) &&
                (uidNext == null || uidNext > previous.uidNext)
            var fresh = emptyList<MailMessage>()
            var freshCount = 0
            if (uidNext == null || uidValidity == null || wantFresh) {
                val examine = imap.command("EXAMINE", folder)
                if (!examine.status.ok) return Outcome(noFolder(watch.cleanFolder, examine.status, leaf))
                val codes = examine.codes()
                uidNext = uidNext ?: codes["UIDNEXT"]?.trim()?.toLongOrNull()
                uidValidity = uidValidity ?: codes["UIDVALIDITY"]?.trim()?.toLongOrNull() ?: 0L
                if (uidNext == null) {
                    val all = imap.command("UID SEARCH ALL")
                    uidNext = (Imap.searchUids(all.data("SEARCH")).maxOrNull() ?: 0L) + 1
                }
                val stillWanted = previous.seeded && uidValidity == previous.uidValidity && uidNext > previous.uidNext
                if (stillWanted) {
                    val search = imap.command("UID SEARCH UID ${previous.uidNext.coerceAtLeast(1)}:*")
                    // `n:*` with n past the last uid still matches the last message, because
                    // `*` is the highest uid in use. Filtered here, not trusted.
                    val uids = Imap.searchUids(search.data("SEARCH")).filter { it >= previous.uidNext }.distinct().sorted()
                    freshCount = uids.size
                    val wanted = uids.takeLast(MailEvents.FETCH_LIMIT)
                    if (wanted.isNotEmpty()) {
                        val fetch = imap.command("UID FETCH ${wanted.joinToString(",")} (UID FLAGS INTERNALDATE ENVELOPE)")
                        fresh = fetch.data("FETCH").mapNotNull(Imap::fetchMessage).filter { it.uid in wanted }
                    }
                }
            }
            val latencyMs = (System.nanoTime() - started) / 1_000_000
            runCatching { imap.command("LOGOUT") }
            val snapshot = MailSnapshot(
                uidValidity = uidValidity ?: 0L,
                uidNext = uidNext ?: 0L,
                messages = messages,
                unseen = unseen,
                fresh = fresh,
                freshCount = maxOf(freshCount, fresh.size),
            )
            return Outcome(
                CheckResult(
                    ok = true,
                    latencyMs = latencyMs,
                    message = summary(snapshot, watch.cleanFolder, latencyMs),
                    at = nowMs(),
                ).withCert(leaf),
                snapshot = snapshot,
            )
        } catch (error: UnknownHostException) {
            return Outcome(failed(FailureKind.DNS, "Host not found", detail = "$host could not be resolved."))
        } catch (error: SocketTimeoutException) {
            return Outcome(
                failed(
                    FailureKind.TIMEOUT,
                    "No answer within ${monitor.timeoutSeconds}s",
                    detail = "$host:$port. ${error.message.orEmpty()}".trim(),
                    cert = leaf,
                ),
            )
        } catch (error: ConnectException) {
            return Outcome(failed(FailureKind.CONNECT, "Connection refused", detail = "$host:$port. ${error.message.orEmpty()}".trim()))
        } catch (error: NoRouteToHostException) {
            return Outcome(failed(FailureKind.CONNECT, "No route to the server", detail = "$host:$port"))
        } catch (error: SSLException) {
            return Outcome(tlsFailure(error, watch.security, port))
        } catch (error: Imap.ProtocolException) {
            return Outcome(
                failed(
                    FailureKind.BAD_CONFIG,
                    error.message ?: "Not an IMAP server",
                    detail = "$host:$port",
                    hint = if (watch.security == MailSecurity.STARTTLS && port == 993) {
                        "Port 993 is usually TLS from the start. Pick TLS instead of STARTTLS."
                    } else {
                        "Check the port. IMAP is 993 with TLS, 143 with STARTTLS."
                    },
                    cert = leaf,
                ),
            )
        } catch (error: IOException) {
            return Outcome(
                failed(
                    FailureKind.CONNECT,
                    "The connection broke off",
                    detail = "$host:$port. ${error.message.orEmpty()}".trim(),
                    cert = leaf,
                ),
            )
        } finally {
            runCatching { socket?.close() }
        }
    }

    private fun handshake(
        factory: SSLSocketFactory,
        plain: Socket,
        host: String,
        port: Int,
        verifyName: Boolean,
    ): SSLSocket {
        val tls = factory.createSocket(plain, host, port, true) as SSLSocket
        tls.startHandshake()
        if (verifyName && !OkHostnameVerifier.verify(host, tls.session)) {
            val names = (tls.session.peerCertificates.firstOrNull() as? X509Certificate)
                ?.subjectAlternativeNames?.mapNotNull { it.getOrNull(1)?.toString() }.orEmpty()
            throw SSLPeerUnverifiedException(
                "The certificate is for ${names.joinToString(", ").ifEmpty { "another name" }}, not $host",
            )
        }
        return tls
    }

    /** A server that says BYE in its greeting is usually full, or has banned this address. */
    private fun byeAtGreeting(greeting: Imap.Status): CheckResult =
        failed(
            FailureKind.CONNECT,
            "The server turned the connection away",
            detail = serverSaid(greeting),
            hint = "A server that says this at once is usually full, or has blocked this address " +
                "after failed sign-ins.",
        )

    /**
     * PLAIN when offered, because it carries any password in UTF-8. LOGIN
     * otherwise, which is the one every server has, unless the server has said
     * LOGINDISABLED, which most say before STARTTLS and never after it.
     */
    private fun signIn(imap: Imap.Session, user: String, password: String): Imap.Reply =
        if (imap.has("AUTH=PLAIN")) {
            imap.authenticatePlain(user, password)
        } else if (imap.has("LOGINDISABLED")) {
            Imap.Reply(
                Imap.Status("NO", "", "The server offers no sign-in Nightbell can use (LOGINDISABLED, no AUTH=PLAIN)"),
                emptyList(),
            )
        } else {
            imap.command("LOGIN", Imap.astring(user), Imap.astring(password))
        }

    private fun serverSaid(status: Imap.Status): String {
        val code = if (status.code.isNotEmpty()) "[${status.code}] " else ""
        return "The server said: $code${status.text}".trim()
    }

    private fun noFolder(folder: String, status: Imap.Status, cert: X509Certificate?): CheckResult =
        failed(
            FailureKind.BAD_CONFIG,
            "No folder named $folder",
            detail = serverSaid(status),
            hint = "Folder names are the server's own. INBOX always exists.",
            cert = cert,
        )

    private fun tlsFailure(error: SSLException, security: MailSecurity, port: Int): CheckResult {
        val pin = TlsTrustConfig.pinMismatch(error)
        val plainPort = security == MailSecurity.TLS && port == 143
        val message = when {
            pin != null -> "The server's key changed since it was pinned"
            error is SSLPeerUnverifiedException -> error.message ?: "The certificate is for another name"
            error is SSLHandshakeException && error.message.orEmpty().contains("path", ignoreCase = true) ->
                "The certificate is not trusted"
            plainPort -> "Port 143 does not speak TLS"
            else -> "TLS handshake failed"
        }
        return failed(
            FailureKind.TLS,
            message,
            detail = pin?.let { "Pinned ${it.expected}, got ${it.actual}" } ?: error.message.orEmpty(),
            hint = when {
                plainPort -> "Port 143 is STARTTLS. Pick STARTTLS, or use 993."
                pin != null -> "If the server was given a new key on purpose, unpin it in the monitor's settings."
                else -> "Self-signed? Set the certificate rule to trust this key or any certificate."
            },
        )
    }

    private fun summary(snapshot: MailSnapshot, folder: String, latencyMs: Long): String {
        val where = if (folder.equals("INBOX", ignoreCase = true)) "the inbox" else folder
        val counts = when {
            snapshot.messages < 0 -> "Read $where"
            snapshot.unseen < 0 -> "${snapshot.messages} in $where"
            else -> "${snapshot.unseen} unread of ${snapshot.messages} in $where"
        }
        return "Signed in. $counts, in ${latencyMs}ms"
    }

    private fun failed(
        kind: FailureKind,
        message: String,
        detail: String = "",
        hint: String = "",
        cert: X509Certificate? = null,
    ): CheckResult =
        CheckResult(
            ok = false,
            latencyMs = 0,
            failureKind = kind,
            message = message,
            detail = detail,
            hint = hint,
            at = nowMs(),
        ).withCert(cert)

    private fun CheckResult.withCert(cert: X509Certificate?): CheckResult {
        if (cert == null) return this
        return copy(
            certExpiresAt = cert.notAfter.time,
            certIssuer = cert.issuerX500Principal.name
                .split(',')
                .firstOrNull { it.trim().startsWith("CN=") }
                ?.substringAfter("CN=")
                .orEmpty(),
            certSpki = TlsTrustConfig.pinOf(cert),
        )
    }
}

/**
 * The mailbox password in the [me.river.nightbell.data.auth.CredentialVault],
 * with the refused fingerprint the health kind already keeps there.
 */
class VaultMailCredentials(
    private val vault: me.river.nightbell.data.auth.CredentialVault,
) : MailChecker.Credentials {

    override suspend fun password(monitorId: String): MailChecker.Stored =
        when (val read = vault.read(monitorId)) {
            me.river.nightbell.data.auth.CredentialVault.Read.Empty -> MailChecker.Stored.None
            me.river.nightbell.data.auth.CredentialVault.Read.Unreadable -> MailChecker.Stored.Unreadable
            is me.river.nightbell.data.auth.CredentialVault.Read.Found -> {
                val entry = read.entry
                if (entry.password.isEmpty()) {
                    MailChecker.Stored.None
                } else {
                    MailChecker.Stored.Password(
                        value = entry.password,
                        refusedBefore = entry.refusedFingerprint.isNotEmpty() &&
                            entry.refusedFingerprint ==
                            me.river.nightbell.data.auth.CredentialVault.fingerprint(entry.password),
                    )
                }
            }
        }

    override suspend fun refused(monitorId: String, password: String) {
        val entry = vault.entry(monitorId)
        // Only if it is still the password that was tried. One saved while the
        // check was out must get its own attempt.
        if (entry.password != password) return
        vault.write(monitorId, entry.copy(refusedFingerprint = me.river.nightbell.data.auth.CredentialVault.fingerprint(password)))
    }

    /** A new password, from setup. Replaces the entry, which drops any refusal with it. */
    suspend fun save(monitorId: String, password: String) {
        vault.write(monitorId, me.river.nightbell.data.auth.VaultEntry(password = password))
    }
}
