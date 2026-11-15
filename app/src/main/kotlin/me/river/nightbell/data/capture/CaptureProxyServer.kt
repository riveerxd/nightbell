package me.river.nightbell.data.capture

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

private const val CRLF = "\r\n"
private const val MAX_SAMPLE = 64 * 1024
private const val MAX_LOG_TEXT = 16 * 1024

/**
 * A plain HTTP proxy bound to 127.0.0.1, announced by
 * `VpnService.Builder.setHttpProxy`. Three shapes arrive:
 *
 *  - Absolute-form plain HTTP (`GET http://host/api HTTP/1.1`): fully visible.
 *  - `CONNECT host:443` for a host the user did NOT allowlist: piped through
 *    byte-for-byte. Only host and port are recorded, honestly.
 *  - `CONNECT host:443` for an allowlisted host: the local CA terminates TLS,
 *    the decrypted HTTP/1.1 request is forwarded to the real origin over TLS,
 *    and the response is written back. That is the only case where the path,
 *    headers, body and response are known for HTTPS. Nothing is decrypted
 *    unless the user explicitly turned it on for that host.
 *
 * Requests are relayed as raw HTTP/1.1 rather than re-issued through a second
 * HTTP client, so headers, body framing and hop-by-hop quirks survive. ALPN is
 * deliberately not offered to the client: with no negotiated protocol, browsers
 * and OkHttp fall back to HTTP/1.1 instead of trying to speak h2 to a proxy
 * that only understands HTTP/1.1.
 */
class CaptureProxyServer(
    private val context: Context,
    private val onRequest: (CapturedEndpoint) -> Unit,
    private val onResult: (String, Int?, Long?, String?) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private val tunnelSequence = AtomicLong(0)
    private var server: ServerSocket? = null

    /**
     * Sockets currently owned by a CONNECT (either a raw tunnel or a MITM
     * session). A TLS tunnel can stay open for minutes while a client reuses it
     * for many requests: the proxy only ever sees the initial CONNECT, so if
     * the interface list is cleared, those requests can never be discovered
     * again. Closing these sockets forces the client to open fresh CONNECTs.
     */
    private val openConnects = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()

    /** Drops every live CONNECT so the next request is visible again. */
    fun closeOpenTunnels(): Int {
        val sockets = openConnects.toList()
        sockets.forEach { runCatching { it.close() } }
        return sockets.size
    }

    /**
     * A CONNECT hides the path, so several calls to one host used to land on a
     * single row. Give each tunnel its own identity: the user can then tick and
     * test each connection separately, and allowlisting + re-visiting replaces
     * them with the real decrypted interfaces.
     */
    private fun tunnelKey(host: String, port: Int): String =
        "CONNECT https://$host:$port/ #" + tunnelSequence.incrementAndGet()
    private val pool = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "nightbell-capture").apply { isDaemon = true }
    }

    /**
     * Intranet HTTPS often uses a self-signed certificate that the client had
     * manually accepted. The proxy has to be able to reach it too, so upstream
     * verification is relaxed *only here*. Nightbell's own monitors and the
     * one-shot tests still use the normal trust store.
     */
    private val permissiveTls: SSLSocketFactory by lazy {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        }.socketFactory
    }

    /** Binds an ephemeral loopback port and returns it, so the VPN can announce it. */
    fun start(): Int {
        val socket = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        server = socket
        running.set(true)
        pool.execute {
            while (running.get()) {
                try {
                    val client = socket.accept()
                    pool.execute { handle(client) }
                } catch (_: Exception) {
                    if (running.get()) continue
                    break
                }
            }
        }
        return socket.localPort
    }

    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        server = null
        pool.shutdownNow()
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 30_000
            val input = client.getInputStream()
            val output = client.getOutputStream()
            val requestLine = readAsciiLine(input) ?: return
            val parts = requestLine.split(' ', limit = 3)
            if (parts.size < 2) return
            val headers = readHeaders(input)
            if (parts[0].equals("CONNECT", ignoreCase = true)) {
                // A TLS tunnel can sit idle for minutes (push, websockets).
                client.soTimeout = 0
                openConnects.add(client)
                try {
                    handleConnect(client, parts[1], input, output)
                } finally {
                    openConnects.remove(client)
                }
            } else {
                handlePlain(parts[0], parts[1], headers, input, output)
            }
        } catch (_: Exception) {
            // A client that walked away mid-request, or an upstream that died.
        } finally {
            runCatching { client.close() }
        }
    }

    private fun handlePlain(
        method: String,
        target: String,
        headers: List<Pair<String, String>>,
        input: InputStream,
        output: OutputStream,
    ) {
        val uri = runCatching { URI(target) }.getOrNull()
        val absolute = uri?.isAbsolute == true &&
            (uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true))
        val scheme = if (absolute) uri!!.scheme.lowercase() else "http"
        val (host, port) = if (absolute) {
            val parsedHost = uri!!.host ?: return
            parsedHost to if (uri.port > 0) uri.port else if (scheme == "https") 443 else 80
        } else {
            parseHostPort(header(headers, "Host").orEmpty(), 80)
        }
        if (host.isBlank()) return
        val rawPath = if (absolute) {
            (uri!!.rawPath?.takeIf { it.isNotEmpty() } ?: "/") +
                uri.rawQuery?.let { "?$it" }.orEmpty()
        } else {
            target.ifEmpty { "/" }
        }
        relay(method, rawPath, headers, input, output, scheme, host, port, decrypted = false)
    }

    private fun handleConnect(
        client: Socket,
        target: String,
        input: InputStream,
        output: OutputStream,
    ) {
        val (host, port) = parseHostPort(target, 443)
        if (host.isBlank()) return
        val reason = CaptureSettings.state.value.blockReason(host)
        if (reason == null) {
            handleMitm(client, host, port, output)
        } else {
            handleTunnel(host, port, input, output, reason)
        }
    }

    /** No CA installed / not allowlisted: keep the old, honest host+port behaviour. */
    private fun handleTunnel(
        host: String,
        port: Int,
        input: InputStream,
        output: OutputStream,
        blockReason: String,
    ) {
        val authority = host + if (port != 443) ":$port" else ""
        val key = tunnelKey(host, port)
        onRequest(
            CapturedEndpoint(
                key = key,
                method = "CONNECT",
                scheme = "https",
                host = host,
                port = port,
                path = "/",
                url = "https://$authority/",
                tlsOnly = true,
                blockReason = blockReason,
            ),
        )
        val upstream = try {
            Socket(host, port).apply { soTimeout = 0 }
        } catch (e: Exception) {
            onResult(key, null, null, e.message ?: "connect failed")
            writeAscii(output, "HTTP/1.1 502 Bad Gateway${CRLF}Connection: close$CRLF$CRLF")
            return
        }
        try {
            writeAscii(output, "HTTP/1.1 200 Connection Established$CRLF$CRLF")
            Thread {
                try {
                    copyAll(input, upstream.getOutputStream())
                    runCatching { upstream.shutdownOutput() }
                } catch (_: Exception) {
                }
            }.apply { isDaemon = true }.start()
            copyAll(upstream.getInputStream(), output)
            onResult(key, 200, null, null)
        } catch (_: Exception) {
            // A tunnel ending is normal: the client closed it, the upstream went
            // away, or the capture page deliberately dropped it so the client
            // would re-CONNECT. Never label the captured CONNECT as a failed
            // interface — that reads as "the host is down" when it is not.
            onResult(key, 200, null, null)
        } finally {
            runCatching { upstream.close() }
        }
    }

    /**
     * Terminates TLS with a locally minted leaf certificate, then relays one
     * decrypted HTTP/1.1 request. One request per connection keeps this simple
     * and reliable; clients open a fresh tunnel for the next one.
     */
    private fun handleMitm(client: Socket, host: String, port: Int, plainOut: OutputStream) {
        val started = System.currentTimeMillis()
        try {
            writeAscii(plainOut, "HTTP/1.1 200 Connection Established$CRLF$CRLF")
            val tls = CaptureCa.serverFactory(context, host)
                .createSocket(client, host, port, true) as SSLSocket
            tls.useClientMode = false
            tls.soTimeout = 60_000
            tls.startHandshake()

            val tlsIn = tls.inputStream
            val tlsOut = tls.outputStream
            val requestLine = readAsciiLine(tlsIn) ?: return
            val parts = requestLine.split(' ', limit = 3)
            if (parts.size < 2) return
            val headers = readHeaders(tlsIn)
            relay(parts[0], parts[1], headers, tlsIn, tlsOut, "https", host, port, decrypted = true)
            runCatching { tlsOut.flush() }
            // Do not drop the host's CONNECT rows here. relay() may have returned
            // before recording anything (bad request line, upstream failure);
            // CaptureRepository.record() retires the placeholders only when a
            // real decrypted interface has actually been written.
        } catch (e: Exception) {
            // Almost always "client did not trust the Nightbell CA". Surface it
            // in BOTH places: the request log for the raw reason, and the
            // interface list so the row stops claiming "add the host to the
            // allowlist" when the allowlist was in fact already correct.
            val detail = e.message ?: "the client did not trust the Nightbell root CA"
            val elapsed = System.currentTimeMillis() - started
            // Independent: a failure in the log writer must never swallow the
            // interface row (which is the only visible sign of the host).
            runCatching {
                CaptureLogRepository.record(
                    RequestLogEntry(
                        source = "capture-https",
                        method = "CONNECT",
                        url = "https://$host:$port/",
                        error = "TLS handshake failed: $detail",
                        latencyMs = elapsed,
                    ),
                )
            }
            runCatching {
                onRequest(
                    CapturedEndpoint(
                        key = tunnelKey(host, port),
                        method = "CONNECT",
                        scheme = "https",
                        host = host,
                        port = port,
                        path = "/",
                        url = "https://$host:$port/",
                        tlsOnly = true,
                        blockReason = "handshake",
                        error = "TLS handshake failed: $detail",
                        latencyMs = elapsed,
                    ),
                )
            }
        }
    }

    /** Relays one visible HTTP/1.1 exchange and writes a full request log entry. */
    private fun relay(
        method: String,
        rawPath: String,
        headers: List<Pair<String, String>>,
        input: InputStream,
        output: OutputStream,
        scheme: String,
        host: String,
        port: Int,
        decrypted: Boolean,
    ) {
        val started = System.currentTimeMillis()
        val visiblePort = (scheme == "http" && port != 80) || (scheme == "https" && port != 443)
        val authority = host + if (visiblePort) ":$port" else ""
        val url = "$scheme://$authority$rawPath"
        val path = rawPath.substringBefore('?').ifEmpty { "/" }
        // Full path + query, later refined with the body fingerprint once the
        // request has actually been read. Two interfaces of one host must not
        // fold into a single untestable row.
        var key = capturedEndpointKey(method, scheme, host, port, rawPath)
        val safeHeaders = headers.filterNot { isSecret(it.first) || isHopByHop(it.first) }

        val upstream = try {
            val socket = if (scheme == "https") {
                if (decrypted) {
                    permissiveTls.createSocket(host, port)
                } else {
                    (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(host, port)
                }
            } else {
                Socket(host, port)
            }
            socket.apply { soTimeout = 30_000 }
        } catch (e: Exception) {
            val message = e.message ?: "connect failed"
            onResult(key, null, null, message)
            logExchange(
                started, if (decrypted) "capture-https" else "capture-http", method, url,
                safeHeaders, "", null, emptyList(), "", message, decrypted,
            )
            // The interface is still a real one even when the upstream refused
            // the connection. Record it, otherwise the host silently vanishes
            // from the list and the only clue is buried in the request log.
            runCatching {
                onRequest(
                    CapturedEndpoint(
                        key = key,
                        method = method,
                        scheme = scheme,
                        host = host,
                        port = port,
                        path = path,
                        url = url,
                        headers = safeHeaders,
                        decrypted = decrypted,
                        error = message,
                    ),
                )
            }
            writeAscii(output, "HTTP/1.1 502 Bad Gateway${CRLF}Connection: close$CRLF$CRLF")
            return
        }

        var statusCode: Int? = null
        var responseHeaders: List<Pair<String, String>> = emptyList()
        var error: String? = null
        val requestSample = ByteArrayOutputStream()
        val responseSample = ByteArrayOutputStream()
        try {
            val out = upstream.getOutputStream()
            val head = buildString {
                append(method).append(' ').append(rawPath).append(" HTTP/1.1").append(CRLF)
                // Expect is answered by us above, so the origin never sees it
                // and cannot send a second interim 100 that looks like a status.
                safeHeaders.filterNot { it.first.equals("Expect", ignoreCase = true) }
                    .forEach { (name, value) ->
                        // Negotiate only encodings both the real client and the
                        // log decoder can handle. A server answering br/zstd to a
                        // phone that asked for it fills the log with binary noise
                        // that reads exactly like "HTTPS was not decrypted".
                        val forwarded = if (name.equals("Accept-Encoding", ignoreCase = true)) {
                            value.split(',')
                                .map { it.trim().substringBefore(';').trim() }
                                .filter { enc ->
                                    enc.equals("gzip", ignoreCase = true) ||
                                        enc.equals("deflate", ignoreCase = true) ||
                                        enc.equals("identity", ignoreCase = true)
                                }
                                .joinToString(", ")
                                .ifBlank { "identity" }
                        } else {
                            value
                        }
                        append(name).append(": ").append(forwarded).append(CRLF)
                    }
                if (safeHeaders.none { it.first.equals("Host", ignoreCase = true) }) {
                    append("Host: ").append(authority).append(CRLF)
                }
                append("Connection: close").append(CRLF).append(CRLF)
            }
            writeAscii(out, head)
            // A client that sent `Expect: 100-continue` is waiting for a go-ahead
            // before it will send the body. Without this the relay would block.
            if (headers.any { (name, value) ->
                    name.equals("Expect", ignoreCase = true) &&
                        value.contains("100-continue", ignoreCase = true)
                }
            ) {
                writeAscii(output, "HTTP/1.1 100 Continue$CRLF$CRLF")
            }

            val length = header(headers, "Content-Length")?.trim()?.toLongOrNull()
            val chunked = headers.any { (name, value) ->
                name.equals("Transfer-Encoding", ignoreCase = true) &&
                    value.contains("chunked", ignoreCase = true)
            }
            when {
                length != null && length > 0 -> copyExactly(input, out, length, requestSample)
                chunked -> copyChunked(input, out, requestSample)
            }
            out.flush()

            val upIn = upstream.getInputStream()
            val statusLine = readAsciiLine(upIn) ?: error("no response")
            statusCode = statusLine.split(' ', limit = 3).getOrNull(1)?.toIntOrNull()
            responseHeaders = readHeaders(upIn)
            val outbound = responseHeaders.filterNot { isHopByHop(it.first) } + ("Connection" to "close")
            writeAscii(output, statusLine + CRLF)
            outbound.forEach { (name, value) ->
                writeAscii(output, name + ": " + value + CRLF)
            }
            writeAscii(output, CRLF)

            val code = statusCode ?: 0
            val noBody = method.equals("HEAD", ignoreCase = true) || code in 100..199 ||
                code == 204 || code == 304
            if (!noBody) {
                val responseLength = header(responseHeaders, "Content-Length")?.trim()?.toLongOrNull()
                val responseChunked = responseHeaders.any { (name, value) ->
                    name.equals("Transfer-Encoding", ignoreCase = true) &&
                        value.contains("chunked", ignoreCase = true)
                }
                when {
                    responseLength != null && responseLength > 0 ->
                        copyExactly(upIn, output, responseLength, responseSample)
                    responseChunked -> copyChunked(upIn, output, responseSample)
                    else -> copyAllWithSample(upIn, output, responseSample)
                }
            }
            output.flush()
        } catch (e: Exception) {
            error = e.message ?: "request failed"
        } finally {
            runCatching { upstream.close() }
        }

        val requestBody = readableBody(requestSample.toByteArray(), null)
        val responseBody = readableBody(
            responseSample.toByteArray(),
            header(responseHeaders, "Content-Encoding"),
        )
        // POST APIs often identify the operation inside the body rather than in
        // the URL. Include it in the identity so `{"action":"list"}` and
        // `{"action":"detail"}` on one path become two selectable rows.
        key = capturedEndpointKey(method, scheme, host, port, rawPath, requestBody)
        onRequest(
            CapturedEndpoint(
                key = key,
                method = method,
                scheme = scheme,
                host = host,
                port = port,
                path = path,
                url = url,
                headers = safeHeaders,
                bodySample = requestBody.take(4096),
                responseSample = responseBody.take(4096),
                decrypted = decrypted,
                statusCode = statusCode,
                latencyMs = System.currentTimeMillis() - started,
                error = error,
            ),
        )
        onResult(key, statusCode, System.currentTimeMillis() - started, error)
        logExchange(
            started, if (decrypted) "capture-https" else "capture-http", method, url,
            safeHeaders, requestBody.take(MAX_LOG_TEXT), statusCode,
            responseHeaders.filterNot { isSecret(it.first) || isHopByHop(it.first) },
            responseBody.take(MAX_LOG_TEXT), error.orEmpty(), decrypted,
        )
    }

    private fun logExchange(
        started: Long,
        source: String,
        method: String,
        url: String,
        requestHeaders: List<Pair<String, String>>,
        requestBody: String,
        statusCode: Int?,
        responseHeaders: List<Pair<String, String>>,
        responseBody: String,
        error: String,
        decrypted: Boolean,
    ) {
        CaptureLogRepository.record(
            RequestLogEntry(
                at = started,
                source = source,
                method = method,
                url = url,
                requestHeaders = requestHeaders.map { "${it.first}: ${it.second}" },
                requestBody = requestBody,
                statusCode = statusCode,
                responseHeaders = responseHeaders.map { "${it.first}: ${it.second}" },
                responseBody = responseBody,
                latencyMs = System.currentTimeMillis() - started,
                error = error,
                decrypted = decrypted,
            ),
        )
    }

    /**
     * Transfer-Encoding is intentionally NOT stripped: the relay forwards the
     * chunk framing byte-for-byte, so the header has to travel with it.
     */
    private fun isHopByHop(name: String): Boolean = listOf(
        "Proxy-Connection", "Proxy-Authorization", "Connection", "Keep-Alive",
        "Proxy-Authenticate", "TE", "Trailer", "Upgrade",
    ).any { it.equals(name, ignoreCase = true) }

    /** Never keep a credential in the capture log, and never copy one into a monitor. */
    private fun isSecret(name: String): Boolean = listOf(
        "Authorization", "Proxy-Authorization", "Cookie", "Set-Cookie",
        "X-Api-Key", "Api-Key", "X-Auth-Token", "X-Csrf-Token",
    ).any { it.equals(name, ignoreCase = true) }

    private fun header(headers: List<Pair<String, String>>, name: String): String? =
        headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    private fun readHeaders(input: InputStream): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        while (result.size <= 200) {
            val line = readAsciiLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                result += line.substring(0, colon).trim() to line.substring(colon + 1).trim()
            }
        }
        return result
    }

    private fun readAsciiLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream(128)
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (buffer.size() == 0) null else buffer.toString("ISO-8859-1")
            if (byte == 13) continue
            if (byte == 10) return buffer.toString("ISO-8859-1")
            if (buffer.size() > 16 * 1024) return null
            buffer.write(byte)
        }
    }

    private fun writeAscii(output: OutputStream, text: String) {
        output.write(text.toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    private fun copyAll(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) {
                output.write(buffer, 0, read)
                output.flush()
            }
        }
    }

    private fun copyAllWithSample(input: InputStream, output: OutputStream, sample: ByteArrayOutputStream) {
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) {
                output.write(buffer, 0, read)
                output.flush()
                val keep = minOf(read.toLong(), (MAX_SAMPLE - sample.size()).toLong()).toInt()
                if (keep > 0) sample.write(buffer, 0, keep)
            }
        }
    }

    private fun copyExactly(input: InputStream, output: OutputStream, count: Long, sample: ByteArrayOutputStream) {
        var left = count
        val buffer = ByteArray(8192)
        while (left > 0) {
            val want = minOf(left, buffer.size.toLong()).toInt()
            val read = input.read(buffer, 0, want)
            if (read < 0) break
            output.write(buffer, 0, read)
            val keep = minOf(read.toLong(), (MAX_SAMPLE - sample.size()).toLong()).toInt()
            if (keep > 0) sample.write(buffer, 0, keep)
            left -= read
        }
        output.flush()
    }

    private fun copyChunked(input: InputStream, output: OutputStream, sample: ByteArrayOutputStream) {
        while (true) {
            val line = readAsciiLine(input) ?: break
            writeAscii(output, line + CRLF)
            val size = line.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size <= 0) {
                while (true) {
                    val trailer = readAsciiLine(input) ?: break
                    writeAscii(output, trailer + CRLF)
                    if (trailer.isEmpty()) break
                }
                break
            }
            copyExactly(input, output, size.toLong(), sample)
            val cr = input.read()
            val lf = input.read()
            if (cr >= 0) output.write(cr)
            if (lf >= 0) output.write(lf)
            output.flush()
        }
    }

    /** `host`, `host:port`, or `[::1]:port`. */
    private fun parseHostPort(raw: String, defaultPort: Int): Pair<String, Int> {
        val value = raw.trim()
        if (value.startsWith("[")) {
            val end = value.indexOf(']')
            if (end > 1) {
                val port = value.substring(end + 1).removePrefix(":").toIntOrNull() ?: defaultPort
                return value.substring(1, end) to port
            }
        }
        val colon = value.lastIndexOf(':')
        if (colon > 0) {
            val port = value.substring(colon + 1).toIntOrNull()
            if (port != null) return value.substring(0, colon) to port
        }
        return value to defaultPort
    }
}
