package me.river.nightbell.data.check

import me.river.nightbell.data.diag.Diag
import me.river.nightbell.domain.LogEvent
import me.river.nightbell.domain.LogField
import me.river.nightbell.domain.Assertions
import me.river.nightbell.domain.CheckResult
import me.river.nightbell.domain.FailureKind
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.HttpMethod
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.ProxyRoute
import me.river.nightbell.domain.TlsFailure
import me.river.nightbell.domain.Validation
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Executes HTTP status and advanced request/response monitors.
 *
 * Failures are classified into [FailureKind] so the UI can explain *why*
 * something broke rather than dumping a stack trace at the user.
 */
class HttpChecker(
    baseClient: OkHttpClient? = null,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /**
     * The settings a check is judged against, read per check rather than captured.
     *
     * The graph builds this checker once at startup while the proxy address and
     * the proxied timeout are things the user can retype at any point afterwards,
     * so reading them here is what lets a corrected port take effect on the next
     * check instead of the next launch.
     */
    private val settingsFor: () -> GlobalSettings = { GlobalSettings() },
) {
    private val base: OkHttpClient = baseClient ?: OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .build()

    /**
     * @param certPin the key this monitor is already pinned to, from
     *   `MonitorRuntime.certPin`. Empty arms the pin instead of enforcing it, and
     *   is ignored entirely unless the monitor asked for [TlsTrust.PINNED].
     */
    suspend fun check(monitor: Monitor, certPin: String = ""): CheckResult = withContext(Dispatchers.IO) {
        val urlNote = Validation.urlNote(monitor.url)
        if (urlNote?.severity == Validation.Severity.ERROR) {
            return@withContext CheckResult(
                ok = false,
                latencyMs = 0,
                failureKind = FailureKind.BAD_CONFIG,
                message = urlNote.message,
                detail = "The monitor URL failed validation before any request was sent.",
                at = nowMs(),
            )
        }

        val settings = settingsFor()
        val route = ProxyRoute.forMonitor(monitor, settings)
        // Refused, not downgraded. A monitor that asked to be routed and has
        // nowhere to route through is a configuration failure, and the one thing
        // it must never do is go out directly: that publishes the hostname the
        // proxy existed to hide, to this device's resolver, on every interval,
        // without ever telling the user it stopped protecting them.
        if (route is ProxyRoute.Route.Unconfigured) {
            return@withContext CheckResult(
                ok = false,
                latencyMs = 0,
                failureKind = FailureKind.BAD_CONFIG,
                message = "No proxy to route through",
                detail = "This monitor is set to use a SOCKS5 proxy and no usable " +
                    "address is configured, so the check was not sent. Set one in " +
                    "Settings, or on the monitor itself, or turn routing off.",
                at = nowMs(),
            )
        }
        val endpoint = (route as? ProxyRoute.Route.Via)?.endpoint
        // A routed check gets its own budget. Most of the wait is Tor building a
        // rendezvous circuit, which says nothing about the monitored service, and
        // the ordinary 15s reads a perfectly healthy hidden service as down.
        val timeout = monitor.effectiveTimeoutSeconds(settings, proxied = endpoint != null)
        val builder = base.newBuilder()
            .connectTimeout(timeout.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeout.toLong(), TimeUnit.SECONDS)
            .writeTimeout(timeout.toLong(), TimeUnit.SECONDS)
            .callTimeout((timeout + 5).toLong(), TimeUnit.SECONDS)
            .followRedirects(monitor.followRedirects)
            .followSslRedirects(monitor.followRedirects)
            .apply { if (endpoint != null) proxy(socksProxy(endpoint)) }
        // Returns null under TlsTrust.SYSTEM, where nothing is overridden and the
        // certificate is read from the response as it always was. See TlsTrustConfig.
        val tlsSession = TlsTrustConfig.apply(builder, monitor.tlsTrust, certPin)
        val client = builder.build()

        val request = runCatching { buildRequest(monitor) }.getOrElse { error ->
            return@withContext CheckResult(
                ok = false,
                latencyMs = 0,
                failureKind = FailureKind.BAD_CONFIG,
                message = "Could not build the request",
                detail = error.message ?: error::class.java.simpleName,
                at = nowMs(),
            )
        }

        Diag.log(
            LogEvent.HTTP_REQUEST,
            LogField.monitor(monitor.id),
            LogField.of("method", monitor.method),
            LogField.route("url", monitor.url),
            LogField.of("timeout_s", timeout),
            LogField.of("redirects", monitor.followRedirects),
            LogField.of("trust", monitor.tlsTrust),
            LogField.count("headers", monitor.headers.size),
            LogField.of("proxied", endpoint != null),
        )
        if (endpoint != null) {
            Diag.log(
                LogEvent.HTTP_PROXY,
                LogField.monitor(monitor.id),
                LogField.host("host", endpoint.host),
                LogField.of("port", endpoint.port),
            )
        }
        attempt(client, request, monitor, timeout, tlsSession, retriesLeft = 1, pinnedTo = certPin)
    }

    /**
     * One request, plus a single retry reserved for a connection this app
     * poisoned rather than a service that is actually down.
     *
     * [HttpChecker] is a process singleton, so its connection pool outlives the
     * gap between one check and the next. A monitor on a short interval routinely
     * picks up a keep-alive connection the far end reaped in the meantime: OkHttp
     * writes the request into a socket that is already closed, reads EOF, and
     * throws "unexpected end of stream" before a single byte of response exists.
     * Nothing in that says the site is down, and on a slow link there is more
     * time for the far end to hang up, which is why it shows up there first.
     *
     * The retry stays narrow and `retryOnConnectionFailure` stays off. The
     * blanket version also retries refused connections and connect timeouts,
     * which is how a genuinely dead host costs two full timeouts and is reported
     * down anyway. Only a connection that came out of the pool and died before
     * the response began earns a second attempt:
     *
     *  - pooled, because a fresh socket dying mid-request is the server's problem
     *    and has to be reported as one,
     *  - before the response began, because anything after that is a real answer
     *    for the assertions to judge,
     *  - not a timeout, because slow is the one failure a monitor exists to catch,
     *  - idempotent, because "the connection died before a response" cannot be told
     *    apart on the wire from "the server read it, acted on it, and then died".
     *    Replaying a POST there submits it twice, and a monitor is not worth a
     *    duplicate order,
     *  - and only when the first attempt failed fast enough to leave room for a
     *    second. That is a bound on how early the retry may start, not on when it
     *    finishes: the new call carries its own timeouts, so a retried check can
     *    run to roughly half the timeout plus a full one. Sized deliberately, and
     *    worth stating rather than pretending otherwise.
     *
     * Each attempt is timed on its own, so a retried check reports the round trip
     * that produced the verdict instead of one inflated by a dead socket that was
     * never the user's to pay for.
     */
    private fun attempt(
        client: OkHttpClient,
        request: Request,
        monitor: Monitor,
        timeoutSeconds: Int,
        tlsSession: TlsTrustConfig.Session?,
        retriesLeft: Int,
        /** Only ever asked whether it is set, for the certificate trace. */
        pinnedTo: String = "",
    ): CheckResult {
        val watcher = ConnectionWatcher()
        val call = client.newBuilder()
            .eventListener(watcher)
            .build()
            .newCall(request)
        val started = System.nanoTime()
        return try {
            call.execute().use { response ->
                val body = if (monitor.method == HttpMethod.HEAD) "" else readBoundedBody(response)
                val latency = elapsedMs(started)
                val leaf = leafCertificate(response, tlsSession)
                val verdict = Assertions.evaluateHttp(monitor, response.code, body)
                Diag.log(
                    LogEvent.HTTP_RESPONSE,
                    LogField.monitor(monitor.id),
                    LogField.of("status", response.code),
                    LogField.ms("latency", latency),
                    LogField.tag("protocol", response.protocol.name),
                    LogField.count("body_bytes", body.length),
                    LogField.of("passed", verdict.passed),
                    LogField.of("kind", verdict.kind),
                )
                if (leaf != null) {
                    Diag.log(
                        LogEvent.HTTP_TLS,
                        LogField.monitor(monitor.id),
                        LogField.of("expires_in_days", (leaf.notAfter.time - nowMs()) / 86_400_000L),
                        LogField.text("issuer", issuerOf(leaf)),
                    )
                }
                CheckResult(
                    ok = verdict.passed,
                    latencyMs = latency,
                    statusCode = response.code,
                    failureKind = verdict.kind,
                    message = if (verdict.passed) {
                        "HTTP ${response.code} in ${latency}ms"
                    } else {
                        verdict.message
                    },
                    detail = if (verdict.passed) {
                        "${response.protocol.name} · ${response.code} ${response.message}"
                    } else {
                        verdict.detail
                    },
                    bodyPreview = body.take(MAX_PREVIEW),
                    certExpiresAt = leaf?.notAfter?.time ?: 0L,
                    certIssuer = issuerOf(leaf),
                    certSpki = TlsTrustConfig.pinOf(leaf),
                    at = nowMs(),
                )
            }
        } catch (cancellation: CancellationException) {
            // Not a failure of the monitored service, so it must not become one.
            // Note it would otherwise fall through `classify` into
            // `FailureKind.UNKNOWN` — CancellationException is an
            // IllegalStateException, not an IOException — and be reported as
            // "Check failed". See CheckerHealth.
            throw cancellation
        } catch (error: Throwable) {
            val latency = elapsedMs(started)
            if (retriesLeft > 0 &&
                monitor.method.isIdempotent &&
                watcher.diedBeforeAnswering(error) &&
                latency * 2 < timeoutSeconds * 1_000L
            ) {
                // Issue 3 was "IOException: unexpected end of stream" and there
                // was nothing anywhere saying whether the retry had happened or
                // what the connection had done first. Both are here now.
                Diag.log(
                    LogEvent.HTTP_RETRY,
                    LogField.monitor(monitor.id),
                    LogField.error("after", error),
                    LogField.ms("failed_at", latency),
                    LogField.of("upgraded", !watcher.schemeUpgradedTo.isNullOrBlank()),
                )
                return attempt(client, request, monitor, timeoutSeconds, tlsSession, retriesLeft - 1, pinnedTo)
            }
            val kind = classify(error)
            if (kind == FailureKind.TLS) {
                Diag.log(
                    LogEvent.HTTP_TLS_REFUSED,
                    LogField.monitor(monitor.id),
                    LogField.tag("cause", tlsCause(error)::class.java.simpleName.lowercase()),
                    LogField.of("trust", monitor.tlsTrust),
                    LogField.present("pin", pinnedTo),
                )
            }
            Diag.log(
                LogEvent.HTTP_ERROR,
                LogField.monitor(monitor.id),
                LogField.of("kind", kind),
                LogField.ms("failed_at", latency),
                LogField.of("retries_left", retriesLeft),
                LogField.error("error", error),
            )
            CheckResult(
                ok = false,
                latencyMs = latency,
                failureKind = kind,
                message = describe(error, kind, monitor),
                detail = detail(error, kind, monitor, watcher.schemeUpgradedTo),
                at = nowMs(),
            )
        }
    }

    /**
     * A SOCKS5 route for OkHttp.
     *
     * The proxy's own address is resolved here, because the proxy is somewhere
     * this device can reach. The monitored host is deliberately not resolved
     * anywhere: OkHttp builds an *unresolved* socket address for a SOCKS route
     * and hands the name over for the proxy to look up. That is the whole reason
     * an .onion address works through this at all, since it has no record any
     * resolver on this device could answer with. It also keeps a clearnet host
     * that was routed on purpose off the device's own DNS, which would otherwise
     * announce every hostname the proxy was supposed to be hiding.
     */
    private fun socksProxy(route: ProxyRoute.Endpoint): Proxy =
        Proxy(Proxy.Type.SOCKS, InetSocketAddress(route.host, route.port))

    private fun buildRequest(monitor: Monitor): Request {
        val builder = Request.Builder().url(monitor.url.trim())
        monitor.headers.filterNot { it.isBlank }.forEach { header ->
            builder.header(header.name.trim(), header.value.trim())
        }
        if (monitor.headers.none { it.name.equals("User-Agent", true) }) {
            builder.header("User-Agent", USER_AGENT)
        }
        builder.header("Cache-Control", "no-cache")

        val method = monitor.method.name
        if (monitor.method.allowsBody) {
            val media = monitor.contentType.trim().ifBlank { "text/plain" }.toMediaTypeOrNull()
            builder.method(method, monitor.body.toRequestBody(media))
        } else {
            builder.method(method, null)
        }
        return builder.build()
    }

    private fun readBoundedBody(response: okhttp3.Response): String {
        val stream = response.body.byteStream()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (total < MAX_BODY_BYTES) {
            val read = try {
                stream.read(buffer)
            } catch (_: IOException) {
                break
            }
            if (read <= 0) break
            val allowed = minOf(read, MAX_BODY_BYTES - total)
            out.write(buffer, 0, allowed)
            total += allowed
        }
        return out.toString("UTF-8")
    }

    /**
     * The certificate this connection actually presented, or null.
     *
     * Reads the *leaf* rather than the chain: an intermediate valid for another
     * five years says nothing about the certificate that is going to stop working.
     *
     * Two sources, because there have to be. Under a custom trust manager OkHttp
     * reports `handshake.peerCertificates` as empty, since it derives them through
     * a chain cleaner that has no trusted root to build a path to; the trust
     * manager itself is handed the real chain, and records it. Under
     * [me.river.nightbell.domain.TlsTrust.SYSTEM] there is no custom manager and no
     * session, and the response is the source it has always been.
     *
     * Null rather than throwing for plain HTTP, for a cached response with no
     * handshake attached, and for anything that is not X.509, because a certificate
     * the checker cannot read has to degrade to "no opinion" and never to
     * "expiring".
     */
    private fun leafCertificate(
        response: okhttp3.Response,
        tlsSession: TlsTrustConfig.Session?,
    ): X509Certificate? = runCatching {
        tlsSession?.leaf
            ?: response.handshake?.peerCertificates?.firstOrNull() as? X509Certificate
    }.getOrNull()

    /** Issuer common name, for the detail screen. Falls back to the whole DN. */
    private fun issuerOf(leaf: X509Certificate?): String = runCatching {
        val dn = leaf?.issuerX500Principal?.name.orEmpty()
        CN_IN_DN.find(dn)?.groupValues?.get(1)?.trim().orEmpty().ifBlank { dn }
    }.getOrDefault("")

    private fun elapsedMs(startedNano: Long): Long =
        ((System.nanoTime() - startedNano) / 1_000_000L).coerceAtLeast(0L)

    private fun classify(error: Throwable): FailureKind = when (error) {
        is UnknownHostException -> FailureKind.DNS
        is SocketTimeoutException, is InterruptedIOException -> FailureKind.TIMEOUT
        is SSLException -> FailureKind.TLS
        is ConnectException -> FailureKind.CONNECT
        is IllegalArgumentException -> FailureKind.BAD_CONFIG
        is IOException -> FailureKind.CONNECT
        else -> FailureKind.UNKNOWN
    }

    private fun describe(error: Throwable, kind: FailureKind, monitor: Monitor): String = when {
        kind == FailureKind.DNS -> "Can't resolve ${monitor.prettyHost.substringBefore('/')}"
        kind == FailureKind.TIMEOUT -> "No response within ${monitor.timeoutSeconds}s"
        kind == FailureKind.TLS -> TlsFailure.headline(
            cause = tlsCause(error),
            hiddenService = ProxyRoute.isHiddenService(monitor.url),
        )
        kind == FailureKind.CONNECT -> "Connection refused or dropped"
        kind == FailureKind.BAD_CONFIG -> "Invalid request: ${error.message ?: "bad configuration"}"
        else -> error.message ?: "Unexpected failure"
    }

    /**
     * How the TLS layer refused, as far as the copy needs to know.
     *
     * The pin case is ours and is recognisable by type. The untrusted-chain case
     * is not: JSSE gives no distinct exception for it, so it is read off the cause
     * chain, where a `CertPathValidatorException` sits under the handshake failure
     * on both Conscrypt and the JVM even though the two print different messages.
     * Matching on the type rather than the text is the whole point.
     */
    private fun tlsCause(error: Throwable): TlsFailure.Cause {
        TlsTrustConfig.pinMismatch(error)?.let {
            return TlsFailure.Cause.PinMismatch(it.expected, it.actual)
        }
        val untrusted = generateSequence(error as Throwable?) {
            if (it.cause === it) null else it.cause
        }.any { it is CertPathValidatorException || it is CertificateException }
        return if (untrusted) TlsFailure.Cause.UntrustedChain else TlsFailure.Cause.Other
    }

    private fun detail(
        error: Throwable,
        kind: FailureKind,
        monitor: Monitor,
        upgradedTo: String?,
    ): String {
        val raw = "${error::class.java.simpleName}: ${error.message ?: "no message"}"
        if (kind != FailureKind.TLS) return raw
        val explanation = TlsFailure.explanation(
            cause = tlsCause(error),
            hiddenService = ProxyRoute.isHiddenService(monitor.url),
            schemeUpgradedTo = upgradedTo,
        )
        return "$explanation\n\n$raw"
    }

    /**
     * Whether a call was handed a connection out of the pool, and how far it got.
     *
     * `connectStart` fires only when OkHttp has to open a socket, so a call that
     * acquired a connection without ever opening one was given a pooled one.
     * That is the whole trick: OkHttp exposes no "was this reused" flag, but it
     * does say whether it had to dial.
     */
    private class ConnectionWatcher : EventListener() {

        @Volatile private var dialled = false

        @Volatile private var acquired = false

        @Volatile private var responded = false

        /**
         * The `https` location an `http` request was sent to, if that happened.
         *
         * Not latched off `responseHeadersEnd`'s reset below, because this one is
         * about the whole call rather than about the exchange that failed. It is
         * the fact that explains issue #6, and by the time the handshake fails the
         * hop is two exchanges in the past.
         */
        @Volatile var schemeUpgradedTo: String? = null
            private set

        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            dialled = true
        }

        override fun connectionAcquired(call: Call, connection: Connection) {
            acquired = true
        }

        override fun responseHeadersStart(call: Call) {
            responded = true
        }

        /**
         * A redirect is a second exchange on the same call, so the flags reset here.
         *
         * Latched across the whole call they described the *first* hop forever, and
         * `followRedirects` is on by default: one `http` to `https` hop, which is
         * most of the web, left `responded` true and made the retry unreachable for
         * the rest of the call. Reset at the end of each response so what is read
         * after a failure describes the exchange that actually failed.
         */
        override fun responseHeadersEnd(call: Call, response: okhttp3.Response) {
            dialled = false
            acquired = false
            responded = false

            // An http request answered with a redirect to https. Recorded here and
            // not from the failure, because the failure has no idea a hop happened.
            if (response.isRedirect && !response.request.url.isHttps) {
                val location = response.header("Location").orEmpty()
                val target = response.request.url.resolve(location)
                if (target != null && target.isHttps) {
                    schemeUpgradedTo = "${target.scheme}://${target.host}"
                }
            }
        }

        /**
         * True when a connection was established and then died before answering.
         *
         * Two shapes, one rule. The first is the reported one: a keep-alive
         * connection the far end had already reaped, which fails the instant the
         * request is written into it. The second was found by putting a real check
         * across an emulated GPRS link, where the *first* connection to a perfectly
         * healthy endpoint failed with `Required SETTINGS preface not received`
         * after 22 seconds, was reported as "Connection refused or dropped", and
         * succeeded on the very next attempt. Both are a connection that broke, and
         * neither is evidence about the monitored service.
         *
         * What is deliberately excluded is everything that says something real:
         *
         *  - timeouts, because slow is the failure a monitor exists to catch,
         *  - a refused connection or an unresolvable name, which never get far
         *    enough to acquire a connection and so never reach this at all,
         *  - anything after the response began, which is a real answer.
         *
         * Note this no longer requires the connection to have come from the pool.
         * It did at first, on the reasoning that a fresh socket dying is the
         * server's problem. The GPRS measurement says otherwise: on a bad link a
         * fresh socket dies for reasons that have nothing to do with the server,
         * which is exactly the false outage the reporter was seeing.
         */
        fun diedBeforeAnswering(error: Throwable): Boolean =
            acquired && !responded &&
                error is IOException &&
                // SocketTimeoutException is an InterruptedIOException, so this one
                // check excludes the read timeout and the call timeout together.
                error !is InterruptedIOException
    }

    companion object {
        /** `CN=…` inside an X.500 DN, stopping at the first unescaped comma. */
        private val CN_IN_DN = Regex("""CN=((?:\\.|[^,])*)""")
        private const val MAX_BODY_BYTES = 512 * 1024
        private const val MAX_PREVIEW = 4_000
        const val USER_AGENT = "NightbellMonitor/1.0 (Android)"
    }
}
