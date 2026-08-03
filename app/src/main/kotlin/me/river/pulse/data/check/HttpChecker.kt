package me.river.pulse.data.check

import me.river.pulse.domain.Assertions
import me.river.pulse.domain.CheckResult
import me.river.pulse.domain.FailureKind
import me.river.pulse.domain.HttpMethod
import me.river.pulse.domain.Monitor
import me.river.pulse.domain.Validation
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
) {
    private val base: OkHttpClient = baseClient ?: OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .build()

    suspend fun check(monitor: Monitor): CheckResult = withContext(Dispatchers.IO) {
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

        val client = base.newBuilder()
            .connectTimeout(monitor.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(monitor.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .writeTimeout(monitor.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .callTimeout((monitor.timeoutSeconds + 5).toLong(), TimeUnit.SECONDS)
            .followRedirects(monitor.followRedirects)
            .followSslRedirects(monitor.followRedirects)
            .build()

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

        val started = System.nanoTime()
        try {
            client.newCall(request).execute().use { response ->
                val body = if (monitor.method == HttpMethod.HEAD) "" else readBoundedBody(response)
                val latency = elapsedMs(started)
                val verdict = Assertions.evaluateHttp(monitor, response.code, body)
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
            val kind = classify(error)
            CheckResult(
                ok = false,
                latencyMs = latency,
                failureKind = kind,
                message = describe(error, kind, monitor),
                detail = "${error::class.java.simpleName}: ${error.message ?: "no message"}",
                at = nowMs(),
            )
        }
    }

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

    private fun describe(error: Throwable, kind: FailureKind, monitor: Monitor): String = when (kind) {
        FailureKind.DNS -> "Can't resolve ${monitor.prettyHost.substringBefore('/')}"
        FailureKind.TIMEOUT -> "No response within ${monitor.timeoutSeconds}s"
        FailureKind.TLS -> "TLS/certificate error"
        FailureKind.CONNECT -> "Connection refused or dropped"
        FailureKind.BAD_CONFIG -> "Invalid request: ${error.message ?: "bad configuration"}"
        else -> error.message ?: "Unexpected failure"
    }

    companion object {
        private const val MAX_BODY_BYTES = 512 * 1024
        private const val MAX_PREVIEW = 4_000
        const val USER_AGENT = "PulseMonitor/1.0 (Android)"
    }
}
