package me.river.nightbell.data.webhook

import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.river.nightbell.data.check.TlsTrustConfig
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.ProxyRoute
import me.river.nightbell.domain.TlsTrust
import me.river.nightbell.domain.WebhookFacts
import me.river.nightbell.domain.WebhookOutbox
import me.river.nightbell.domain.WebhookPayload
import me.river.nightbell.domain.WebhookSecrets
import me.river.nightbell.domain.WebhookTarget
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Sends one rendered event and reports what came back.
 *
 * Never throws for anything the network or the receiver does: every outcome is
 * an [Attempt], because the caller's job is to record it, and an exception
 * escaping a check pass is exactly the kind of fault [me.river.nightbell.domain.CheckerHealth]
 * would then have to explain.
 */
class WebhookSender(
    baseClient: OkHttpClient? = null,
    private val settingsFor: () -> GlobalSettings = { GlobalSettings() },
    private val appVersion: String = "",
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val base: OkHttpClient = baseClient ?: OkHttpClient.Builder()
        // A retry inside OkHttp would be a second POST the outbox does not know
        // about, and the outbox already retries on its own schedule.
        .retryOnConnectionFailure(false)
        .build()

    /**
     * @param code the HTTP status, 0 when no answer arrived at all, and -1 when
     *   the request could not even be built. -1 is never retried: a header name
     *   with a space in it will have a space in it next time too.
     * @param snippet the start of the receiver's answer, scrubbed, for the test
     *   button and the settings row. A 400 from Telegram or Slack says exactly
     *   what was wrong with the message, and showing it is most of the help.
     */
    data class Attempt(
        val code: Int,
        val ms: Long,
        val error: String = "",
        val snippet: String = "",
        val retryAfterMs: Long? = null,
    ) {
        val delivered: Boolean get() = WebhookOutbox.verdict(code) == WebhookOutbox.Verdict.DELIVERED
    }

    suspend fun send(target: WebhookTarget, facts: WebhookFacts, deliveryId: String): Attempt =
        withContext(Dispatchers.IO) {
            val started = nowMs()
            val settings = settingsFor()
            val rendered = WebhookPayload.render(
                target = target,
                facts = facts.copy(sender = facts.sender.ifBlank { settings.webhookSender.trim() }),
                deliveryId = deliveryId,
                sentAtMs = started,
                appVersion = appVersion,
            )
            val url = rendered.url.toHttpUrlOrNull()
                ?: return@withContext Attempt(-1, 0, "The address is not a valid http or https URL")

            val builder = base.newBuilder()
                .connectTimeout(CONNECT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(READ_SECONDS, TimeUnit.SECONDS)
                .callTimeout(CALL_SECONDS, TimeUnit.SECONDS)
            if (target.useProxy) {
                // Refused rather than sent direct, for the reason a routed monitor
                // is: the proxy exists to keep this hostname off the local resolver.
                val endpoint = ProxyRoute.endpoint(settings)
                    ?: return@withContext Attempt(-1, 0, "Set to use the SOCKS5 proxy, and none is configured")
                builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(endpoint.host, endpoint.port)))
            }
            if (target.acceptAnyCertificate) TlsTrustConfig.apply(builder, TlsTrust.ANY, "")
            val client = builder.build()

            val request = runCatching {
                val headers = Headers.Builder()
                // Non-ASCII allowed on purpose: a monitor called "Café" in a header
                // placeholder is sent as UTF-8 rather than throwing. Every receiver
                // worth posting to reads it.
                rendered.headers.forEach { (name, value) -> headers.addUnsafeNonAscii(name, value) }
                val body = rendered.body?.toRequestBody(rendered.contentType?.toMediaTypeOrNull())
                Request.Builder()
                    .url(url)
                    .headers(headers.build())
                    .method(rendered.method.name, body)
                    .build()
            }.getOrElse { error ->
                return@withContext Attempt(-1, 0, scrub(target, "Could not build the request: ${error.message}"))
            }

            try {
                client.newCall(request).execute().use { response ->
                    val snippet = scrub(
                        target,
                        runCatching { response.peekBody(SNIPPET_BYTES).string() }.getOrDefault("").trim(),
                    ).take(SNIPPET_CHARS)
                    val ms = nowMs() - started
                    if (response.isSuccessful) {
                        Attempt(response.code, ms, snippet = snippet)
                    } else {
                        Attempt(
                            code = response.code,
                            ms = ms,
                            error = describe(response.code, snippet),
                            snippet = snippet,
                            retryAfterMs = WebhookOutbox.retryAfterMs(response.header("Retry-After")),
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IOException) {
                Attempt(0, nowMs() - started, scrub(target, networkError(error)))
            } catch (error: Exception) {
                Attempt(0, nowMs() - started, scrub(target, error.message ?: error::class.java.simpleName))
            }
        }

    private fun networkError(error: IOException): String = when (error) {
        is UnknownHostException -> "Host not found"
        is SocketTimeoutException -> "Timed out"
        is ConnectException -> "Could not connect"
        is SSLException -> "TLS handshake failed" + (error.message?.let { ": $it" } ?: "")
        else -> error.message?.ifBlank { null } ?: error::class.java.simpleName
    }

    private fun scrub(target: WebhookTarget, text: String): String = WebhookSecrets.scrub(text, target)

    companion object {
        private const val CONNECT_SECONDS = 10L
        private const val READ_SECONDS = 15L
        private const val CALL_SECONDS = 25L
        private const val SNIPPET_BYTES = 2_048L
        private const val SNIPPET_CHARS = 300

        /**
         * The receiver's answer as a person can read it.
         *
         * A webhook service answers in a line of JSON or text, and that line is
         * the most useful thing on the screen. An address that is not a webhook
         * at all answers with a whole web page, and its first line is a doctype.
         * For those the page's title is the part that says where the request
         * actually landed.
         */
        fun readable(snippet: String): String {
            val text = snippet.trim()
            if (text.isEmpty()) return ""
            val html = text.startsWith("<!doctype", ignoreCase = true) || text.startsWith("<html", ignoreCase = true)
            if (!html) return text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(text)?.groupValues?.get(1)?.replace(Regex("\\s+"), " ")?.trim()
            return if (title.isNullOrBlank()) "a web page, not a webhook" else "a web page titled \"$title\""
        }

        /** What the status means in words, with the receiver's own reason when it gave one. */
        fun describe(code: Int, snippet: String): String {
            val meaning = when (code) {
                400 -> "The service did not accept the message"
                401, 403 -> "The address or its key was refused"
                404 -> "Nothing at that address. Check it was copied whole"
                405 -> "That address does not take this method"
                410 -> "That webhook has been deleted"
                413 -> "The message was too large for the service"
                429 -> "The service is rate limiting, will try again"
                in 500..599 -> "The service had a problem, will try again"
                else -> "Unexpected answer"
            }
            val reason = readable(snippet).take(140)
            return if (reason.isBlank()) "HTTP $code. $meaning" else "HTTP $code. $meaning: $reason"
        }
    }
}
